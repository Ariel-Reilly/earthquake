"""
地质灾害 AI 评估模型 v3.0 —— 多源数据融合体系。

多源数据融合体系
=================
AI 智能巡检监测自然灾害需要多源数据融合，本系统整合以下六类核心数据：

一、核心遥感影像数据（用于识别地表灾害特征）
------------------------------------------------
| 数据名称              | 传感器/卫星      | 空间分辨率         | 时间分辨率    | 用途                                           | 推荐接入方式                         |
|---------------------|----------------|-------------------|-------------|----------------------------------------------|-------------------------------------|
| Sentinel-1 SAR      | C波段雷达        | 10 m（GRD）       | 6天（双星）   | 洪水、滑坡、地表形变（InSAR）、地震同震位移     | Copernicus Data Space API / GEE     |
| Sentinel-2 多光谱   | MSI            | 10/20/60 m        | 5天（双星）   | 植被指数、水体提取、火灾迹地、干旱监测         | Copernicus Data Space / Sentinel Hub |
| Landsat 8/9        | OLI/TIRS       | 15/30/100 m       | 16天        | 长期土地覆盖变化、历史对比                      | USGS EarthExplorer / GEE            |
| MODIS / VIIRS       | 中分辨率        | 250/500/1000 m    | 每日多次      | 大范围火点、气溶胶、洪水快速初判               | NASA GIBS / FIRMS API / GEE        |
| 高分辨率商业（可选）| Maxar/Planet   | <1 m              | 按需         | 建筑物损毁评估、精细滑坡边界                    | Planet API（学生可申请试用）         |

二、气象与水文数据（用于触发条件分析和预警）
------------------------------------------------
| 数据名称              | 内容                       | 时空分辨率         | 用途                               | 推荐接入来源                         |
|---------------------|--------------------------|------------------|-----------------------------------|-------------------------------------|
| ERA5 再分析          | 降雨、温度、风速、湿度、气压等 | 0.25° 逐小时      | 降雨阈值分析、干旱指数、台风路径环境场 | ECMWF CDS API（免费注册）            |
| GPM IMERG           | 全球卫星降雨               | 0.1° 每30分钟     | 实时降雨监测、滑坡/洪水降雨触发       | NASA GES DISC / EarthData API       |
| SMAP 土壤湿度        | 雷达/辐射计土壤水分         | 9 km / 3 km      | 干旱监测、滑坡前期土壤饱和度          | NSIDC / EarthData                   |
| 河流水位（可选）     | 水文站实测                 | 站点级            | 洪水演进验证                        | 国家水文部门公开数据或 GRDC          |

三、地形与地质数据（用于易发性评估和特征提取）
------------------------------------------------
| 数据名称              | 内容              | 分辨率            | 用途                     | 推荐接入来源                         |
|---------------------|-----------------|-----------------|--------------------------|-------------------------------------|
| SRTM DEM            | 全球数字高程模型    | 30 m            | 坡度、坡向、汇流分析、地形湿度指数 | NASA EarthData / GEE                |
| Copernicus DEM      | 全球DEM           | 30 m（部分地区10m）| 更高精度地形分析              | Copernicus Data Space（需注册）      |
| 地质图/断层数据       | 岩性、构造         | 矢量             | 地震、滑坡易发性因子             | USGS / 各国地质调查局                |
| 土壤类型             | 土壤物理属性        | 矢量/栅格         | 渗透性、滑坡敏感性              | FAO HWSD / ISRIC SoilGrids         |

四、历史灾害与承灾体数据（用于模型训练和风险评估）
------------------------------------------------
| 数据名称              | 内容                           | 用途                       | 推荐接入来源                         |
|---------------------|------------------------------|---------------------------|-------------------------------------|
| EM-DAT              | 全球灾害事件库（类型、时间、地点、损失）| 模型训练标签、风险区划            | EM-DAT 官网下载 CSV/API             |
| GDACS               | 全球灾害警报和影响评估（地震/洪水/台风）| 实时灾害事件信息，用于触发分析     | GDACS RSS/API                       |
| Copernicus EMS      | 重大灾害快速测绘产品（洪水/火灾范围）   | 高精度验证数据                  | Copernicus EMS 网站下载             |
| WorldPop / GHS      | 人口密度分布                    | 风险评估、影响人口估算            | WorldPop API / GHSL                |
| OpenStreetMap       | 建筑物、道路、土地利用              | 承灾体暴露度、基础设施损毁评估      | OSM API / Overpass API            |

五、地面传感器与实时观测数据（用于快速预警和验证）
------------------------------------------------
| 数据名称              | 内容                       | 用途                               | 推荐接入方式                         |
|---------------------|--------------------------|-----------------------------------|-------------------------------------|
| USGS 地震目录        | 实时地震事件（震级、位置、深度）  | 地震触发报警，SAR 形变分析触发        | USGS FDSN Event Web Service API   |
| 全球地震台网（可选）   | 地震波形（加速度）             | 强震动预警                        | IRIS DMC Web Services               |
| 现场 IoT 传感器（可选）| 雨量计、位移计、倾斜仪、水位计   | 高时间分辨率前兆监测               | 自建或第三方 IoT 平台（如 ThingsBoard）|

六、辅助数据（可选，用于增强分析或可视化）
------------------------------------------------
| 数据名称              | 内容            | 用途                     | 推荐接入来源                         |
|---------------------|----------------|--------------------------|-------------------------------------|
| NASA Black Marble   | 夜间灯光         | 断电范围评估、灾后影响          | NASA LAADS / GEE                   |
| 社交媒体 API         | 微博/Twitter/X 帖文 | 灾情现场文字/图片，验证模型     | 微博开放平台 / Twitter API v2       |
| 行政区划矢量          | 国/省/市边界      | 结果分区统计                | Natural Earth / GADM               |

本模块对输入的 factors 字典按六类数据源分别评分，最终输出：
  - 总体 risk_score / risk_level / concern_label / model
  - 六维度各自 sub_score + level + details
  - SHAP-like 特征贡献度（每条带 contribution）
  - reasoning 数组：可解释的风险归因（每条带维度标签）
  - suggested_actions 数组：可执行建议

支持方法：
  - 完整六维度综合评估（`score` 主方法）
  - 六大灾害类型分类（滑坡/崩塌/泥石流/地裂缝/地面沉降/塌陷）
  - SHAP-like 特征贡献度
  - 历史灾情承灾体评估
  - 社交媒体舆情分析
  - 夜间灯光/基础设施影响评估

因子 schema（v3.0）
====================

  [1] 遥感影像（Remote Sensing）—— 空间基础
      image_anomaly_score   0-100   YOLO/卫星分析异常分数
      image_anomaly_count    int     检测到的异常目标数
      image_source          str     sentinel1 / sentinel2 / landsat / modis / radar / drone / camera

  [2] 气象水文（Meteorological & Hydrological）—— 触发条件
      rainfall_24h / rainfall_7d / rainfall_30d    mm
      temperature            °C
      humidity              0-1
      soil_moisture          0-1    土壤含水率
      water_level           m       河流水位

  [3] 地形地质（Terrain & Geology）—— 易发性判断
      elevation_m / slope_deg / aspect_deg / curvature
      soil_grade (1-5) / vegetation_cover (0-1)
      distance_to_water_m / geology_grade (1-5)
      geology_label / fault_distance_m

  [4] 地面传感器（Ground Sensors）—— 近实时动态
      displacement_rate / displacement_total / crack_width
      earthquake_mag / tilt_angle / site_grade (1-5)

  [5] 历史灾情与承灾体（Historical Disasters & Exposure）—— 训练验证
      historical_count / historical_max_score / exposure_population
      exposure_buildings / land_use_type

  [6] 辅助数据（Supporting Data）—— 可选增强
      social_alert_level (0-4) / social_post_count
      social_sentiment_score (-1~1) / social_emergency_level (0-4)
      nighttime_light_index (0-63) / infrastructure_score (0-1)
      road_access_score (0-1) / power_status (0-1)

v3.0 升级（多源数据融合完善）：
  - 新增完整六类数据源详细说明表格
  - 遥感数据源扩展：Sentinel-1 SAR、MODIS/VIIRS、高分辨率商业卫星
  - 气象水文新增 ERA5、GPM IMERG、SMAP 土壤湿度、河流水位
  - 地形地质新增 Copernicus DEM、土壤类型数据
  - 历史灾情新增 EM-DAT、GDACS、Copernicus EMS、WorldPop
  - 实时传感器新增 USGS 地震目录、全球地震台网
  - 辅助数据新增 NASA Black Marble、行政区划矢量
"""
from __future__ import annotations

import math
from typing import Any, Dict, List, Optional, Tuple

from app.models.xgboost_model import get_fusion_model
from app.models.feature_engineering import compute_derived_features
from app.models.adaptive_normalizer import get_adaptive_normalizer

# 风险等级阈值（与其它 AI 模型一致）
LEVEL_THRESHOLDS = (
    (80.0, "CRITICAL"),
    (60.0, "HIGH"),
    (35.0, "MEDIUM"),
    (0.0, "LOW"),
)

# 中文业务等级（关注度）
CONCERN_LABEL_MAP: Dict[str, str] = {
    "LOW": "稳定",
    "MEDIUM": "关注",
    "HIGH": "预警",
    "CRITICAL": "异常严重",
}

# 六大主要地质灾害类型（对应 hazard_point.hazard_type 字典）
DISASTER_TYPE_LANDSLIDE = "滑坡"
DISASTER_TYPE_COLLAPSE = "崩塌"
DISASTER_TYPE_DEBRIS_FLOW = "泥石流"
DISASTER_TYPE_GROUND_CRACK = "地裂缝"
DISASTER_TYPE_SUBSIDENCE = "地面沉降"
DISASTER_TYPE_SINKHOLE = "塌陷"
DISASTER_TYPE_UNKNOWN = "其他"
DISASTER_TYPE_UNDETERMINED = "待定"  # v1.4.0 新增：分类置信度不足时返回

SUPPORTED_DISASTER_TYPES: List[str] = [
    DISASTER_TYPE_LANDSLIDE,
    DISASTER_TYPE_COLLAPSE,
    DISASTER_TYPE_DEBRIS_FLOW,
    DISASTER_TYPE_GROUND_CRACK,
    DISASTER_TYPE_SUBSIDENCE,
    DISASTER_TYPE_SINKHOLE,
]


# 归一化参考（保留做 fallback；正常路径用 adaptive_normalizer）
ELEVATION_NORM_MAX = 3000.0
SLOPE_NORM_MAX = 60.0
SOIL_NORM_MAX = 5.0
VEGETATION_NORM_MAX = 1.0
DISTANCE_NORM_MAX = 5000.0
GEOLOGY_NORM_MAX = 5.0
RAIN_24H_NORM_MAX = 150.0
RAIN_7D_NORM_MAX = 400.0
RAIN_30D_NORM_MAX = 800.0
SOCIAL_ALERT_NORM_MAX = 4.0
NIGHTLIGHT_NORM_MAX = 63.0
FAULT_DISTANCE_NORM_MAX = 10000.0

# v2.0 七维度基础权重（与 comprehensive_model_v2 保持一致）
# 设备缺失时自动把 device 的权重重新归一化到其余六类
BASE_WEIGHTS_V2: Dict[str, float] = {
    "remote_sensing": 0.20,   # 遥感影像
    "meteorology":    0.22,   # 气象水文
    "terrain":        0.15,   # 地形地质
    "sensor":         0.18,   # 地面传感器
    "historical":      0.10,   # 历史灾情
    "social_media":   0.08,   # 社交媒体
    "supporting":     0.07,   # 辅助数据
}

# 兼容旧版 v1.x（仅 geographic + precipitation + device）
BASE_WEIGHTS: Dict[str, float] = {
    "geographic": 0.50,
    "precipitation": 0.40,
    "device": 0.10,
}

# 灾害评估模型专属的"真·设备维度"白名单（只包括设备物理指标，不混入 rainfall_*）
DEVICE_FACTORS: List[str] = [
    "displacement_rate",
    "displacement_total",
    "crack_width",
    "earthquake_mag",
    "site_grade",
]


def score_to_level(score: float) -> str:
    for threshold, level in LEVEL_THRESHOLDS:
        if score >= threshold:
            return level
    return "LOW"


def concern_label(level: str) -> str:
    return CONCERN_LABEL_MAP.get(level, "未知")


def safe_norm(value: float, max_val: float) -> float:
    if value is None or not math.isfinite(float(value)):
        return 0.0
    if max_val <= 0:
        return 0.0
    return max(0.0, min(1.0, float(value) / max_val))


def has_device_factors(factors: Dict[str, float]) -> bool:
    """判断用户是否提供了设备维度因子（仅看 DEVICE_FACTORS，不混入 rainfall_*）。"""
    for k in DEVICE_FACTORS:
        v = factors.get(k)
        if v is not None and abs(float(v)) > 1e-9:
            return True
    return False


class DisasterAssessmentModel:
    """地质灾害 AI 评估：地理 + 天气 + 可选设备 → 等级 + 关注度 + 灾害类型 + 解释。"""

    # ---------- 灾害类型多因子加权评分配置（v1.4.0 升级） ---------- #
    # 每个类型 = Σ(归一化特征值 × 类型专属权重)；
    # 权重越大说明该特征对此类型越重要。
    TYPE_FEATURES: Dict[str, Dict[str, float]] = {
        DISASTER_TYPE_LANDSLIDE: {
            "_derived_rain_saturation_index": 0.30,  # 长期雨+易蚀土壤+缺植被
            "_derived_foot_erosion_index": 0.25,      # 陡坡近水
            "slope_deg": 0.20,                        # 中陡坡 25-40°
            "geology_grade": 0.10,
            "rainfall_24h": 0.10,
            "_derived_disp_derivative": 0.05,
        },
        DISASTER_TYPE_COLLAPSE: {
            "slope_deg": 0.40,                        # 极陡 ≥ 40°
            "geology_grade": 0.25,                    # 破碎岩体
            "_derived_foot_erosion_index": 0.15,
            "rainfall_24h": 0.10,
            "_derived_rain_intensity_ratio": 0.10,
        },
        DISASTER_TYPE_DEBRIS_FLOW: {
            "_derived_rain_intensity_ratio": 0.30,    # 骤雨型
            "_derived_rain_saturation_index": 0.20,   # 长期雨
            "distance_to_water_m": 0.20,              # 近沟
            "vegetation_cover": 0.15,                 # 缺植被
            "slope_deg": 0.15,                        # 中等坡度
        },
        DISASTER_TYPE_GROUND_CRACK: {
            "_derived_disp_derivative": 0.30,         # 位移加速
            "crack_width": 0.25,
            "displacement_rate": 0.20,
            "_derived_rain_saturation_index": 0.15,
            "slope_deg": 0.10,
        },
        DISASTER_TYPE_SUBSIDENCE: {
            "soil_grade": 0.35,                       # 高压缩土
            "_derived_geology_distance_score": 0.25,  # 脆弱地质 + 地下水
            "vegetation_cover": 0.15,                 # 稀疏
            "_derived_rain_saturation_index": 0.15,
            "_derived_disp_derivative": 0.10,
        },
        DISASTER_TYPE_SINKHOLE: {
            "_derived_geology_distance_score": 0.35,  # 岩溶地质 + 地下水
            "geology_grade": 0.25,
            "rainfall_24h": 0.20,                     # 极端降雨触发
            "distance_to_water_m": 0.10,
            "_derived_rain_intensity_ratio": 0.10,
        },
    }

    # 分类置信度阈值
    TYPE_SCORE_THRESHOLD = 0.18       # 低于此分认为类型特征不足
    TYPE_LEAD_MARGIN = 0.05           # 领先第二名不足此分数认为"待定"

    def __init__(self) -> None:
        # 复用 fusion 模型用于 device 维度加权（不强制依赖）
        self._fusion = get_fusion_model()
        # 自适应归一化器（单例）
        self._normalizer = get_adaptive_normalizer()

    # ------------------------------------------------------------------ #
    # 自适应归一化 helper
    # ------------------------------------------------------------------ #
    def _dynamic_norm(self, factor_key: str, region_code: str, fallback: float) -> float:
        """从 adaptive_normalizer 取动态上限；样本不足时回退 fallback。"""
        try:
            return self._normalizer.get_dynamic_max(factor_key, region_code, fallback=fallback)
        except Exception:
            return fallback

    # ------------------------------------------------------------------ #
    # 单维度评分（v1.4.0：接入自适应归一化）
    # ------------------------------------------------------------------ #
    def score_geographic(
        self, factors: Dict[str, float], region_code: str = "_default"
    ) -> Dict[str, Any]:
        elevation = factors.get("elevation_m", 0.0)
        slope = float(factors.get("slope_deg", 0.0))
        soil = float(factors.get("soil_grade", 1.0))
        vegetation = float(factors.get("vegetation_cover", 0.5))
        distance = float(factors.get("distance_to_water_m", 1000.0))
        geology = float(factors.get("geology_grade", 1.0))

        # 优先用自适应上限
        norm_elevation = safe_norm(elevation, self._dynamic_norm("elevation_m", region_code, ELEVATION_NORM_MAX))
        norm_slope = safe_norm(slope, self._dynamic_norm("slope_deg", region_code, SLOPE_NORM_MAX))
        norm_soil = safe_norm(soil, self._dynamic_norm("soil_grade", region_code, SOIL_NORM_MAX))
        norm_vegetation = 1.0 - safe_norm(vegetation, self._dynamic_norm("vegetation_cover", region_code, VEGETATION_NORM_MAX))
        norm_distance = max(0.0, 1.0 - safe_norm(distance, self._dynamic_norm("distance_to_water_m", region_code, DISTANCE_NORM_MAX)))
        norm_geology = safe_norm(geology, self._dynamic_norm("geology_grade", region_code, GEOLOGY_NORM_MAX))

        combined = (
            norm_slope * 0.25
            + norm_soil * 0.20
            + norm_distance * 0.20
            + norm_geology * 0.15
            + norm_vegetation * 0.10
            + norm_elevation * 0.10
        )
        score = round(combined * 100.0, 2)
        return {
            "score": score,
            "level": score_to_level(score),
            "elevation_m": float(elevation),
            "slope_deg": slope,
            "soil_grade": soil,
            "vegetation_cover": vegetation,
            "distance_to_water_m": float(distance),
            "geology_grade": geology,
            "geology_label": factors.get("geology_label", "未指定"),
            "details": (
                f"海拔 {elevation:.0f} m · 坡度 {slope:.1f}° · 土壤易蚀性 {soil:.1f}/5 · "
                f"植被覆盖 {vegetation:.0%} · 距水体 {distance:.0f} m · 地质 {factors.get('geology_label', '未指定')}"
            ),
        }

    def score_precipitation(
        self, factors: Dict[str, float], region_code: str = "_default"
    ) -> Dict[str, Any]:
        rain_24h = max(0.0, float(factors.get("rainfall_24h", 0.0)))
        rain_7d = max(0.0, float(factors.get("rainfall_7d", 0.0)))

        # 自适应归一化
        norm_24h = safe_norm(rain_24h, self._dynamic_norm("rainfall_24h", region_code, RAIN_24H_NORM_MAX))
        norm_7d = safe_norm(rain_7d, self._dynamic_norm("rainfall_7d", region_code, RAIN_7D_NORM_MAX))

        combined = 0.6 * norm_24h + 0.4 * norm_7d
        score = round(combined * 100.0, 2)

        if rain_24h >= 100 or rain_7d >= 250:
            trend, note = "极端", "已超过地质灾害极端降雨阈值"
        elif rain_24h >= 50 or rain_7d >= 150:
            trend, note = "高", "接近/超过地质灾害高风险降雨阈值"
        elif rain_24h >= 25 or rain_7d >= 80:
            trend, note = "中", "累计降雨达需关注水平"
        else:
            trend, note = "平稳", "降雨平稳，未触发阈值"

        return {
            "score": score,
            "level": score_to_level(score),
            "rainfall_24h": round(rain_24h, 2),
            "rainfall_7d": round(rain_7d, 2),
            "trend": trend,
            "details": (
                f"24h 累计降雨 {rain_24h:.1f} mm，7d 累计 {rain_7d:.1f} mm，"
                f"趋势判定：{trend}（{note}）"
            ),
        }

    def score_device(self, factors: Dict[str, float]) -> Tuple[float, float]:
        """设备维度（可选；缺失时返回 0）。复用 fusion 模型加权逻辑——但输入限定到 DEVICE_FACTORS。"""
        sub = {k: factors.get(k, 0.0) for k in DEVICE_FACTORS}
        # fusion._run_weighted 使用它自己的 DEFAULT_WEIGHTS 来归一化，对缺失的因子视为 0 即可
        result = self._fusion._run_weighted(sub)  # noqa: SLF001
        return float(result["risk_score"]), float(result["confidence"])

    # =========================== v2.0 新增维度 =========================== #

    def score_remote_sensing(self, factors: Dict[str, float]) -> Dict[str, Any]:
        """[1] 遥感影像维度：光学/雷达/热红外/无人机图像异常评分。"""
        raw = max(0.0, min(100.0, float(factors.get("image_anomaly_score", 0.0))))
        count = int(factors.get("image_anomaly_count", 0))
        source = factors.get("image_source", "unknown")
        confidence = float(factors.get("image_confidence", 0.5))
        if raw <= 0:
            detail = "暂无遥感影像异常记录"
        elif raw <= 30:
            detail = f"遥感异常分数 {raw:.1f}，影像地表状态基本稳定"
        elif raw <= 60:
            detail = f"遥感异常分数 {raw:.1f}，发现 {count} 处疑似异常，建议加密复核"
        elif raw <= 80:
            detail = f"遥感异常分数 {raw:.1f}，{count} 处疑似裂缝/形变，需现场核查"
        else:
            detail = f"遥感异常分数 {raw:.1f}，{count} 处显著异常，建议立即现场核查"
        src_label = {
            "sentinel2": "Sentinel-2", "sentinel": "Sentinel-2",
            "landsat": "Landsat", "radar": "雷达SAR", "sar": "雷达SAR",
            "drone": "无人机", "camera": "地面相机", "thermal": "热红外",
        }.get(str(source).lower(), str(source))
        return {
            "score": round(raw, 2),
            "level": score_to_level(raw),
            "source": source,
            "source_label": src_label,
            "anomaly_count": count,
            "confidence": round(confidence, 4),
            "details": detail,
        }

    def score_meteorology_extended(self, factors: Dict[str, float], region_code: str = "_default") -> Dict[str, Any]:
        """[2] 气象水文扩展维度：降雨+温度+湿度+土壤含水率+水位。"""
        rain_24h = max(0.0, float(factors.get("rainfall_24h", 0.0)))
        rain_7d  = max(0.0, float(factors.get("rainfall_7d", 0.0)))
        rain_30d = max(0.0, float(factors.get("rainfall_30d", 0.0)))
        temp = float(factors.get("temperature", 20.0))
        humidity = float(factors.get("humidity", 0.5))
        soil_moisture = float(factors.get("soil_moisture", 0.3))
        water_level = float(factors.get("water_level", 0.0))

        norm_24h = safe_norm(rain_24h, self._dynamic_norm("rainfall_24h", region_code, RAIN_24H_NORM_MAX))
        norm_7d  = safe_norm(rain_7d,  self._dynamic_norm("rainfall_7d", region_code, RAIN_7D_NORM_MAX))
        norm_30d = safe_norm(rain_30d, self._dynamic_norm("rainfall_30d", region_code, RAIN_30D_NORM_MAX))
        rain_combined = 0.5 * norm_24h + 0.3 * norm_7d + 0.2 * norm_30d
        soil_risk = 0.3 if soil_moisture >= 0.45 else (0.1 if soil_moisture >= 0.35 else 0.0)
        water_risk = min(0.3, water_level / max(0.01, float(factors.get("water_level_warning", 1.0))) * 0.3) if water_level > 0 else 0.0
        score = round((rain_combined * 0.7 + soil_risk * 0.15 + water_risk * 0.15) * 100.0, 2)
        if rain_24h >= 100 or rain_7d >= 250:
            trend, note = "极端", "超过地质灾害极端降雨阈值"
        elif rain_24h >= 50 or rain_7d >= 150:
            trend, note = "高", "接近高风险降雨阈值"
        elif rain_24h >= 25 or rain_7d >= 80:
            trend, note = "中", "累计降雨达需关注水平"
        else:
            trend, note = "平稳", "降雨平稳，未触发阈值"
        detail = f"24h {rain_24h:.1f}mm · 7d {rain_7d:.1f}mm · 30d {rain_30d:.1f}mm；{trend}（{note}）"
        if soil_moisture >= 0.45:
            detail += f"；土壤含水率{soil_moisture:.0%}（接近饱和）"
        return {
            "score": score,
            "level": score_to_level(score),
            "rainfall_24h": round(rain_24h, 2),
            "rainfall_7d": round(rain_7d, 2),
            "rainfall_30d": round(rain_30d, 2),
            "temperature": round(temp, 1),
            "humidity": round(humidity, 3),
            "soil_moisture": round(soil_moisture, 3),
            "water_level": round(water_level, 2),
            "rain_trend": trend,
            "details": detail,
        }

    def score_terrain_extended(self, factors: Dict[str, float], region_code: str = "_default") -> Dict[str, Any]:
        """[3] 地形地质扩展维度：海拔/坡度/坡向/曲率/土壤/植被/距水/地质/断层。"""
        elevation = float(factors.get("elevation_m", 0.0))
        slope     = float(factors.get("slope_deg", 0.0))
        aspect    = float(factors.get("aspect_deg", 180.0))
        curvature = float(factors.get("curvature", 0.0))
        soil      = float(factors.get("soil_grade", 1.0))
        vegetation= float(factors.get("vegetation_cover", 0.5))
        distance  = float(factors.get("distance_to_water_m", 1000.0))
        geology   = float(factors.get("geology_grade", 1.0))
        fault_dist= float(factors.get("fault_distance_m", 99999.0))
        geo_label = str(factors.get("geology_label", "未指定"))

        norm_slope     = safe_norm(slope, self._dynamic_norm("slope_deg", region_code, SLOPE_NORM_MAX))
        norm_soil      = safe_norm(soil, self._dynamic_norm("soil_grade", region_code, SOIL_NORM_MAX))
        norm_distance  = max(0.0, 1.0 - safe_norm(distance, self._dynamic_norm("distance_to_water_m", region_code, DISTANCE_NORM_MAX)))
        norm_geology   = safe_norm(geology, self._dynamic_norm("geology_grade", region_code, GEOLOGY_NORM_MAX))
        norm_vegetation= 1.0 - safe_norm(vegetation, self._dynamic_norm("vegetation_cover", region_code, VEGETATION_NORM_MAX))
        norm_fault     = max(0.0, 1.0 - safe_norm(fault_dist, FAULT_DISTANCE_NORM_MAX))
        curvature_risk = max(0.0, -curvature * 0.1)
        aspect_risk = 0.05 if (0 <= aspect < 90 or 270 <= aspect <= 360) else 0.0

        combined = (
            norm_slope * 0.22 + norm_soil * 0.18 + norm_distance * 0.18
            + norm_geology * 0.14 + norm_vegetation * 0.10 + norm_fault * 0.10
            + curvature_risk + aspect_risk
        )
        score = round(combined * 100.0, 2)
        flags = []
        if slope >= 30: flags.append(f"坡度{slope:.1f}°")
        if distance <= 300: flags.append(f"距水体{distance:.0f}m")
        if vegetation <= 0.30: flags.append(f"植被{vegetation:.0%}")
        detail = f"海拔{elevation:.0f}m · 坡度{slope:.1f}° · 土壤{soil:.1f}/5 · 植被{vegetation:.0%} · 距水体{distance:.0f}m · {geo_label}"
        if flags: detail += "；" + "；".join(flags)
        return {
            "score": score,
            "level": score_to_level(score),
            "elevation_m": round(elevation, 1),
            "slope_deg": round(slope, 1),
            "aspect_deg": round(aspect, 1),
            "curvature": round(curvature, 4),
            "soil_grade": round(soil, 1),
            "vegetation_cover": round(vegetation, 3),
            "distance_to_water_m": round(distance, 1),
            "geology_grade": round(geology, 1),
            "geology_label": geo_label,
            "fault_distance_m": round(fault_dist, 1),
            "details": detail,
        }

    def score_historical(self, factors: Dict[str, float]) -> Dict[str, Any]:
        """[5] 历史灾情与承灾体维度。"""
        hist_count = int(factors.get("historical_count", 0))
        hist_max   = max(0.0, float(factors.get("historical_max_score", 0.0)))
        pop        = int(factors.get("exposure_population", 0))
        buildings  = int(factors.get("exposure_buildings", 0))
        land_use   = str(factors.get("land_use_type", "unknown"))
        hist_score = min(100.0, hist_max + hist_count * 8.0)
        exposure_factor = min(1.0, (pop / 10000.0 + buildings / 1000.0) * 0.5)
        land_risk_map = {"urban": 0.3, "cropland": 0.5, "forest": 0.15, "water": 0.6, "barren": 0.1, "unknown": 0.2}
        land_risk = land_risk_map.get(land_use, 0.2)
        score = round((hist_score * 0.5 + exposure_factor * 30.0 + land_risk * 20.0), 2)
        land_label = {"urban": "城镇区", "cropland": "农业区", "forest": "林地区", "water": "水域", "barren": "荒漠", "unknown": "未分类"}.get(land_use, "未分类")
        detail = f"历史事件{hist_count}次 · 历史最大评分{hist_max:.0f} · {land_label} · 承灾人口{pop}人"
        return {
            "score": score,
            "level": score_to_level(score),
            "historical_count": hist_count,
            "historical_max_score": round(hist_max, 1),
            "exposure_population": pop,
            "exposure_buildings": buildings,
            "land_use_type": land_use,
            "land_use_label": land_label,
            "details": detail,
        }

    def score_social_media(self, factors: Dict[str, float]) -> Dict[str, Any]:
        """[6] 社交媒体与文本维度。"""
        alert_level = max(0.0, min(4.0, float(factors.get("social_alert_level", 0.0))))
        post_count  = int(factors.get("social_post_count", 0))
        sentiment   = max(-1.0, min(1.0, float(factors.get("social_sentiment_score", 0.0))))
        emergency   = max(0.0, min(4.0, float(factors.get("social_emergency_level", 0.0))))
        norm_alert  = safe_norm(alert_level, SOCIAL_ALERT_NORM_MAX)
        post_factor = min(0.3, post_count / 50.0)
        sentiment_factor = max(0.0, -sentiment * 0.3)
        emergency_factor = safe_norm(emergency, SOCIAL_ALERT_NORM_MAX) * 0.3
        score = round((norm_alert * 0.6 + post_factor + sentiment_factor + emergency_factor) * 100.0, 2)
        alert_label = {0: "无", 1: "低", 2: "中", 3: "高", 4: "紧急"}.get(int(alert_level), "未知")
        sent_label = "恐慌" if sentiment < -0.3 else "正常" if sentiment > 0.3 else "中性"
        detail = f"舆情告警{alert_label}({alert_level:.0f}/4) · 帖文{post_count}条 · 情感{sent_label}"
        return {
            "score": score,
            "level": score_to_level(score),
            "social_alert_level": int(alert_level),
            "social_alert_label": alert_label,
            "post_count": post_count,
            "sentiment_score": round(sentiment, 3),
            "sentiment_label": sent_label,
            "emergency_level": int(emergency),
            "details": detail,
        }

    def score_supporting(self, factors: Dict[str, float]) -> Dict[str, Any]:
        """[7] 辅助数据维度：夜间灯光/基础设施/道路/电力。"""
        ntl   = max(0.0, min(63.0, float(factors.get("nighttime_light_index", 31.0))))
        infra = max(0.0, min(1.0, float(factors.get("infrastructure_score", 0.5))))
        road  = max(0.0, min(1.0, float(factors.get("road_access_score", 0.5))))
        power = max(0.0, min(1.0, float(factors.get("power_status", 1.0))))
        access_score = road * 0.6 + infra * 0.4
        score = round((max(0.0, 1.0 - access_score) * 0.7 + max(0.0, 1.0 - power) * 0.2 + safe_norm(ntl, NIGHTLIGHT_NORM_MAX) * 0.1) * 100.0, 2)
        power_label = "正常" if power > 0.7 else "不稳定" if power > 0.3 else "断电"
        detail = f"夜间灯光{ntl:.0f}/63 · 路网可达{road:.0%} · 基础设施{int(infra*100)}% · 电力{power_label}"
        return {
            "score": score,
            "level": score_to_level(score),
            "nighttime_light_index": round(ntl, 1),
            "infrastructure_score": round(infra, 3),
            "road_access_score": round(road, 3),
            "power_status": round(power, 3),
            "power_label": power_label,
            "details": detail,
        }

    # ------------------------------------------------------------------ #
    # SHAP-like 特征贡献度（v1.4.0 新增）
    # ------------------------------------------------------------------ #
    def compute_feature_contributions(
        self,
        factors: Dict[str, Any],
        region_code: str = "_default",
    ) -> List[Dict[str, Any]]:
        """
        计算每个因子对总分的贡献度（SHAP-like 单调解释）。

        思路：每个因子归一化后 × 维度内权重 = 贡献值（points out of 100）。
        衍生特征也参与贡献计算（因为它们更"业务可解释"）。

        Returns:
            [{"feature": str, "value": float, "norm_value": float,
              "weight": float, "contribution": float}, ...]
            按 contribution 降序。
        """
        contributions: List[Tuple[str, float, float, float]] = []

        # geographic 维度贡献
        elev = float(factors.get("elevation_m", 0.0))
        slope = float(factors.get("slope_deg", 0.0))
        soil = float(factors.get("soil_grade", 1.0))
        veg = float(factors.get("vegetation_cover", 0.5))
        dist = float(factors.get("distance_to_water_m", 5000.0))
        geo = float(factors.get("geology_grade", 1.0))

        norm_elev = safe_norm(elev, self._dynamic_norm("elevation_m", region_code, ELEVATION_NORM_MAX))
        norm_slope = safe_norm(slope, self._dynamic_norm("slope_deg", region_code, SLOPE_NORM_MAX))
        norm_soil = safe_norm(soil, self._dynamic_norm("soil_grade", region_code, SOIL_NORM_MAX))
        norm_veg = 1.0 - safe_norm(veg, self._dynamic_norm("vegetation_cover", region_code, VEGETATION_NORM_MAX))
        norm_dist = 1.0 - safe_norm(dist, self._dynamic_norm("distance_to_water_m", region_code, DISTANCE_NORM_MAX))
        norm_geo = safe_norm(geo, self._dynamic_norm("geology_grade", region_code, GEOLOGY_NORM_MAX))

        contributions.extend([
            ("elevation_m", elev, norm_elev * 100.0 * 0.10, 0.10),
            ("slope_deg", slope, norm_slope * 100.0 * 0.25, 0.25),
            ("soil_grade", soil, norm_soil * 100.0 * 0.20, 0.20),
            ("vegetation_cover", veg, norm_veg * 100.0 * 0.10, 0.10),
            ("distance_to_water_m", dist, norm_dist * 100.0 * 0.20, 0.20),
            ("geology_grade", geo, norm_geo * 100.0 * 0.15, 0.15),
        ])

        # precipitation 维度
        rain_24 = float(factors.get("rainfall_24h", 0.0))
        rain_7 = float(factors.get("rainfall_7d", 0.0))
        norm_r24 = safe_norm(rain_24, self._dynamic_norm("rainfall_24h", region_code, RAIN_24H_NORM_MAX))
        norm_r7 = safe_norm(rain_7, self._dynamic_norm("rainfall_7d", region_code, RAIN_7D_NORM_MAX))
        contributions.extend([
            ("rainfall_24h", rain_24, norm_r24 * 100.0 * 0.6 * 0.40, 0.24),
            ("rainfall_7d", rain_7, norm_r7 * 100.0 * 0.4 * 0.40, 0.16),
        ])

        # 衍生特征（v1.4.0 新增）：按各自语义贡献
        sat = float(factors.get("_derived_rain_saturation_index", 0.0))
        intensity = float(factors.get("_derived_rain_intensity_ratio", 0.0))
        duration = float(factors.get("_derived_rain_duration_days", 0.0))
        foot = float(factors.get("_derived_foot_erosion_index", 0.0))
        geo_dist = float(factors.get("_derived_geology_distance_score", 0.0))
        disp_deriv = float(factors.get("_derived_disp_derivative", 0.0))

        # 衍生特征对总分的"附加解释贡献"（每个 max +X 分）
        contributions.extend([
            ("_derived_rain_saturation_index", sat, sat * 100.0 * 0.08, 0.08),
            ("_derived_rain_intensity_ratio", intensity, intensity * 100.0 * 0.05, 0.05),
            ("_derived_rain_duration_days", duration, min(duration / 7.0, 1.0) * 100.0 * 0.04, 0.04),
            ("_derived_foot_erosion_index", foot, foot * 100.0 * 0.06, 0.06),
            ("_derived_geology_distance_score", geo_dist, geo_dist * 100.0 * 0.05, 0.05),
            ("_derived_disp_derivative", max(0.0, disp_deriv), max(0.0, min(1.0, disp_deriv / 10.0)) * 100.0 * 0.05, 0.05),
        ])

        # 构造 dict + 排序
        result = []
        for name, value, contrib, weight in contributions:
            result.append({
                "feature": name,
                "raw_value": round(float(value), 4),
                "norm_value": round(float(contrib / weight / 100.0) if weight > 0 else 0.0, 4),
                "weight": round(weight, 4),
                "contribution": round(contrib, 2),  # 对总分的贡献（points）
            })
        result.sort(key=lambda x: x["contribution"], reverse=True)
        return result

    # ------------------------------------------------------------------ #
    # 灾害类型分类（v1.4.0 多因子加权评分升级版）
    # ------------------------------------------------------------------ #
    def classify_disaster_type(
        self,
        declared_type: Optional[str],
        factors: Dict[str, float],
        overall_level: str,
    ) -> Dict[str, Any]:
        """
        综合判定灾害类型（v1.4.0）：
          1. hazard_point.hazard_type 是首选（人工/源数据已标好）；
          2. 否则按多因子加权评分（TYPE_FEATURES）算每个类型的得分；
          3. 若最高分 < TYPE_SCORE_THRESHOLD 或领先不足 TYPE_LEAD_MARGIN → "待定"；
          4. 否则给出 winner + 关键贡献特征 + 置信度。
        """
        # 1. 优先采用字典里的 hazardType
        if declared_type and declared_type.strip() in SUPPORTED_DISASTER_TYPES:
            return {
                "disaster_type": declared_type,
                "source": "declared",
                "confidence": 0.95,
                "reason": f"灾害点档案已标注灾害类型为「{declared_type}」",
                "type_scores": {declared_type: 1.0},
                "type_top_contributors": [],
            }

        # 2. 多因子加权评分
        type_scores: Dict[str, float] = {}
        type_contributors: Dict[str, List[Dict[str, Any]]] = {}

        for dtype, feature_weights in self.TYPE_FEATURES.items():
            score = 0.0
            contributors: List[Dict[str, Any]] = []
            for feat, weight in feature_weights.items():
                # v1.4.0 fix：缺因子（factors.get 返回 None）→ norm=0，避免"distance=0 = 在水里"误判
                if feat not in factors or factors.get(feat) is None:
                    continue
                raw = float(factors.get(feat, 0.0) or 0.0)
                # 归一化：衍生特征已经在 [0,1]，原始特征按各自 MAX 归一化
                if feat.startswith("_derived_"):
                    norm = max(0.0, min(1.0, raw))
                elif feat == "slope_deg":
                    norm = max(0.0, min(1.0, raw / 60.0))
                elif feat == "geology_grade":
                    norm = max(0.0, min(1.0, raw / 5.0))
                elif feat == "soil_grade":
                    norm = max(0.0, min(1.0, raw / 5.0))
                elif feat == "vegetation_cover":
                    norm = 1.0 - max(0.0, min(1.0, raw))  # 植被少越危险
                elif feat == "distance_to_water_m":
                    norm = max(0.0, min(1.0, 1.0 - raw / 5000.0))  # 近水越危险
                elif feat == "rainfall_24h":
                    norm = max(0.0, min(1.0, raw / 150.0))
                elif feat == "displacement_rate":
                    norm = max(0.0, min(1.0, raw / 30.0))
                elif feat == "crack_width":
                    norm = max(0.0, min(1.0, raw / 50.0))
                else:
                    norm = 0.0
                contrib = norm * weight
                score += contrib
                if contrib > 0.02:  # 只保留有意义的
                    contributors.append({
                        "feature": feat,
                        "raw_value": round(raw, 4),
                        "norm_value": round(norm, 4),
                        "weight": weight,
                        "contribution": round(contrib, 4),
                    })
            type_scores[dtype] = round(score, 4)
            # 按贡献度降序
            contributors.sort(key=lambda x: x["contribution"], reverse=True)
            type_contributors[dtype] = contributors[:3]  # Top-3

        # 3. 排序取 winner
        ranked = sorted(type_scores.items(), key=lambda kv: kv[1], reverse=True)
        if not ranked:
            return {
                "disaster_type": DISASTER_TYPE_UNDETERMINED,
                "source": "fallback",
                "confidence": 0.0,
                "reason": "无可用因子，无法判定灾害类型",
                "type_scores": {},
                "type_top_contributors": [],
            }
        top_type, top_score = ranked[0]
        runner_score = ranked[1][1] if len(ranked) > 1 else 0.0
        margin = top_score - runner_score

        # 4. 阈值判断
        if top_score < self.TYPE_SCORE_THRESHOLD:
            return {
                "disaster_type": DISASTER_TYPE_UNDETERMINED,
                "source": "low_confidence",
                "confidence": round(top_score, 3),
                "reason": (
                    f"最高类型得分仅 {top_score:.2f}（阈值 {self.TYPE_SCORE_THRESHOLD}），"
                    "特征不足，不做具体分类"
                ),
                "type_scores": type_scores,
                "type_top_contributors": type_contributors,
            }
        if margin < self.TYPE_LEAD_MARGIN:
            # 与第二名差距太小 → "待定"
            return {
                "disaster_type": DISASTER_TYPE_UNDETERMINED,
                "source": "ambiguous",
                "confidence": round(top_score, 3),
                "reason": (
                    f"第一候选「{top_type}」({top_score:.2f}) 与第二候选 "
                    f"({runner_score:.2f}) 差距仅 {margin:.3f}，类型歧义较大"
                ),
                "type_scores": type_scores,
                "type_top_contributors": type_contributors,
            }

        # 5. 明确胜出
        confidence = round(min(0.92, 0.45 + top_score), 3)
        top_contributors = type_contributors[top_type]
        reason_text = "；".join([
            f"{c['feature']}({c['raw_value']:.2f}→{c['norm_value']:.2f})×{c['weight']}"
            for c in top_contributors
        ])
        return {
            "disaster_type": top_type,
            "source": "inferred",
            "confidence": confidence,
            "reason": f"多因子加权评分推断：{reason_text}",
            "type_scores": {k: round(v, 3) for k, v in type_scores.items()},
            "type_top_contributors": top_contributors,
        }

    # ------------------------------------------------------------------ #
    # v2.0 总分（七维度加权）
    # ------------------------------------------------------------------ #
    def _overall_score_v2(
        self,
        remote_sensing: Dict[str, Any],
        meteorology: Dict[str, Any],
        terrain: Dict[str, Any],
        sensor_score: float,
        sensor_available: bool,
        historical: Dict[str, Any],
        social_media: Dict[str, Any],
        supporting: Dict[str, Any],
    ) -> Tuple[float, float, str, bool]:
        """七维度加权总分。设备缺失时自动把 sensor 权重重新归一化到其余六类。"""
        weights = dict(BASE_WEIGHTS_V2)
        if not sensor_available:
            # 把 sensor 权重按比例分给其余六类
            total_others = sum(v for k, v in weights.items() if k != "sensor")
            scale = (total_others + weights["sensor"]) / total_others
            for k in weights:
                if k != "sensor":
                    weights[k] = (weights[k] / total_others) * scale * total_others
            weights["sensor"] = 0.0
        weighted = sum([
            remote_sensing["score"] * weights.get("remote_sensing", 0.0),
            meteorology["score"]   * weights.get("meteorology",    0.0),
            terrain["score"]       * weights.get("terrain",        0.0),
            sensor_score           * weights.get("sensor",         0.0),
            historical["score"]    * weights.get("historical",     0.0),
            social_media["score"]  * weights.get("social_media",   0.0),
            supporting["score"]    * weights.get("supporting",     0.0),
        ])
        covered = sum(1 for k in weights if k == "sensor" if sensor_available or k != "sensor")
        confidence = round(min(0.90, 0.35 + 0.05 * covered), 4)
        return round(weighted, 2), confidence, "heuristic-seven-dimension-v2", not sensor_available

    # ------------------------------------------------------------------ #
    # 总分（v1.4.0：自适应归一化权重；v2.0 升级见 _overall_score_v2）
    # ------------------------------------------------------------------ #
    def _overall_score(
        self,
        geographic: Dict[str, Any],
        precipitation: Dict[str, Any],
        device_score: float,
        device_available: bool,
    ) -> Tuple[float, str, bool]:
        """加权总分：当设备维度缺失时自动把它的权重分给 geographic + precipitation。"""
        weights = dict(BASE_WEIGHTS)
        if not device_available:
            weights["geographic"] = 0.55
            weights["precipitation"] = 0.45
            weights["device"] = 0.0
        weighted = (
            geographic["score"] * weights["geographic"]
            + precipitation["score"] * weights["precipitation"]
            + device_score * weights["device"]
        )
        weighted = round(max(0.0, min(100.0, weighted)), 2)
        # 设备缺失时显式标 degraded，方便前端/排班员识别
        # v1.5.0：融合模型若已加载（真实 XGBoost），device 维度走真推理；模型名透传
        fusion = self._fusion
        if fusion.is_loaded():
            model_name = "heuristic-geo-weather+xgboost-fusion"
        else:
            model_name = "heuristic-geo-weather+derived"
        return weighted, model_name, not device_available

    # ------------------------------------------------------------------ #
    # reasoning + suggested_actions（v1.4.0：贡献度排序 + 衍生特征归因）
    # ------------------------------------------------------------------ #
    def build_reasoning(
        self,
        overall_level: str,
        concern: str,
        geographic: Dict[str, Any],
        precipitation: Dict[str, Any],
        device_score: float,
        device_available: bool,
        disaster_type: str,
        feature_contributions: Optional[List[Dict[str, Any]]] = None,
    ) -> List[str]:
        items: List[str] = []

        rain_24h = precipitation.get("rainfall_24h", 0.0)
        rain_7d = precipitation.get("rainfall_7d", 0.0)
        if rain_24h >= 100 or rain_7d >= 250:
            items.append(
                f"24h 降雨 {rain_24h:.1f} mm / 7d 累计 {rain_7d:.1f} mm，已超过地质灾害极端降雨阈值，是当前最大风险源"
            )
        elif rain_24h >= 50 or rain_7d >= 150:
            items.append(
                f"24h 降雨 {rain_24h:.1f} mm 接近高风险阈值（7d {rain_7d:.1f} mm），需要持续关注"
            )
        elif rain_24h >= 25:
            items.append(f"24h 降雨 {rain_24h:.1f} mm，累计已达需关注水平")

        slope = geographic.get("slope_deg", 0.0)
        if slope >= 30:
            items.append(f"坡度 {slope:.1f}° 较陡，强降雨下易诱发浅层滑坡")
        distance = geographic.get("distance_to_water_m", 9999.0)
        if distance <= 200:
            items.append(f"距水体仅 {distance:.0f} m，存在坡脚冲刷风险")
        vegetation = geographic.get("vegetation_cover", 1.0)
        if vegetation <= 0.30:
            items.append(f"植被覆盖仅 {vegetation:.0%}，地表保护能力弱")

        if device_available:
            if device_score >= 50:
                items.append(f"设备维度评分 {device_score:.1f}，监测数据异常信号已纳入研判")
        else:
            items.append("当前未接入监测设备，本次研判完全依据当地地理与天气情况")

        # v1.4.0：衍生特征归因
        if feature_contributions:
            top3 = [c for c in feature_contributions[:3] if c["contribution"] > 0.5]
            for c in top3:
                feat = c["feature"]
                contrib = c["contribution"]
                if feat.startswith("_derived_"):
                    label_map = {
                        "_derived_rain_saturation_index": "降雨饱和指数",
                        "_derived_rain_intensity_ratio": "骤雨强度",
                        "_derived_rain_duration_days": "降雨持续天数",
                        "_derived_foot_erosion_index": "坡脚侵蚀指数",
                        "_derived_geology_distance_score": "地质×距水耦合",
                        "_derived_disp_derivative": "位移加速度",
                    }
                    label = label_map.get(feat, feat)
                    items.append(
                        f"衍生特征「{label}」对总分贡献 +{contrib:.1f} 分（{c['raw_value']:.3f}）"
                    )
                else:
                    items.append(
                        f"{feat}（值 {c['raw_value']:.2f}）对总分贡献 +{contrib:.1f} 分"
                    )

        items.append(f"综合判定为「{disaster_type}」类灾害，需{concern}跟进")
        return items

    def build_suggested_actions(
        self,
        overall_level: str,
        concern: str,
        geographic: Dict[str, Any],
        precipitation: Dict[str, Any],
        device_available: bool,
        disaster_type: str,
    ) -> List[str]:
        actions: List[str] = []

        rain_24h = precipitation.get("rainfall_24h", 0.0)
        slope = geographic.get("slope_deg", 0.0)
        distance = geographic.get("distance_to_water_m", 9999.0)

        if rain_24h >= 50:
            actions.append("加密雨量观测至 1h/次；持续关注气象部门短临预报")

        if not device_available and overall_level in ("HIGH", "CRITICAL"):
            actions.append("建议尽快派员对接现场，考虑部署位移/裂缝监测设备")

        if disaster_type == DISASTER_TYPE_DEBRIS_FLOW and rain_24h >= 25:
            actions.append("组织沟口警戒，必要时封闭下游行洪通道")
        if disaster_type == DISASTER_TYPE_LANDSLIDE and slope >= 25 and rain_24h >= 25:
            actions.append("组织周边受威胁居民会商，必要时启动避险转移预案")
        if disaster_type == DISASTER_TYPE_COLLAPSE and slope >= 40:
            actions.append("对陡崖下方的道路与人员活动区设置警戒线")
        if disaster_type == DISASTER_TYPE_GROUND_CRACK:
            actions.append("对已发现裂缝周边设置观测标尺，记录扩展情况")
        if disaster_type == DISASTER_TYPE_SUBSIDENCE:
            actions.append("对沉降区域建筑开展定期形变测量")
        if disaster_type == DISASTER_TYPE_SINKHOLE:
            actions.append("排查落水洞/塌陷坑，必要时填埋并设立警示标志")

        if overall_level in ("HIGH", "CRITICAL"):
            actions.append("向上级值班报送综合研判结果，同步至预警中心联动")
        if overall_level == "CRITICAL":
            actions.append("立即进入应急值守状态，24h 不间断监测 + 现场警戒")

        # 待定类型额外建议
        if disaster_type == DISASTER_TYPE_UNDETERMINED:
            actions.append("类型分类置信度不足，建议补充现场踏勘资料后重新评估")

        if not actions:
            actions.append("保持常规观测频次，关注 24h 降雨趋势")
        return actions

    # =========================== v2.0 Reasoning & Actions =========================== #

    def _build_reasoning_v2(
        self,
        overall_level: str,
        concern: str,
        remote_sensing: Dict[str, Any],
        meteorology: Dict[str, Any],
        terrain: Dict[str, Any],
        sensor_score: float,
        sensor_available: bool,
        historical: Dict[str, Any],
        social_media: Dict[str, Any],
        supporting: Dict[str, Any],
        disaster_type: str,
    ) -> List[str]:
        items: List[str] = []
        # [2] 气象水文
        met = meteorology
        rain_24h = met.get("rainfall_24h", 0.0)
        rain_7d  = met.get("rainfall_7d", 0.0)
        soil_moisture = met.get("soil_moisture", 0.0)
        if rain_24h >= 100 or rain_7d >= 250:
            items.append("[气象水文] 24h降雨" + f"{rain_24h:.1f}mm / 7d{rain_7d:.1f}mm，超过地质灾害极端降雨阈值，为首要风险源")
        elif rain_24h >= 50 or rain_7d >= 150:
            items.append("[气象水文] 24h降雨" + f"{rain_24h:.1f}mm接近高风险阈值（7d{rain_7d:.1f}mm），需持续关注")
        elif rain_24h >= 25:
            items.append("[气象水文] 24h降雨" + f"{rain_24h:.1f}mm，累计已达需关注水平")
        if soil_moisture >= 0.45:
            items.append("[气象水文] 土壤含水率" + f"{soil_moisture:.0%}接近饱和，强降雨下极易触发滑坡")
        # [1] 遥感
        rs = remote_sensing
        if rs.get("score", 0) >= 60:
            items.append("[遥感影像] " + f"{rs.get('source_label','遥感')}异常分数{rs['score']:.1f}，检测到{rs.get('anomaly_count',0)}处疑似异常，需现场核查")
        elif rs.get("score", 0) >= 35:
            items.append("[遥感影像] 异常分数" + f"{rs['score']:.1f}，地表存在轻度扰动")
        # [3] 地形地质
        tr = terrain
        slope = tr.get("slope_deg", 0.0)
        dist  = tr.get("distance_to_water_m", 9999.0)
        veg   = tr.get("vegetation_cover", 1.0)
        if slope >= 30:
            items.append("[地形地质] 坡度" + f"{slope:.1f}°较陡（≥30°），强降雨下易诱发浅层滑坡")
        if dist <= 300:
            items.append("[地形地质] 距水体仅" + f"{dist:.0f}m，坡脚冲刷风险高")
        if veg <= 0.30:
            items.append("[地形地质] 植被覆盖" + f"{veg:.0%}偏低，地表保护能力弱")
        # [4] 传感器
        if sensor_available and sensor_score >= 50:
            items.append("[地面传感器] 监测数据异常信号已纳入研判，评分" + f"{sensor_score:.1f}")
        # [5] 历史灾情
        his = historical
        if his.get("historical_count", 0) >= 5:
            items.append("[历史灾情] 该点历史灾害事件" + f"{his['historical_count']}次，为高频灾害点，需重点关注")
        if his.get("exposure_population", 0) > 5000:
            items.append("[历史灾情] 承灾人口" + f"{his['exposure_population']}人，高密度区域需优先保障")
        # [6] 社交媒体
        sm = social_media
        if sm.get("social_alert_level", 0) >= 3:
            items.append("[社交媒体] 舆情告警等级" + f"{sm.get('social_alert_label','高')}/4，多平台出现灾害相关报告")
        # [7] 辅助数据
        sup = supporting
        if sup.get("power_status", 1.0) < 0.3:
            items.append("[辅助数据] 区域电力断供（" + f"{sup.get('power_label','断电')}" + "），应急响应能力严重受限")
        if sup.get("road_access_score", 1.0) < 0.3:
            items.append("[辅助数据] 道路可达性" + f"{sup.get('road_access_score',0):.0%}，救援通道受限")
        if not items and overall_level in ("HIGH", "CRITICAL"):
            items.append("综合评分" + overall_level + "，多维度评分均偏高，建议结合现场情况复核")
        if not items:
            items.append("七维度评分均处于可控范围，整体风险平稳，保持常规监测即可")
        items.append("综合判定为「" + disaster_type + "」类灾害，需" + concern + "跟进")
        return items

    def _build_actions_v2(
        self,
        overall_level: str,
        concern: str,
        remote_sensing: Dict[str, Any],
        meteorology: Dict[str, Any],
        terrain: Dict[str, Any],
        sensor_available: bool,
        historical: Dict[str, Any],
        social_media: Dict[str, Any],
        supporting: Dict[str, Any],
        disaster_type: str,
    ) -> List[str]:
        actions: List[str] = []
        met = meteorology
        rain_24h = met.get("rainfall_24h", 0.0)
        tr = terrain
        slope = tr.get("slope_deg", 0.0)
        sm = social_media
        sup = supporting
        rs = remote_sensing
        # 气象
        if rain_24h >= 50:
            actions.append("加密雨量观测至1h/次；持续关注气象部门短临预报，及时接收强降雨预警")
        if met.get("soil_moisture", 0) >= 0.45:
            actions.append("土壤含水率已饱和，严密监测坡脚渗水情况和地表裂缝扩展")
        # 遥感
        if rs.get("score", 0) >= 60:
            actions.append("派遣现场核查人员，记录裂缝位置/长度/宽度变化，并拍照存档")
        if rs.get("score", 0) >= 80:
            actions.append("建议立即启动无人机航拍，获取高分辨率灾害全景影像")
        # 传感器
        if not sensor_available and overall_level in ("HIGH", "CRITICAL"):
            actions.append("建议尽快派员对接现场，考虑部署位移/裂缝监测设备")
        # 地形
        if slope >= 30 and rain_24h >= 25:
            actions.append("组织周边受威胁居民会商，必要时启动避险转移预案，做好转移安置准备")
        if tr.get("distance_to_water_m", 9999) <= 300:
            actions.append("加强坡脚河段水位监测，防范冲刷掏蚀引发底部失稳")
        # 历史
        if historical.get("historical_count", 0) >= 5:
            actions.append("该点为历史高频灾害点，建议申请专项治理资金，推进工程加固")
        # 社交媒体
        if sm.get("social_alert_level", 0) >= 3:
            actions.append("舆情显示灾害信息快速扩散，建议通过官方渠道发布权威信息，避免恐慌蔓延")
        if sm.get("emergency_level", 0) >= 3:
            actions.append("应急热线已出现大量求助记录，建议调度救援力量前往")
        # 辅助
        if sup.get("power_status", 1.0) < 0.5:
            actions.append("区域电力不稳定，协调电力部门优先保障监测设备供电，备用电源就位")
        if sup.get("road_access_score", 1.0) < 0.3:
            actions.append("道路受阻，协调交通部门抢通救援通道，同步规划替代疏散路线")
        # 综合
        if overall_level in ("HIGH", "CRITICAL"):
            actions.append("向上级值班报送综合分析结果，同步至预警中心联动，启动部门会商机制")
        if overall_level == "CRITICAL":
            actions.append("立即进入应急值守状态，24h不间断监测+现场警戒，必要时申请上级支援")
        if not actions:
            actions.append("保持常规监测频次，关注24h降雨趋势和各维度指标变化")
        return actions

    # ------------------------------------------------------------------ #
    # 对外主接口 v2.0：完整七维度综合评估
    #   - v1.x 兼容：三维度（geographic / precipitation / device）保留在 dimensions；
    #   - v2.0 新增：七维度（remote_sensing / meteorology / terrain / sensor / historical / social_media / supporting）
    # ------------------------------------------------------------------ #
    def score(self, hazard: Dict[str, Any], factors: Dict[str, float]) -> Dict[str, Any]:
        """
        Args:
            hazard: {"hazard_code": str, "hazard_type": str | None, "region_code": str | None}
            factors: 与七类数据源 schema 对齐的因子字典（各维度因子均可选）
        Returns:
            完整评估：overall / disaster_type / dimensions（含 v1 兼容 + v2 七维度）/ reasoning / suggested_actions / feature_contributions
        """
        hazard_code = hazard.get("hazard_code", "")
        declared_type = hazard.get("hazard_type")
        region_code = hazard.get("region_code") or hazard_code or "_default"

        # ==== 特征工程（v1.4.0：6 个衍生特征） ==== #
        factors_with_derived = compute_derived_features(factors)
        for key in (
            "_derived_rain_saturation_index", "_derived_rain_intensity_ratio",
            "_derived_rain_duration_days", "_derived_foot_erosion_index",
            "_derived_geology_distance_score",
        ):
            v = factors_with_derived.get(key)
            if v is not None:
                self._normalizer.fit_one(key, v, region_code)
        for k in ("rainfall_24h", "rainfall_7d", "slope_deg", "vegetation_cover"):
            v = factors.get(k)
            if v is not None:
                self._normalizer.fit_one(k, v, region_code)

        # ==== v1.0 三维度（向后兼容） ==== #
        geographic = self.score_geographic(factors_with_derived, region_code)
        precipitation = self.score_precipitation(factors_with_derived, region_code)
        device_available = has_device_factors(factors)
        device_score, device_conf = self.score_device(factors) if device_available else (0.0, 0.0)

        # ==== v2.0 七维度 ==== #
        remote_sensing  = self.score_remote_sensing(factors)
        meteorology     = self.score_meteorology_extended(factors, region_code)
        terrain         = self.score_terrain_extended(factors, region_code)
        sensor_available = device_available  # sensor = device
        sensor_score    = device_score
        historical      = self.score_historical(factors)
        social_media   = self.score_social_media(factors)
        supporting      = self.score_supporting(factors)

        # ==== v1.0 总分（向后兼容 API） ==== #
        overall_score_v1, model_name_v1, mock_v1 = self._overall_score(
            geographic, precipitation, device_score, device_available
        )
        overall_level_v1 = score_to_level(overall_score_v1)

        # ==== v2.0 总分 ==== #
        overall_score_v2, confidence_v2, model_name_v2, mock_v2 = self._overall_score_v2(
            remote_sensing, meteorology, terrain, sensor_score, sensor_available,
            historical, social_media, supporting,
        )
        overall_level_v2 = score_to_level(overall_score_v2)
        concern_v2 = concern_label(overall_level_v2)

        # ==== 采用 v2.0 作为主输出 ==== #
        overall_score  = overall_score_v2
        overall_level  = overall_level_v2
        concern        = concern_v2
        model_name    = model_name_v2
        mock          = mock_v2

        # ==== 灾害类型分类（v1.4.0 多因子加权） ==== #
        dt = self.classify_disaster_type(declared_type, factors_with_derived, overall_level)

        # ==== SHAP-like 特征贡献度 ==== #
        contributions = self.compute_feature_contributions(factors_with_derived, region_code)

        # ==== 置信度 ==== #
        covered = sum(
            1 for k in (
                "elevation_m", "slope_deg", "soil_grade", "vegetation_cover",
                "distance_to_water_m", "geology_grade",
                "rainfall_24h", "rainfall_7d",
                "image_anomaly_score",
                "social_alert_level",
                "nighttime_light_index",
            )
            if factors.get(k) is not None
        )
        confidence = round(min(0.92, 0.40 + 0.045 * covered), 4)

        # ==== reasoning + actions（使用 v2.0 维度） ==== #
        reasoning = self._build_reasoning_v2(
            overall_level, concern, remote_sensing, meteorology, terrain,
            sensor_score, sensor_available, historical, social_media, supporting,
            dt["disaster_type"],
        )
        actions = self._build_actions_v2(
            overall_level, concern, remote_sensing, meteorology, terrain,
            sensor_available, historical, social_media, supporting,
            dt["disaster_type"],
        )

        # ==== 维度字典（含 v1 兼容 + v2 七维度） ==== #
        dimensions: Dict[str, Any] = {
            # v1.x 兼容（保持原有字段名）
            "geographic": geographic,
            "precipitation": precipitation,
            "device": {
                "score": round(device_score, 2),
                "confidence": round(device_conf, 4),
                "available": device_available,
            },
            # v2.0 七维度（新增）
            "remote_sensing": remote_sensing,
            "meteorology": meteorology,
            "terrain": terrain,
            "sensor": {
                "score": round(sensor_score, 2),
                "confidence": round(device_conf, 4),
                "available": sensor_available,
            },
            "historical": historical,
            "social_media": social_media,
            "supporting": supporting,
        }

        return {
            "overall": {
                "risk_level": overall_level,
                "risk_score": overall_score,
                "confidence": confidence,
                "concern_label": concern,
                "model": model_name,
                "mock": mock,
                "device_available": device_available,
                # v1.x 兼容字段
                "_v1": {
                    "risk_level": overall_level_v1,
                    "risk_score": overall_score_v1,
                    "model": model_name_v1,
                },
            },
            "disaster_type": dt,
            "dimensions": dimensions,
            "feature_contributions": contributions,
            "reasoning": reasoning,
            "suggested_actions": actions,
        }


_model_singleton: Optional[DisasterAssessmentModel] = None


def get_disaster_assessment_model() -> DisasterAssessmentModel:
    global _model_singleton
    if _model_singleton is None:
        _model_singleton = DisasterAssessmentModel()
    return _model_singleton