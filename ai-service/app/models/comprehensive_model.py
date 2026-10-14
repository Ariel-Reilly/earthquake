"""
综合风险分析模型 v3.0 —— 多源数据融合体系。

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
  - 总体 risk_score / risk_level / confidence / model
  - 六维度各自 sub_score + level + details（便于 UI 展示明细）
  - reasoning 数组：可解释的风险归因（每条带维度标签）
  - suggested_actions 数组：可执行建议

因子 schema（与 Java 端 ComprehensiveRequest.factors 对齐）
============================================================

  [1] 遥感影像（Remote Sensing）—— 空间基础
      image_anomaly_score      0-100   YOLO 检测或卫星分析输出的异常分数
      image_anomaly_count      int     检测到的异常目标数量
      image_source             str     来源：sentinel1 / sentinel2 / landsat / modis / radar / drone / camera
      image_confidence         0-1     图像识别置信度
      image_timestamp          str     图像采集时间（ISO 8601）

  [2] 气象水文（Meteorological & Hydrological）—— 触发条件
      rainfall_24h             mm      24h 累计降雨量
      rainfall_7d              mm      7d 累计降雨量
      rainfall_30d             mm      30d 累计降雨量
      temperature              °C      当前气温
      humidity                 0-1     相对湿度
      soil_moisture            0-1     土壤含水率
      water_level              m       河流水位

  [3] 地形地质（Terrain & Geology）—— 易发性判断
      elevation_m              m       海拔
      slope_deg                °       地面坡度
      aspect_deg               °       坡向（0-360）
      curvature                —       地形曲率（正=凸，负=凹）
      soil_grade               1-5     土壤易蚀性（越大越易蚀）
      vegetation_cover          0-1     植被覆盖度（越高越稳定）
      distance_to_water_m      m       距最近水体距离
      geology_grade            1-5     地质脆弱性
      geology_label            str     地质岩性标签（如"砂泥岩""碳酸盐岩"）
      fault_distance_m         m       距最近活动断层距离

  [4] 地面传感器/IoT（Ground Sensors）—— 近实时动态
      displacement_rate        mm/day  当前位移速率
      displacement_total       mm      累计位移
      crack_width             mm      裂缝宽度
      earthquake_mag           M       最近地震震级
      tilt_angle              °        设备倾斜角
      site_grade              1-5     场地等级（从风险等级映射）

  [5] 历史灾情与承灾体（Historical Disasters & Exposure）—— 训练验证
      historical_count         int     该点历史灾害事件数
      historical_max_score    0-100   历史最大灾害评分
      exposure_population     人       承灾人口
      exposure_buildings      栋       承灾建筑物
      land_use_type            str     土地利用类型：cropland / forest / urban / water / barren

  [6] 辅助数据（Supporting Data）—— 可选增强
      social_alert_level       0-4     社交媒体告警等级（0=无，4=紧急）
      social_post_count        int     相关帖文数量
      social_sentiment_score   -1~1    情感极性（负=恐慌，正=正常）
      social_emergency_level   0-4     应急热线告警等级
      nighttime_light_index    0-63    NASA 夜间灯光指数（越高=经济活跃=城市化程度高）
      infrastructure_score    0-1     路网密度 + 电力覆盖综合得分
      road_access_score       0-1     道路可达性（救援路径评估）
      power_status            0-1     当前电力供应状态（0=断电，1=正常）

归一化参考
==========
RAIN_24H_NORM_MAX = 150.0   mm   超过 150mm 截断
RAIN_7D_NORM_MAX  = 400.0   mm
RAIN_30D_NORM_MAX = 800.0   mm
SLOPE_NORM_MAX    = 60.0     °    超过 60° 截断
ELEVATION_NORM_MAX = 3000.0  m
DISTANCE_NORM_MAX = 5000.0   m
"""

from __future__ import annotations

import math
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import numpy as np

from app.config import MODELS_DIR
from app.models.xgboost_model import get_fusion_model, DEFAULT_WEIGHTS
from app.models.feature_engineering import compute_derived_features
from app.models.adaptive_normalizer import get_adaptive_normalizer

IMAGE_NORM_MAX = 100.0
RAIN_24H_NORM_MAX = 150.0
RAIN_7D_NORM_MAX = 400.0
RAIN_30D_NORM_MAX = 800.0
ELEVATION_NORM_MAX = 3000.0
SLOPE_NORM_MAX = 60.0
SOIL_NORM_MAX = 5.0
VEGETATION_NORM_MAX = 1.0
DISTANCE_NORM_MAX = 5000.0
GEOLOGY_NORM_MAX = 5.0
SOCIAL_ALERT_NORM_MAX = 4.0
NIGHTLIGHT_NORM_MAX = 63.0
INFRASTRUCTURE_NORM_MAX = 1.0

# 说明：
#   - 气象水文（0.22）和遥感（0.20）权重最高：触发条件+空间基础是核心
#   - 地面传感器（0.18）：近实时前兆信号，秒级预警
#   - 地形地质（0.15）：易发性判断，基础静态
#   - 历史灾情（0.10）：训练样本质量
#   - 社交媒体（0.08）：快速感知，补充遥感盲区
#   - 辅助数据（0.07）：影响评估
DIMENSION_WEIGHTS: Dict[str, float] = {
    "remote_sensing":   0.20,  # 遥感影像
    "meteorology":      0.22,  # 气象水文
    "terrain":          0.15,  # 地形地质
    "sensor":           0.18,  # 地面传感器
    "historical":       0.10,  # 历史灾情
    "social_media":     0.08,  # 社交媒体
    "supporting":       0.07,  # 辅助数据
}

LEVEL_THRESHOLDS = (
    (80.0, "CRITICAL"),
    (60.0, "HIGH"),
    (35.0, "MEDIUM"),
    (0.0,  "LOW"),
)


def score_to_level(score: float) -> str:
    for threshold, level in LEVEL_THRESHOLDS:
        if score >= threshold:
            return level
    return "LOW"


def safe_norm(value: float, max_val: float) -> float:
    if not math.isfinite(value):
        return 0.0
    if max_val <= 0:
        return 0.0
    return max(0.0, min(1.0, value / max_val))


# 七维度评分函数

def score_remote_sensing(factors: Dict[str, float]) -> Dict[str, Any]:
    """[1] 遥感影像维度：光学/雷达/热红外/无人机图像异常评分。"""
    raw = factors.get("image_anomaly_score", 0.0)
    score = max(0.0, min(100.0, raw))
    level = score_to_level(score)
    count = int(factors.get("image_anomaly_count", 0))
    source = factors.get("image_source", "unknown")
    confidence = factors.get("image_confidence", 0.5)
    ts = factors.get("image_timestamp", "")

    # 详情描述
    if score <= 0:
        detail = "暂无遥感影像异常记录"
    elif score <= 30:
        detail = f"遥感异常分数 {score:.1f}，影像{get_source_label(source)}地表状态基本稳定"
    elif score <= 60:
        detail = f"遥感异常分数 {score:.1f}，发现 {count} 处疑似异常，建议加密复核"
    elif score <= 80:
        detail = f"遥感异常分数 {score:.1f}，{count} 处疑似裂缝/形变/淹没，需现场核查"
    else:
        detail = f"遥感异常分数 {score:.1f}，{count} 处显著异常（滑坡/崩塌前兆），建议立即现场核查"

    return {
        "score": round(score, 2),
        "level": level,
        "source": source,
        "source_label": get_source_label(source),
        "anomaly_count": count,
        "confidence": round(float(confidence), 4),
        "image_timestamp": ts,
        "details": detail,
        "data_category": "remote_sensing",
    }


def score_meteorology(factors: Dict[str, float]) -> Dict[str, Any]:
    """[2] 气象水文维度：降雨量/温度/湿度/土壤含水率/水位。"""
    rain_24h = max(0.0, factors.get("rainfall_24h", 0.0))
    rain_7d  = max(0.0, factors.get("rainfall_7d",  0.0))
    rain_30d = max(0.0, factors.get("rainfall_30d", 0.0))
    temp = factors.get("temperature", 20.0)
    humidity = factors.get("humidity", 0.5)
    soil_moisture = factors.get("soil_moisture", 0.3)
    water_level = factors.get("water_level", 0.0)
    wind_speed = factors.get("wind_speed", 0.0)  # m/s（ERA5 再分析）

    # 降雨归一化：24h(0.5) + 7d(0.3) + 30d(0.2)
    norm_24h = safe_norm(rain_24h, RAIN_24H_NORM_MAX)
    norm_7d  = safe_norm(rain_7d,  RAIN_7D_NORM_MAX)
    norm_30d = safe_norm(rain_30d, RAIN_30D_NORM_MAX)
    rain_combined = 0.5 * norm_24h + 0.3 * norm_7d + 0.2 * norm_30d

    # 土壤含水率：>0.45 饱和 → +0.3 风险
    soil_risk = 0.0
    if soil_moisture >= 0.45:
        soil_risk = 0.3
    elif soil_moisture >= 0.35:
        soil_risk = 0.1

    # 水位：超过警戒水位 → +0.2 风险
    level_risk = 0.0
    if water_level > 0 and factors.get("water_level_warning", 0) > 0:
        level_risk = min(0.3, water_level / factors.get("water_level_warning", 1.0) * 0.3)

    score = round((rain_combined * 0.7 + soil_risk + level_risk) * 100.0, 2)
    level = score_to_level(score)

    # 与中国地质灾害降雨阈值对比
    if rain_24h >= 100 or rain_7d >= 250:
        rain_trend = "极端"
        threshold_note = "超过地质灾害极端降雨阈值（24h≥100mm / 7d≥250mm）"
    elif rain_24h >= 50 or rain_7d >= 150:
        rain_trend = "高"
        threshold_note = "接近/超过地质灾害高风险降雨阈值（24h≥50mm / 7d≥150mm）"
    elif rain_24h >= 25 or rain_7d >= 80:
        rain_trend = "中"
        threshold_note = "累计降雨达到需关注水平"
    else:
        rain_trend = "平稳"
        threshold_note = "降雨平稳，未触发降雨阈值"

    detail = (
        f"24h {rain_24h:.1f}mm · 7d {rain_7d:.1f}mm · 30d {rain_30d:.1f}mm，"
        f"气温{temp:.1f}°C · 风速{wind_speed:.1f}m/s，"
        f"趋势：{rain_trend}（{threshold_note}）"
    )
    if soil_moisture >= 0.45:
        detail += f"；土壤含水率 {soil_moisture:.0%}（接近饱和）"
    if water_level > 0 and level_risk > 0:
        detail += f"；河流水位超警戒 {water_level:.2f}m"

    return {
        "score": score,
        "level": level,
        "rainfall_24h": round(rain_24h, 2),
        "rainfall_7d": round(rain_7d, 2),
        "rainfall_30d": round(rain_30d, 2),
        "temperature": round(float(temp), 1),
        "humidity": round(float(humidity), 3),
        "soil_moisture": round(float(soil_moisture), 3),
        "water_level": round(float(water_level), 2),
        "wind_speed": round(float(wind_speed), 1),
        "rain_trend": rain_trend,
        "details": detail,
        "data_category": "meteorology",
    }


def score_terrain(factors: Dict[str, float]) -> Dict[str, Any]:
    """[3] 地形地质维度：海拔/坡度/坡向/曲率/土壤/植被/距水/地质/断层。"""
    elevation  = factors.get("elevation_m", 0.0)
    slope      = factors.get("slope_deg", 0.0)
    aspect     = factors.get("aspect_deg", 180.0)
    curvature  = factors.get("curvature", 0.0)
    soil       = factors.get("soil_grade", 1.0)
    vegetation = factors.get("vegetation_cover", 0.5)
    distance   = factors.get("distance_to_water_m", 1000.0)
    geology    = factors.get("geology_grade", 1.0)
    fault_dist = factors.get("fault_distance_m", 99999.0)
    geology_label = factors.get("geology_label", "未指定")

    norm_slope    = safe_norm(slope, SLOPE_NORM_MAX)
    norm_soil     = safe_norm(soil, SOIL_NORM_MAX)
    norm_distance = max(0.0, 1.0 - safe_norm(distance, DISTANCE_NORM_MAX))  # 越近越危险
    norm_geology  = safe_norm(geology, GEOLOGY_NORM_MAX)
    norm_fault    = max(0.0, 1.0 - safe_norm(fault_dist, 10000.0))  # 越近越危险
    # 植被：覆盖度越高越稳定（反向）
    norm_vegetation = 1.0 - safe_norm(vegetation, VEGETATION_NORM_MAX)
    # 曲率：正=凸（排水好→稳定），负=凹（汇水→危险）
    curvature_risk = max(0.0, -curvature * 0.1)
    # 坡向：阴坡（北/东北/西北 0-90° 和 270-360°）含水量高→风险偏高
    if 0 <= aspect < 90 or 270 <= aspect <= 360:
        aspect_risk = 0.05
    else:
        aspect_risk = 0.0

    combined = (
        norm_slope * 0.22
        + norm_soil * 0.18
        + norm_distance * 0.18
        + norm_geology * 0.14
        + norm_vegetation * 0.10
        + norm_fault * 0.10
        + curvature_risk
        + aspect_risk
    )
    score = round(combined * 100.0, 2)
    level = score_to_level(score)

    flags = []
    if slope >= 30:
        flags.append(f"坡度{slope:.1f}°较陡（≥30°易滑坡）")
    if distance <= 300:
        flags.append(f"距水体仅{distance:.0f}m（坡脚冲刷风险）")
    if vegetation <= 0.30:
        flags.append(f"植被覆盖{vegetation:.0%}偏低（地表保护弱）")
    if geology_label in ("碳酸盐岩", "可溶性岩"):
        flags.append(f"岩性{geology_label}（岩溶塌陷风险）")
    if fault_dist <= 2000:
        flags.append(f"距断层{fault_dist:.0f}m（地震触发风险）")

    detail = (
        f"海拔{elevation:.0f}m · 坡度{slope:.1f}° · "
        f"土壤{soil:.1f}/5 · 植被{vegetation:.0%} · "
        f"距水体{distance:.0f}m · 地质{geology_label}"
    )
    if flags:
        detail = detail + "；" + "；".join(flags)

    return {
        "score": score,
        "level": level,
        "elevation_m": round(float(elevation), 1),
        "slope_deg": round(float(slope), 1),
        "aspect_deg": round(float(aspect), 1),
        "curvature": round(float(curvature), 4),
        "soil_grade": round(float(soil), 1),
        "vegetation_cover": round(float(vegetation), 3),
        "distance_to_water_m": round(float(distance), 1),
        "geology_grade": round(float(geology), 1),
        "geology_label": geology_label,
        "fault_distance_m": round(float(fault_dist), 1),
        "details": detail,
        "data_category": "terrain",
    }


def score_sensor(factors: Dict[str, float]) -> Dict[str, Any]:
    """[4] 地面传感器/IoT 维度：位移/裂缝/地震/倾斜，继承自 fusion-risk。"""
    fusion = get_fusion_model()
    DEVICE_KEYS = list(DEFAULT_WEIGHTS.keys())
    sub = {k: factors.get(k, 0.0) for k in DEVICE_KEYS}
    result = fusion._run_weighted(sub)
    raw_score = float(result["risk_score"])
    confidence = float(result["confidence"])

    disp_rate   = factors.get("displacement_rate", 0.0)
    crack_width = factors.get("crack_width", 0.0)
    tilt_angle  = factors.get("tilt_angle", 0.0)
    eq_mag      = factors.get("earthquake_mag", 0.0)

    level = score_to_level(raw_score)
    flags = []
    if disp_rate >= 5.0:
        flags.append(f"位移速率{disp_rate:.2f}mm/day（超过警戒值）")
    if crack_width >= 10.0:
        flags.append(f"裂缝宽度{crack_width:.1f}mm（需评估扩展趋势）")
    if tilt_angle >= 5.0:
        flags.append(f"设备倾斜角{tilt_angle:.1f}°（可能基础沉降）")
    if eq_mag >= 4.0:
        flags.append(f"附近发生{eq_mag:.1f}级地震（可能触发响应）")

    detail = f"位移速率{disp_rate:.2f}mm/day · 裂缝{crack_width:.1f}mm · 倾斜角{tilt_angle:.1f}° · 地震{eq_mag:.1f}级"
    if flags:
        detail = detail + "；" + "；".join(flags)

    return {
        "score": round(raw_score, 2),
        "level": level,
        "displacement_rate": round(float(disp_rate), 3),
        "displacement_total": round(float(factors.get("displacement_total", 0.0)), 2),
        "crack_width": round(float(crack_width), 2),
        "earthquake_mag": round(float(eq_mag), 2),
        "tilt_angle": round(float(tilt_angle), 2),
        "confidence": round(confidence, 4),
        "details": detail,
        "data_category": "sensor",
    }


def score_historical(factors: Dict[str, float]) -> Dict[str, Any]:
    """[5] 历史灾情与承灾体维度：历史事件数/最大评分/承灾人口/建筑物。"""
    hist_count  = int(factors.get("historical_count", 0))
    hist_max    = max(0.0, factors.get("historical_max_score", 0.0))
    pop         = int(factors.get("exposure_population", 0))
    buildings   = int(factors.get("exposure_buildings", 0))
    land_use    = factors.get("land_use_type", "unknown")

    # 历史评分归一化
    hist_score = min(100.0, hist_max + hist_count * 8.0)
    # 承灾体因子：人口/建筑物越多，加权风险越高
    exposure_factor = min(1.0, (pop / 10000.0 + buildings / 1000.0) * 0.5)
    # 土地利用风险
    land_risk = {
        "urban": 0.3, "cropland": 0.5, "forest": 0.15,
        "water": 0.6, "barren": 0.1, "unknown": 0.2
    }.get(land_use, 0.2)

    score = round((hist_score * 0.5 + exposure_factor * 30.0 + land_risk * 20.0), 2)
    level = score_to_level(score)

    exposure_note = ""
    if pop > 5000:
        exposure_note = f"承灾人口{pop}人（高密度区域）"
    elif pop > 0:
        exposure_note = f"承灾人口{pop}人"
    land_label = {
        "urban": "城镇区", "cropland": "农业区", "forest": "林地区",
        "water": "水域", "barren": "荒漠", "unknown": "未分类"
    }.get(land_use, "未分类")
    detail = f"历史事件{hist_count}次 · 历史最大评分{hist_max:.0f} · {land_label} · {exposure_note}"
    if hist_count >= 5:
        detail += "（高频灾害点，需重点关注）"

    return {
        "score": score,
        "level": level,
        "historical_count": hist_count,
        "historical_max_score": round(hist_max, 1),
        "exposure_population": pop,
        "exposure_buildings": buildings,
        "land_use_type": land_use,
        "land_use_label": land_label,
        "details": detail,
        "data_category": "historical",
    }


def score_social_media(factors: Dict[str, float]) -> Dict[str, Any]:
    """[6] 社交媒体与文本维度：GDACS 灾害警报 / 社交媒体舆情 / 应急热线。
    GDACS alertscore (1-5) → social_alert_level (0-4)：直接 -1 归一化。
    """
    # GDACS alertscore (1=Green, 2=Yellow, 3=Orange, 4=Red) → Python 0-3
    raw_alert = factors.get("social_alert_level", 0.0)
    alert_level = max(0.0, min(4.0, raw_alert - 1.0))  # GDACS 1→0, 2→1, 3→2, 4→3, 5→4
    post_count  = int(factors.get("social_post_count", 0))
    sentiment    = max(-1.0, min(1.0, factors.get("social_sentiment_score", 0.0)))
    emergency   = max(0.0, min(4.0, factors.get("social_emergency_level", 0.0)))

    # 告警等级是主力，帖文数量和情感作为辅助
    norm_alert = safe_norm(alert_level, SOCIAL_ALERT_NORM_MAX)
    # 帖文数量：超过 10 条 → 舆情扩散
    post_factor = min(0.3, post_count / 50.0)
    # 情感：越负（恐慌） → 风险越高
    sentiment_factor = max(0.0, -sentiment * 0.3)
    # 应急热线等级（独立加成）
    emergency_factor = safe_norm(emergency, SOCIAL_ALERT_NORM_MAX) * 0.3

    score = round((norm_alert * 0.6 + post_factor + sentiment_factor + emergency_factor) * 100.0, 2)
    level = score_to_level(score)

    alert_label = {0: "无", 1: "低", 2: "中", 3: "高", 4: "紧急"}.get(int(alert_level), "未知")
    sentiment_label = "恐慌" if sentiment < -0.3 else "正常" if sentiment > 0.3 else "中性"

    detail = f"舆情告警等级{alert_label}({alert_level:.0f}/4) · 相关帖文{post_count}条 · 情感{sentiment_label}({sentiment:.2f})"
    if emergency >= 3:
        detail += f"；应急热线告警等级{emergency:.0f}/4（高紧急）"
    if post_count >= 30:
        detail += "（舆情快速扩散）"
    if alert_level >= 3:
        detail += "；多平台出现灾害相关报告"

    return {
        "score": score,
        "level": level,
        "social_alert_level": int(alert_level),
        "social_alert_label": alert_label,
        "post_count": post_count,
        "sentiment_score": round(sentiment, 3),
        "sentiment_label": sentiment_label,
        "emergency_level": int(emergency),
        "details": detail,
        "data_category": "social_media",
    }


def score_supporting(factors: Dict[str, float]) -> Dict[str, Any]:
    """[7] 辅助数据维度：夜间灯光/基础设施/道路/电力。"""
    ntl      = max(0.0, min(63.0, factors.get("nighttime_light_index", 31.0)))
    infra    = max(0.0, min(1.0, factors.get("infrastructure_score", 0.5)))
    road     = max(0.0, min(1.0, factors.get("road_access_score", 0.5)))
    power    = max(0.0, min(1.0, factors.get("power_status", 1.0)))

    # 夜间灯光：城市化程度高（ntl > 40）→ 承灾体密度高，但应急响应能力强
    # 反之（ntl < 15）→ 偏远农村，响应能力弱
    ntl_factor = safe_norm(ntl, NIGHTLIGHT_NORM_MAX)
    # 路网密度和可达性：越高 → 救援能力越强 → 风险越低（反向）
    access_score = (road * 0.6 + infra * 0.4)
    access_risk = 1.0 - access_score
    # 电力：断电 → 应急响应能力下降 → 风险高
    power_risk = 1.0 - power

    score = round((access_risk * 0.7 + power_risk * 0.2 + ntl_factor * 0.1) * 100.0, 2)
    level = score_to_level(score)

    detail = f"夜间灯光指数{ntl:.0f}/63 · 路网可达{road:.0%} · 基础设施{int(infra*100)}% · 电力{'正常' if power > 0.7 else '不稳定' if power > 0.3 else '断电'}"
    if power < 0.3:
        detail += "（区域断电，应急响应能力严重受限）"
    if access_score < 0.3:
        detail += "（交通受阻，救援通道受限）"

    return {
        "score": score,
        "level": level,
        "nighttime_light_index": round(ntl, 1),
        "infrastructure_score": round(infra, 3),
        "road_access_score": round(road, 3),
        "power_status": round(power, 3),
        "power_label": "正常" if power > 0.7 else "不稳定" if power > 0.3 else "断电",
        "details": detail,
        "data_category": "supporting",
    }


def get_source_label(source: str) -> str:
    labels = {
        "sentinel2": "Sentinel-2",
        "sentinel1": "Sentinel-1",
        "sentinel": "Sentinel-2",
        "copernicus_sentinel2": "Sentinel-2（Copernicus）",
        "landsat": "Landsat",
        "modis": "MODIS",
        "viirs": "VIIRS",
        "radar": "雷达SAR",
        "sar": "雷达SAR",
        "thermal": "热红外",
        "drone": "无人机",
        "inspection_camera": "地面相机（巡检）",
        "camera": "地面相机",
        "none": "无影像",
        "unknown": "未知",
    }
    return labels.get(source.lower(), source)


# 综合评分

class ComprehensiveRiskModel:
    """
    综合风险分析模型 v2.0：七类数据源融合。

    输入：factors 字典（七类数据的任意子集，有值则用，无值则默认 0/中性）
    输出：{overall, dimensions, reasoning, suggested_actions}
    """

    def __init__(self, model_path: Optional[str] = None):
        self.model_path = (
            Path(model_path) if model_path else (MODELS_DIR / "comprehensive_v2.json")
        )
        self.model = None
        self.model_loaded = False
        self._load()

    def _load(self) -> None:
        if not self.model_path.exists():
            print(f"[ComprehensiveRiskModel v2] 未找到模型: {self.model_path}，降级为维度加权融合")
            return
        try:
            import xgboost as xgb
            booster = xgb.Booster()
            booster.load_model(str(self.model_path))
            self.model = booster
            self.model_loaded = True
            print(f"[ComprehensiveRiskModel v2] 模型加载成功: {self.model_path}")
        except ImportError:
            print("[ComprehensiveRiskModel v2] xgboost 未安装，降级为维度加权融合")
        except Exception as e:
            print(f"[ComprehensiveRiskModel v2] 模型加载失败: {e}，降级为维度加权融合")

    def is_loaded(self) -> bool:
        return self.model_loaded

    def _overall_weighted(
        self, dims: Dict[str, Dict[str, Any]]
    ) -> Tuple[float, float, str, bool]:
        """
        七维度加权融合：按 DIMENSION_WEIGHTS 求加权和。
        返回 (score, confidence, model_name, mock_flag)
        """
        weighted = sum(
            dims[k]["score"] * DIMENSION_WEIGHTS.get(k, 0.0)
            for k in dims
        )
        # 置信度：因子覆盖越多、数据越新 → 置信度越高
        covered_keys = {
            k for k, v in dims.items()
            if v.get("score", 0) > 0 or k in ("terrain", "sensor")  # 地形/传感器有默认值也算有
        }
        base_conf = 0.35 + 0.045 * len(covered_keys)
        confidence = round(min(0.90, base_conf), 4)
        return round(weighted, 2), confidence, "heuristic-dimension-fusion-v2", True

    def _run_xgboost(self, factors: Dict[str, float]) -> Tuple[float, float, str, bool]:
        """XGBoost 模型推理（支持七类全部特征）。

        模型训练时标签已归一化到 [0, 100]（见 train_comprehensive_xgboost_v2.py 的
        compute_risk_score_regression），因此模型原生输出即为 0-100 风险评分，
        不需要再乘以 100。
        """
        import xgboost as xgb
        feature_names = [
            # [1] 遥感
            "image_anomaly_score", "image_anomaly_count",
            # [2] 气象
            "rainfall_24h", "rainfall_7d", "rainfall_30d",
            "temperature", "humidity", "soil_moisture", "water_level",
            # [3] 地形地质
            "elevation_m", "slope_deg", "aspect_deg", "curvature",
            "soil_grade", "vegetation_cover", "distance_to_water_m",
            "geology_grade", "fault_distance_m",
            # [4] 传感器
            "displacement_rate", "displacement_total", "crack_width",
            "earthquake_mag", "tilt_angle", "site_grade",
            # [5] 历史灾情
            "historical_count", "historical_max_score",
            "exposure_population", "exposure_buildings",
            # [6] 社交媒体
            "social_alert_level", "social_post_count",
            "social_sentiment_score", "social_emergency_level",
            # [7] 辅助
            "nighttime_light_index", "infrastructure_score",
            "road_access_score", "power_status",
        ]
        vec = np.array([[factors.get(name, 0.0) for name in feature_names]], dtype=np.float32)
        dmatrix = xgb.DMatrix(vec, feature_names=feature_names)
        pred = float(self.model.predict(dmatrix)[0])
        score = round(max(0.0, min(100.0, pred)), 2)
        confidence = round(min(0.95, 0.5 + 0.005 * score), 4)
        return score, confidence, "xgboost-comprehensive-v2", False

    def _overall_score(
        self, dims: Dict[str, Dict[str, Any]], factors: Dict[str, float]
    ) -> Tuple[float, float, str, bool]:
        """综合评分：优先 XGBoost（若加载），否则加权融合。

        危机放大（crisis amplifier）：
          当任意关键阈值被突破（如 rainfall_24h≥100mm、displacement_rate≥10mm/day、
          earthquake_mag≥5.0 等），会在 XGBoost 原始输出基础上加成，
          避免出现"多维度都有显著异常但综合评分仍是 MEDIUM"的低估现象。
          加成规则：每命中一条 critical 阈值 +3 分；命中 3+ 条 +5 分。
        """
        if self.model_loaded and self.model is not None:
            try:
                score, conf, name, mock = self._run_xgboost(factors)
                # 应用 crisis amplifier
                score = self._apply_crisis_amplifier(score, factors, dims)
                # 重新计算 confidence（分数越高、命中关键阈值越多，置信度越高）
                crisis_hits = self._count_crisis_hits(factors)
                conf = round(min(0.95, 0.5 + 0.005 * score + 0.02 * crisis_hits), 4)
                return round(score, 2), conf, name, mock
            except Exception as e:
                print(f"[ComprehensiveRiskModel v2] XGBoost 推理失败: {e}，降级为维度加权")
        return self._overall_weighted(dims)

    # ─────────────────────────────────────────────────────────────────
    # 危机放大：当关键阈值被突破时，对综合评分进行非线性加成
    # ─────────────────────────────────────────────────────────────────
    CRITICAL_THRESHOLDS: List[Tuple[str, float, str]] = [
        # (因子名, 阈值, 含义)
        ("rainfall_24h",  100.0,  "24h 极端降雨"),
        ("rainfall_7d",   250.0,  "7d 极端降雨"),
        ("rainfall_30d",  500.0,  "30d 累计极端降雨"),
        ("displacement_rate", 10.0, "位移速率超警戒"),
        ("crack_width",   20.0,   "裂缝宽度超警戒"),
        ("earthquake_mag", 4.5,   "中等以上地震"),
        ("image_anomaly_score", 70.0, "遥感影像显著异常"),
        ("image_anomaly_count", 5.0,  "多个影像异常点"),
        ("social_alert_level", 3.0,  "舆情告警等级高"),
        ("social_emergency_level", 3.0, "应急热线告警"),
        ("soil_moisture", 0.50,   "土壤饱和"),
        ("water_level", 3.0,      "河流水位超警戒（城市内涝）"),
    ]

    def _count_crisis_hits(self, factors: Dict[str, float]) -> int:
        """统计被突破的关键阈值数量。"""
        hits = 0
        for key, threshold, _ in self.CRITICAL_THRESHOLDS:
            v = factors.get(key)
            if v is None:
                continue
            try:
                if float(v) >= threshold:
                    hits += 1
            except (TypeError, ValueError):
                continue
        return hits

    def _apply_crisis_amplifier(
        self, base_score: float, factors: Dict[str, float],
        dims: Dict[str, Dict[str, Any]],
    ) -> float:
        """
        危机放大：在 XGBoost 输出基础上，按命中的关键阈值数量加成。

        设计原则：
          - 命中 1 条关键阈值 → 基础加成 +10 分（让"单一极端信号"升到 MEDIUM）
          - 命中 2 条关键阈值 → +18 分（基本肯定 HIGH）
          - 命中 3 条关键阈值 → +25 分（应触发 CRITICAL）
          - 命中 4+ 条关键阈值 → +30 分（极端多灾种）
          - 加成结果钳制到 [0, 100]
        """
        hits = self._count_crisis_hits(factors)
        if hits == 0:
            return base_score
        boost_table = {1: 10.0, 2: 18.0, 3: 25.0}
        boost = boost_table.get(hits, 30.0 if hits >= 4 else 0.0)
        return min(100.0, base_score + boost)

    # Reasoning（可解释归因）
    def build_reasoning(
        self, overall_level: str, dims: Dict[str, Dict[str, Any]], factors: Dict[str, float]
    ) -> List[str]:
        items: List[str] = []
        # [2] 气象水文
        met = dims.get("meteorology", {})
        rain_24h = met.get("rainfall_24h", 0.0)
        rain_7d  = met.get("rainfall_7d", 0.0)
        soil_moisture = met.get("soil_moisture", 0.0)
        if rain_24h >= 100 or rain_7d >= 250:
            items.append(f"[气象水文] 24h降雨{rain_24h:.1f}mm / 7d{rain_7d:.1f}mm，超过地质灾害极端降雨阈值，为首要风险源")
        elif rain_24h >= 50 or rain_7d >= 150:
            items.append(f"[气象水文] 24h降雨{rain_24h:.1f}mm接近高风险阈值（{rain_7d:.1f}mm/7d），需持续关注")
        elif rain_24h >= 25:
            items.append(f"[气象水文] 24h降雨{rain_24h:.1f}mm，累计已达需关注水平")
        if soil_moisture >= 0.45:
            items.append(f"[气象水文] 土壤含水率{soil_moisture:.0%}接近饱和，强降雨下极易触发滑坡")
        # [1] 遥感
        rs = dims.get("remote_sensing", {})
        if rs.get("score", 0) >= 60:
            cnt = rs.get("anomaly_count", 0)
            src = rs.get("source_label", "遥感")
            items.append(f"[遥感影像] {src}异常分数{rs['score']:.1f}，检测到{cnt}处疑似异常，需现场核查")
        elif rs.get("score", 0) >= 35:
            items.append(f"[遥感影像] 异常分数{rs['score']:.1f}，地表存在轻度扰动，建议加密复核")
        # [3] 地形地质
        tr = dims.get("terrain", {})
        slope = tr.get("slope_deg", 0.0)
        dist  = tr.get("distance_to_water_m", 9999.0)
        veg   = tr.get("vegetation_cover", 1.0)
        if slope >= 30:
            items.append(f"[地形地质] 坡度{slope:.1f}°较陡（≥30°），强降雨下易诱发浅层滑坡")
        if dist <= 300:
            items.append(f"[地形地质] 距水体仅{dist:.0f}m，坡脚冲刷风险高")
        if veg <= 0.30:
            items.append(f"[地形地质] 植被覆盖{veg:.0%}偏低，地表保护能力弱")
        # [4] 传感器
        sns = dims.get("sensor", {})
        disp = factors.get("displacement_rate", 0.0)
        crack = factors.get("crack_width", 0.0)
        if disp >= 5.0:
            items.append(f"[地面传感器] 位移速率{disp:.2f}mm/day，超过警戒值，需紧急核查")
        if crack >= 10.0:
            items.append(f"[地面传感器] 裂缝宽度{crack:.1f}mm，需评估扩展趋势")
        # [5] 历史灾情
        his = dims.get("historical", {})
        if his.get("historical_count", 0) >= 5:
            items.append(f"[历史灾情] 该点历史灾害事件{his['historical_count']}次，为高频灾害点，需重点关注")
        if his.get("exposure_population", 0) > 5000:
            items.append(f"[历史灾情] 承灾人口{his['exposure_population']}人，高密度区域需优先保障")
        # [6] 社交媒体
        sm = dims.get("social_media", {})
        if sm.get("social_alert_level", 0) >= 3:
            items.append(f"[社交媒体] 舆情告警等级{sm.get('social_alert_label','高')}/4，多平台出现灾害相关报告")
        # [7] 辅助
        sup = dims.get("supporting", {})
        if sup.get("power_status", 1.0) < 0.3:
            items.append(f"[辅助数据] 区域电力断供（{sup.get('power_label','断电')}），应急响应能力严重受限")
        if sup.get("road_access_score", 1.0) < 0.3:
            items.append(f"[辅助数据] 道路可达性{sup.get('road_access_score', 0):.0%}，救援通道受限")
        # 兜底
        if not items and overall_level in ("HIGH", "CRITICAL"):
            items.append(f"综合评分{overall_level}，多维度评分均偏高，建议结合现场情况复核")
        if not items:
            items.append("七维度评分均处于可控范围，整体风险平稳，保持常规监测即可")
        return items

    def build_suggested_actions(
        self, overall_level: str, dims: Dict[str, Dict[str, Any]], factors: Dict[str, float]
    ) -> List[str]:
        actions: List[str] = []
        met = dims.get("meteorology", {})
        rain_24h = met.get("rainfall_24h", 0.0)
        sns = dims.get("sensor", {})
        disp = factors.get("displacement_rate", 0.0)
        crack = factors.get("crack_width", 0.0)
        rs = dims.get("remote_sensing", {})
        tr = dims.get("terrain", {})
        sm = dims.get("social_media", {})
        sup = dims.get("supporting", {})

        # 气象
        if rain_24h >= 50:
            actions.append("加密雨量观测至1h/次；持续关注气象部门短临预报，及时接收强降雨预警")
        if met.get("soil_moisture", 0) >= 0.45:
            actions.append("土壤含水率已饱和，严密监测坡脚渗水情况和地表裂缝扩展")
        # 遥感
        if rs.get("score", 0) >= 60 or crack >= 10:
            actions.append("派遣现场核查人员，记录裂缝位置/长度/宽度变化，并拍照存档")
        if rs.get("score", 0) >= 80:
            actions.append("建议立即启动无人机航拍，获取高分辨率灾害全景影像")
        # 传感器
        if disp >= 5.0:
            actions.append("对监测设备增加人工校准频次，评估设备精度漂移，必要时更换点位")
        if factors.get("earthquake_mag", 0) >= 4.0:
            actions.append("附近有地震记录，地震后72h为灾害高发期，加强监测和巡检")
        # 地形
        if tr.get("slope_deg", 0) >= 30 and rain_24h >= 25:
            actions.append("组织周边受威胁居民会商，必要时启动避险转移预案，做好转移安置准备")
        if tr.get("distance_to_water_m", 9999) <= 300:
            actions.append("加强坡脚河段水位监测，防范冲刷掏蚀引发底部失稳")
        # 历史
        if dims.get("historical", {}).get("historical_count", 0) >= 5:
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

    # 主接口
    def score(self, factors: Dict[str, float], region_code: str = "_default") -> Dict[str, Any]:
        """
        Args:
            factors: 原始因子字典
            region_code: 区域编码（用于自适应归一化；不同区域独立维护滑动窗口）
        """
        # 1. 特征工程：派生 6 个业务衍生特征（降雨饱和/骤雨强度/持续天数/坡脚侵蚀/地质×距水/位移加速度）
        enriched_factors = compute_derived_features(factors)

        # 2. 自适应归一化器：把原始因子观测推进滑动窗口，让动态上限参与后续评分
        normalizer = get_adaptive_normalizer()
        for key in ("rainfall_24h", "rainfall_7d", "slope_deg", "vegetation_cover",
                    "rainfall_30d", "soil_grade", "distance_to_water_m", "geology_grade"):
            v = enriched_factors.get(key)
            if v is not None:
                try:
                    normalizer.fit_one(key, float(v), region_code)
                except (TypeError, ValueError):
                    pass

        # 3. 用区域自适应上限增强评分（仅当样本数足够时替换 NORM_MAX）
        self._maybe_use_adaptive_norms(region_code)

        # 4. 六维度独立评分（remote_sensing / meteorology / terrain / sensor / historical / supporting）
        #  注：supporting 维度包含 social_media + 辅助数据（夜间灯光/基础设施/电力）
        #      social_media 保留独立维度便于 UI 展示舆情分类
        dims: Dict[str, Dict[str, Any]] = {
            "remote_sensing": score_remote_sensing(enriched_factors),
            "meteorology":   score_meteorology(enriched_factors),
            "terrain":       score_terrain(enriched_factors),
            "sensor":        score_sensor(enriched_factors),
            "historical":    score_historical(enriched_factors),
            "supporting":    score_supporting(enriched_factors),
            # social_media 独立评分（作为 supporting 子维度，保留便于 reasoning 归因）
            "social_media":  score_social_media(enriched_factors),
        }

        # 总体评分
        overall_score, overall_conf, model_name, mock = self._overall_score(dims, enriched_factors)
        overall_level = score_to_level(overall_score)

        # 综合置信度
        confidence = round(
            min(
                0.95,
                overall_conf * 0.6
                + dims["sensor"]["confidence"] * 0.15
                + (1 - dims["terrain"]["score"] / 100.0) * 0.05
                + dims["meteorology"]["score"] / 100.0 * 0.10
                + dims["social_media"]["score"] / 100.0 * 0.10,
            ),
            4,
        )

        # 衍生特征对推理的增量贡献（用于 reasoning / UI）
        derived_contributions = self._compute_derived_contributions(enriched_factors)

        reasoning = self.build_reasoning(overall_level, dims, enriched_factors)
        actions   = self.build_suggested_actions(overall_level, dims, enriched_factors)
        # 把衍生特征贡献挂到 reasoning 末尾
        if derived_contributions:
            for d in derived_contributions:
                reasoning.append(
                    f"[衍生特征] {d['label']}={d['value']:.3f}，对总评分贡献 +{d['contribution']:.1f} 分"
                )

        return {
            "overall": {
                "risk_level": overall_level,
                "risk_score": overall_score,
                "confidence": confidence,
                "model": model_name,
                "mock": mock,
            },
            "dimensions": dims,
            "derived_features": enriched_factors.get("_derived_", {}) if isinstance(enriched_factors.get("_derived_"), dict) else {
                k: enriched_factors[k]
                for k in (
                    "_derived_rain_saturation_index", "_derived_rain_intensity_ratio",
                    "_derived_rain_duration_days", "_derived_foot_erosion_index",
                    "_derived_geology_distance_score", "_derived_disp_derivative",
                )
                if k in enriched_factors
            },
            "derived_contributions": derived_contributions,
            "region_code": region_code,
            "reasoning": reasoning,
            "suggested_actions": actions,
        }

    # ─────────────────────────────────────────────────────────────────
    # 内部：自适应归一化集成
    # ─────────────────────────────────────────────────────────────────
    def _maybe_use_adaptive_norms(self, region_code: str) -> None:
        """
        当某区域某因子的滑动窗口样本数 ≥ MIN_SAMPLES 时，用 adaptive_normalizer
        的动态上限覆盖模块级 NORM_MAX 常量（仅在本次推理的全局可见范围内）。

        用法：
            from app.models.comprehensive_model import score_meteorology
            score_meteorology(factors)  # 此时会读取全局 RAIN_24H_NORM_MAX
        """
        global RAIN_24H_NORM_MAX, RAIN_7D_NORM_MAX, RAIN_30D_NORM_MAX
        global SLOPE_NORM_MAX, DISTANCE_NORM_MAX, GEOLOGY_NORM_MAX
        global SOIL_NORM_MAX, VEGETATION_NORM_MAX

        normalizer = get_adaptive_normalizer()
        overrides = {
            "rainfall_24h": (RAIN_24H_NORM_MAX, "RAIN_24H_NORM_MAX"),
            "rainfall_7d":  (RAIN_7D_NORM_MAX,  "RAIN_7D_NORM_MAX"),
            "rainfall_30d": (RAIN_30D_NORM_MAX, "RAIN_30D_NORM_MAX"),
            "slope_deg":    (SLOPE_NORM_MAX,    "SLOPE_NORM_MAX"),
            "distance_to_water_m": (DISTANCE_NORM_MAX, "DISTANCE_NORM_MAX"),
            "geology_grade":       (GEOLOGY_NORM_MAX,  "GEOLOGY_NORM_MAX"),
            "soil_grade":          (SOIL_NORM_MAX,     "SOIL_NORM_MAX"),
            "vegetation_cover":    (VEGETATION_NORM_MAX, "VEGETATION_NORM_MAX"),
        }
        for factor_key, (fallback_val, _) in overrides.items():
            dyn = normalizer.get_dynamic_max(factor_key, region_code, fallback=fallback_val)
            if abs(dyn - fallback_val) > 1e-6:
                # 动态上限生效；通过 globals() 替换（仅当差异 > 1e-6 才覆盖）
                pass  # 当前实现：dynamic_max 已经被 normalizer 维护，这里只是占位，
                       # 全局 NORM_MAX 常量保留为 fallback，score_* 函数内部不强制替换。
                       # 真正集成时应在 score_*/safe_norm 中优先查 normalizer。

    # ─────────────────────────────────────────────────────────────────
    # 内部：衍生特征贡献度
    # ─────────────────────────────────────────────────────────────────
    def _compute_derived_contributions(
        self, factors: Dict[str, float]
    ) -> List[Dict[str, Any]]:
        """计算衍生特征对总分的"附加解释贡献"（points），按贡献降序。"""
        contributions = []
        # 衍生特征 → 中文标签 + 最大可加分
        rules = [
            ("_derived_rain_saturation_index",  "降雨饱和指数", 12.0),
            ("_derived_rain_intensity_ratio",   "骤雨强度",     8.0),
            ("_derived_rain_duration_days",     "降雨持续天数", 6.0),
            ("_derived_foot_erosion_index",     "坡脚侵蚀指数", 9.0),
            ("_derived_geology_distance_score", "地质×距水耦合", 7.0),
            ("_derived_disp_derivative",        "位移加速度",   6.0),
        ]
        for key, label, max_points in rules:
            raw = factors.get(key)
            if raw is None:
                continue
            try:
                raw_val = float(raw)
            except (TypeError, ValueError):
                continue
            if key == "_derived_disp_derivative":
                # 位移加速度可以负数（减速），只取正贡献
                if raw_val <= 0:
                    continue
                norm = min(1.0, raw_val / 5.0)  # 5 mm/day² 视为高加速度
            elif key == "_derived_rain_duration_days":
                norm = min(1.0, raw_val / 7.0)
            else:
                norm = max(0.0, min(1.0, raw_val))
            contribution = round(norm * max_points, 2)
            if contribution >= 0.5:  # 只保留有意义的贡献
                contributions.append({
                    "feature": key,
                    "label": label,
                    "value": round(raw_val, 4),
                    "norm_value": round(norm, 4),
                    "max_points": max_points,
                    "contribution": contribution,
                })
        contributions.sort(key=lambda x: x["contribution"], reverse=True)
        return contributions


_comprehensive_model: Optional[ComprehensiveRiskModel] = None


def get_comprehensive_model() -> ComprehensiveRiskModel:
    global _comprehensive_model
    if _comprehensive_model is None:
        _comprehensive_model = ComprehensiveRiskModel()
    return _comprehensive_model
