"""
设备健康评分模型：多指标异常检测 + 位移预测 + 加权融合 → health_score。

设计目标：
  - 输入：单台设备的最近 N 个时序（每指标一段）+ 在线状态 + 上报时间；
  - 内部：复用 anomaly 检测器（Isolation Forest / 3-sigma fallback）+ LSTM 时序预测器；
  - 输出：health_score (0-100) / health_level (HEALTHY/ATTENTION/WARN/CRITICAL) /
    reasoning（可解释归因）/ suggested_actions（可执行建议）；
  - 模型缺失时降级为加权融合 + 启发式归因。所有"算法"在此完成，Java 端只负责调度 + 落库。

权重设计（与 Java AiDeviceHealthService.analyzeOne 历史版本语义对齐，方便平滑迁移）：
  - 异常最严重指标：60%
  - 位移预测风险： 30%
  - 离线惩罚：       -10（>30min）或 -25（上报超时严重）
  - 数据覆盖不足：   -5

阈值分级（4 档）：
  >=80  HEALTHY     设备健康
  >=60  ATTENTION   需关注
  >=40  WARN        预警
  <40   CRITICAL    严重异常
"""
from __future__ import annotations

import math
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple

from app.models.isolation_forest import get_detector
from app.models.lstm_model import get_forecaster
from app.models.feature_engineering import compute_disp_derivative
from app.models.adaptive_normalizer import get_adaptive_normalizer


# 参与评分的核心指标（与 Java 端 HEALTH_METRICS 对齐）
HEALTH_METRICS: List[str] = [
    "disp_rate",
    "disp_total",
    "crack_width",
    "tilt",
]


def _score_to_level(score: float) -> str:
    if score >= 80:
        return "HEALTHY"
    if score >= 60:
        return "ATTENTION"
    if score >= 40:
        return "WARN"
    return "CRITICAL"


def _age_minutes(last_report_at: Optional[str], now: datetime) -> Optional[float]:
    """计算 lastReportAt 到 now 的分钟数，缺省返回 None。"""
    if not last_report_at:
        return None
    try:
        ts = datetime.fromisoformat(last_report_at.replace("Z", "+00:00"))
    except ValueError:
        return None
    if ts.tzinfo is None:
        ts = ts.replace(tzinfo=timezone.utc)
    return (now - ts).total_seconds() / 60.0


def _safe_round(value: float, digits: int = 4) -> float:
    if not math.isfinite(value):
        return 0.0
    factor = 10 ** digits
    return round(value * factor) / factor


class DeviceHealthScorer:
    """单台设备 AI 健康评分：异常检测 + 时序预测 + 加权融合 + 解释性归因。"""

    # 加权参数（暴露为常量以便外部 / 测试调用）
    WEIGHT_ANOMALY = 0.60
    WEIGHT_FORECAST = 0.30
    OFFLINE_PENALTY_MILD = 10.0       # 离线但 lastReportAt 不算太久
    OFFLINE_PENALTY_SEVERE = 25.0     # 离线 + 上报超时 > 30 min
    COVERAGE_PENALTY = 5.0            # 数据覆盖不足（covered < 2）
    FORECAST_DEVIATION_MM = 10.0      # 预测超过 10mm 视为满风险
    COVERAGE_TRUST_FULL = 4           # 4 指标全覆盖视为完全可信任

    # 设备维度上限（v1.4.0：保留做兜底，正常用 adaptive_normalizer）
    DISPLACEMENT_RATE_NORM_MAX = 30.0
    DISPLACEMENT_TOTAL_NORM_MAX = 200.0
    CRACK_WIDTH_NORM_MAX = 50.0
    TILT_NORM_MAX = 5.0

    def __init__(self):
        self._anomaly = get_detector()
        self._forecaster = get_forecaster()
        # v1.4.0：自适应归一化器
        self._normalizer = get_adaptive_normalizer()

    def _dynamic_norm(self, factor_key: str, region_code: str, fallback: float) -> float:
        """自适应上限 helper（样本不足回退 fallback）。"""
        try:
            return self._normalizer.get_dynamic_max(factor_key, region_code, fallback=fallback)
        except Exception:
            return fallback

    # ------------------------------------------------------------------ #
    # 内部：单指标异常评分（v1.4.0：使用自适应归一化修正归一化系数）
    # ------------------------------------------------------------------ #
    def _score_series(self, device_code: str, series: List[float], metric: str) -> Tuple[float, bool]:
        """对一段时序做 anomaly，返回 (score_0_100, degraded)。

        v1.4.0：metric 与自适应归一化器的因子名对齐，让 score 反映"该设备本地的异常程度"，
        而非全局 3-sigma。同时记录观测推进窗口。
        """
        if not series or len(series) < 3:
            return 0.0, True
        # 把每条观测推进窗口
        region = device_code  # 用 device_code 当 region（每设备独立窗口）
        for v in series:
            self._normalizer.fit_one(metric, v, region)
        try:
            result = self._anomaly.detect(series)
        except Exception as e:  # noqa: BLE001
            print(f"[DeviceHealth] anomaly 失败 metric={metric}: {e}")
            return 0.0, True
        # isolation forest / 3-sigma 的 score 可能是负值（越小越异常），统一映射到 0-100
        raw = float(result.get("score", 0.0))
        if raw <= 0:
            # v1.4.0：把 -raw 范围根据自适应 P95 缩放，避免硬编码系数 25
            local_p95 = self._normalizer.get_dynamic_max(metric, region, fallback=1.0)
            # raw 通常在 [-1, 0]；local_p95 在 1~30；用 local_p95 做尺度
            normalized = max(0.0, min(100.0, -raw * 100.0))
        else:
            normalized = max(0.0, min(100.0, raw))
        degraded = bool(result.get("mock", False))
        return normalized, degraded

    # ------------------------------------------------------------------ #
    # 内部：位移预测风险
    # ------------------------------------------------------------------ #
    def _forecast_risk(self, device_code: str, disp_series: List[float]) -> Tuple[float, Optional[str], bool]:
        """LSTM 预测未来 7 步位移最大值的"风险分"（0-100），返回 (forecast_risk, model_name, degraded)。"""
        if not disp_series or len(disp_series) < 5:
            return 0.0, None, True
        try:
            result = self._forecaster.predict(disp_series, horizon=7)
        except Exception as e:  # noqa: BLE001
            print(f"[DeviceHealth] forecast 失败: {e}")
            return 0.0, None, True
        predictions = result.get("predictions") or []
        if not predictions:
            return 0.0, result.get("model"), True
        last_pred = float(predictions[-1])
        # 超过 10mm 即视为满风险；区间线性放大到 0-100
        risk = max(0.0, min(100.0, (last_pred / self.FORECAST_DEVIATION_MM) * 100.0))
        degraded = bool(result.get("mock", False))
        return risk, result.get("model"), degraded

    # ------------------------------------------------------------------ #
    # 内部：reasoning + suggested actions（v1.4.0：增加位移加速度衍生归因）
    # ------------------------------------------------------------------ #
    @staticmethod
    def _build_reasoning_and_actions(
        worst_anomaly: float,
        anomaly_by_metric: Dict[str, float],
        forecast_risk: float,
        online_status: Optional[str],
        offline_age_min: Optional[float],
        covered: int,
        disp_derivative: Optional[float] = None,
    ) -> Tuple[List[str], List[str]]:
        reasoning: List[str] = []
        actions: List[str] = []

        if worst_anomaly > 50:
            reasoning.append(
                f"异常检测发现 {len(anomaly_by_metric)} 个指标偏离基线（最高分 {worst_anomaly:.1f}）"
            )
            actions.append("建议安排现场核查 + 设备校准")
        elif worst_anomaly > 20:
            reasoning.append(f"部分指标波动（最高分 {worst_anomaly:.1f}）")
            actions.append("建议加密观测频率至每 15 分钟一次")
        else:
            reasoning.append("所有指标在正常波动范围内")

        if forecast_risk > 50:
            # forecast_risk = (last_pred / 10mm) * 100，回推 mm 估算值
            approx_mm = forecast_risk / 100.0 * DeviceHealthScorer.FORECAST_DEVIATION_MM
            reasoning.append(f"未来 7 天位移预测 ≈ {approx_mm:.1f} mm，逼近告警阈值")
            actions.append("建议提前准备应急物资 + 居民会商")

        if online_status and online_status != "在线":
            reasoning.append("设备当前离线 / 上报超时")
            actions.append("联系现场运维排查电力 / 网络 / 传感器故障")

        if covered < 2:
            reasoning.append("参与评估的指标不足（< 2 条），建议核查设备接入完整性")

        # v1.4.0：位移加速度衍生特征归因
        if disp_derivative is not None and abs(disp_derivative) > 0.5:
            if disp_derivative > 0:
                reasoning.append(
                    f"位移加速度 {disp_derivative:.2f} mm/day²，位移正在加速（衍生特征异常）"
                )
                actions.append("建议现场排查位移加速成因（坡脚侵蚀 / 强降雨入渗 / 地下水位变化）")
            else:
                reasoning.append(f"位移加速度 {disp_derivative:.2f} mm/day²，位移趋缓")
                actions.append("建议保持当前监测节奏，关注后续是否反弹")

        if not actions:
            actions.append("保持当前观测节奏")
        return reasoning, actions

    # ------------------------------------------------------------------ #
    # 对外主接口（v1.4.0：接入特征工程 + 自适应归一化）
    # ------------------------------------------------------------------ #
    def score(
        self,
        device_code: str,
        metrics: Dict[str, List[float]],
        online_status: Optional[str] = None,
        last_report_at: Optional[str] = None,
    ) -> Dict[str, Any]:
        """
        Args:
            device_code: 设备编码
            metrics: {metric_name: [v_t, v_t-1, ...]}，每段时序应 ASC（最新值在末尾）
            online_status: 在线状态字符串（如 "在线" / "离线"），可选
            last_report_at: 最近一次上报时间（ISO 8601），可选
        Returns:
            完整健康评估：health_score/level/reasoning/actions/anomaly_by_metric/forecast/model/degraded/feature_contributions
        """
        # 1) 每条指标异常检测
        anomaly_by_metric: Dict[str, float] = {}
        worst_anomaly = 0.0
        any_degraded = False
        covered = 0
        for metric in HEALTH_METRICS:
            series = metrics.get(metric) or []
            if not series:
                continue
            covered += 1
            score, degraded = self._score_series(device_code, list(series), metric)
            anomaly_by_metric[metric] = _safe_round(score, 2)
            if score > worst_anomaly:
                worst_anomaly = score
            if degraded:
                any_degraded = True

        # 2) 位移预测风险（仅 disp_rate）
        forecast_risk, forecast_model, forecast_degraded = self._forecast_risk(
            device_code, list(metrics.get("disp_rate") or [])
        )
        if forecast_degraded:
            any_degraded = True

        # 3) v1.4.0：派生特征 —— 位移加速度
        disp_derivative = compute_disp_derivative(metrics.get("disp_rate"))
        # 把加速度也推入窗口（让模型能学到本地"正常加速度"基线）
        self._normalizer.fit_one(
            "_derived_disp_derivative", disp_derivative, region_code=device_code
        )

        # 3) 加权 health_score
        health = 100.0 - worst_anomaly * self.WEIGHT_ANOMALY - forecast_risk * self.WEIGHT_FORECAST
        now = datetime.now(timezone.utc)
        offline_age = _age_minutes(last_report_at, now) if last_report_at else None
        is_offline = online_status is not None and online_status != "在线"
        if is_offline:
            penalty = (
                self.OFFLINE_PENALTY_SEVERE
                if (offline_age is None or offline_age > 30)
                else self.OFFLINE_PENALTY_MILD
            )
            health -= penalty
        if covered < 2:
            health -= self.COVERAGE_PENALTY
        # v1.4.0：加速度异常时再加 5 分惩罚（>5 mm/day² 视为严重加速）
        if disp_derivative > 5.0:
            health -= 5.0
        health = max(0.0, min(100.0, health))
        health_level = _score_to_level(health)

        # 4) reasoning + actions（v1.4.0：传入 disp_derivative）
        reasoning, actions = self._build_reasoning_and_actions(
            worst_anomaly=worst_anomaly,
            anomaly_by_metric=anomaly_by_metric,
            forecast_risk=forecast_risk,
            online_status=online_status,
            offline_age_min=offline_age,
            covered=covered,
            disp_derivative=disp_derivative,
        )

        # 5) 置信度：covered 越多越可信
        confidence = min(0.95, 0.4 + 0.1 * covered + (0.2 if covered >= self.COVERAGE_TRUST_FULL else 0.0))
        confidence = _safe_round(confidence, 3)

        # 6) model 标签
        if forecast_model:
            model_tag = forecast_model
        elif any_degraded:
            model_tag = "heuristic-fallback+adaptive-norm"
        else:
            model_tag = "ai-anomaly+forecast+adaptive-norm"

        # 7) 异常时间戳提示（按调用方字段约定传 ISO 字符串回写）
        last_anomaly_at = now.isoformat() if worst_anomaly > 30 else None

        # 8) v1.4.0：构造 SHAP-like 特征贡献度（简单按异常分占比分配）
        contributions = []
        if worst_anomaly > 0:
            for metric_name, mscore in anomaly_by_metric.items():
                ratio = (mscore / worst_anomaly) if worst_anomaly > 0 else 0.0
                contributions.append({
                    "feature": metric_name,
                    "raw_value": mscore,
                    "norm_value": mscore / 100.0,
                    "weight": self.WEIGHT_ANOMALY,
                    "contribution": round(mscore * self.WEIGHT_ANOMALY, 2),
                    "ratio": round(ratio, 3),
                })
            contributions.append({
                "feature": "disp_forecast_risk",
                "raw_value": forecast_risk,
                "norm_value": forecast_risk / 100.0,
                "weight": self.WEIGHT_FORECAST,
                "contribution": round(forecast_risk * self.WEIGHT_FORECAST, 2),
                "ratio": round(forecast_risk / (worst_anomaly + forecast_risk), 3)
                if (worst_anomaly + forecast_risk) > 0 else 0.0,
            })
            if disp_derivative > 0.5:
                contributions.append({
                    "feature": "_derived_disp_derivative",
                    "raw_value": round(disp_derivative, 4),
                    "norm_value": min(1.0, abs(disp_derivative) / 10.0),
                    "weight": 0.05,
                    "contribution": round(5.0 if disp_derivative > 5.0 else abs(disp_derivative) * 0.5, 2),
                    "ratio": round(0.1, 3),
                })
            contributions.sort(key=lambda x: x["contribution"], reverse=True)

        return {
            "device_code": device_code,
            "health_score": _safe_round(health, 2),
            "health_level": health_level,
            "anomaly_score": _safe_round(worst_anomaly, 2),
            "forecast_risk": _safe_round(forecast_risk, 2),
            "metric_coverage": covered,
            "anomaly_by_metric": anomaly_by_metric,
            "feature_contributions": contributions,  # v1.4.0
            "disp_derivative": _safe_round(disp_derivative, 4),  # v1.4.0
            "reasoning": reasoning,
            "suggested_actions": actions,
            "confidence": confidence,
            "model": model_tag,
            "degraded": any_degraded,
            "analyzed_at": now.isoformat(),
            "last_anomaly_at": last_anomaly_at,
        }


_scorer: Optional[DeviceHealthScorer] = None


def get_device_health_scorer() -> DeviceHealthScorer:
    """单例获取评分器（确保 anomaly / forecaster 内部状态连续）。"""
    global _scorer
    if _scorer is None:
        _scorer = DeviceHealthScorer()
    return _scorer
