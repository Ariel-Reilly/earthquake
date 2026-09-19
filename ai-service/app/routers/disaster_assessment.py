"""
地质灾害 AI 评估路由 v2.0：POST /api/ai/disaster-assessment/score
对应 Java DisasterAssessmentRequestDto / DisasterAssessmentResponseDto。

v2.0 升级——七类数据源全量支持：
  [1] 遥感影像（Remote Sensing）：image_anomaly_score / image_anomaly_count / image_source
  [2] 气象水文（Meteorological）：rainfall_24h / rainfall_7d / rainfall_30d / temperature / humidity / soil_moisture / water_level
  [3] 地形地质（Terrain & Geology）：elevation_m / slope_deg / aspect_deg / curvature / soil_grade / vegetation_cover / distance_to_water_m / geology_grade / geology_label / fault_distance_m
  [4] 地面传感器/IoT（Sensor）：displacement_rate / displacement_total / crack_width / earthquake_mag / tilt_angle / site_grade
  [5] 历史灾情与承灾体（Historical）：historical_count / historical_max_score / exposure_population / exposure_buildings / land_use_type
  [6] 社交媒体与文本（Social Media）：social_alert_level / social_post_count / social_sentiment_score / social_emergency_level
  [7] 辅助数据（Supporting）：nighttime_light_index / infrastructure_score / road_access_score / power_status

特点：
  - **不强制依赖任何单一维度**：任何维度因子缺失时，AI 模型自动调整权重并输出有效结果；
  - **七维度独立评分**：返回 dimensions.{remote_sensing / meteorology / terrain / sensor / historical / social_media / supporting}；
  - - 输出 concern_label（稳定/关注/预警/异常严重）+ disaster_type（六大类型）+ reasoning + suggested_actions；
  - 兼容 v1.x：dimensions 中保留 geographic / precipitation / device（含 _v1 兼容字段）；

支持方法：
  POST /api/ai/disaster-assessment/score           单次评估（v2.0 七维度）
  POST /api/ai/disaster-assessment/detect-batch   批量评估（Java 调度器调用，最多 64 点）
  POST /api/ai/disaster-assessment/detect-regions 按 region 派生网格 + 批量检测
  POST /api/ai/disaster-assessment/independent    全国独立测算（不依赖设备/已知档案）
  GET  /api/ai/disaster-assessment/status          模型/字典自检
"""
from __future__ import annotations

from typing import Any, Dict, List, Optional, Tuple

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field, conlist

from app.models.disaster_assessment_model import (
    CONCERN_LABEL_MAP,
    SUPPORTED_DISASTER_TYPES,
    get_disaster_assessment_model,
)

router = APIRouter(prefix="/api/ai", tags=["disaster-assessment"])


class HazardMeta(BaseModel):
    hazard_code: str = Field(min_length=1, description="灾害点编码")
    hazard_type: Optional[str] = Field(
        default=None,
        description="灾害点档案已标注的灾害类型：滑坡 / 崩塌 / 泥石流 / 地裂缝 / 地面沉降 / 塌陷",
    )


class DisasterAssessmentRequest(BaseModel):
    hazard: HazardMeta = Field(description="灾害点基础元数据（用于类型分类 + 标识）")
    factors: Dict[str, float] = Field(
        default_factory=dict,
        description=(
            "评估因子（七类数据源全部可选，有则用，无则默认 0/中性）：\n"
            "  [1] 遥感影像: image_anomaly_score / image_anomaly_count / image_source\n"
            "  [2] 气象水文: rainfall_24h / rainfall_7d / rainfall_30d / temperature / humidity / soil_moisture / water_level\n"
            "  [3] 地形地质: elevation_m / slope_deg / aspect_deg / curvature / soil_grade / vegetation_cover / distance_to_water_m / geology_grade / geology_label / fault_distance_m\n"
            "  [4] 传感器: displacement_rate / displacement_total / crack_width / earthquake_mag / tilt_angle / site_grade\n"
            "  [5] 历史灾情: historical_count / historical_max_score / exposure_population / exposure_buildings / land_use_type\n"
            "  [6] 社交媒体: social_alert_level / social_post_count / social_sentiment_score / social_emergency_level\n"
            "  [7] 辅助数据: nighttime_light_index / infrastructure_score / road_access_score / power_status"
        ),
    )


class DisasterAssessmentResponse(BaseModel):
    hazard_code: str
    overall: Dict[str, Any]
    disaster_type: Dict[str, Any]
    dimensions: Dict[str, Any]
    reasoning: list
    suggested_actions: list
    model: str
    mock: bool
    device_available: bool
    message: str = "ok"


# ===== v1.6.0：批量灾害监测（AiHazardMonitorScheduler 调用） =====


class MonitoringPoint(BaseModel):
    """单个监控点 v2.0：支持七类数据源全部字段。"""

    lon: float = Field(description="WGS-84 经度")
    lat: float = Field(description="WGS-84 纬度")
    region_code: str = Field(description="行政区代码（与 Java region 表对应，如 100101）")

    # ---- [1] 遥感影像（可选：设备相机/卫星分析输出）----
    image_anomaly_score: Optional[float] = Field(default=None, description="图像异常分数 0-100")
    image_source: Optional[str] = Field(default=None, description="图像来源：camera / drone / sentinel2 / landsat / radar")

    # ---- [2] 气象水文（主要来源：weather 字典覆盖）----
    weather: Dict[str, float] = Field(
        default_factory=dict,
        description=(
            "实时天气快照：rainfall_24h / rainfall_7d / rainfall_30d / "
            "temperature / humidity / soil_moisture / water_level"
        ),
    )

    # ---- [3] 地形地质（可选：DEM/地质图输出）----
    elevation_m: Optional[float] = Field(default=None, description="海拔（米）")
    slope_deg: Optional[float] = Field(default=None, description="坡度（度）")
    aspect_deg: Optional[float] = Field(default=None, description="坡向（度，0-360）")
    curvature: Optional[float] = Field(default=None, description="地形曲率")
    soil_grade: Optional[float] = Field(default=None, description="土壤等级 1-5")
    vegetation_cover: Optional[float] = Field(default=None, description="植被覆盖度 0-1")
    distance_to_water_m: Optional[float] = Field(default=None, description="距最近水体距离（米）")
    geology_grade: Optional[float] = Field(default=None, description="地质脆弱性 1-5")
    geology_label: Optional[str] = Field(default=None, description="地质岩性标签")
    fault_distance_m: Optional[float] = Field(default=None, description="距最近活动断层距离（米）")

    # ---- [4] 传感器（可选：接入设备才有）----
    displacement_rate: Optional[float] = Field(default=None, description="位移速率 mm/day")
    crack_width: Optional[float] = Field(default=None, description="裂缝宽度 mm")

    # ---- [5] 历史灾情与承灾体（可选：hazar_point 档案有）----
    historical_count: Optional[int] = Field(default=None, description="历史灾害事件数")
    exposure_population: Optional[int] = Field(default=None, description="承灾人口")
    land_use_type: Optional[str] = Field(default=None, description="土地利用类型：urban/cropland/forest/water/barren")

    # ---- [6] 社交媒体（可选：舆情 API 有）----
    social_alert_level: Optional[float] = Field(default=None, description="舆情告警等级 0-4")
    social_post_count: Optional[int] = Field(default=None, description="相关帖文数")
    social_sentiment_score: Optional[float] = Field(default=None, description="情感极性 -1~1")

    # ---- [7] 辅助数据（可选：NASA/OSM/国家电网有）----
    nighttime_light_index: Optional[float] = Field(default=None, description="夜间灯光指数 0-63")
    infrastructure_score: Optional[float] = Field(default=None, description="基础设施可达性 0-1")
    road_access_score: Optional[float] = Field(default=None, description="道路可达性 0-1")
    power_status: Optional[float] = Field(default=None, description="电力状态 0-1")


class DetectBatchRequest(BaseModel):
    points: List[MonitoringPoint] = Field(
        description="监控点列表（最多 64 个，超过会被截断）",
        max_length=64,
    )
    # 仅返回风险达到阈值的检测结果，避免噪声污染前端
    min_risk_level: str = Field(
        default="HIGH",
        description="HIGH / MEDIUM / LOW，仅返回 ≥ 该等级的检测",
    )


class DetectionDto(BaseModel):
    """单个被识别为高风险的监控点完整信息。"""

    region_code: str
    lon: float
    lat: float
    hazard_code: str
    hazard_type: str
    risk_level: str
    risk_score: float
    concern_label: str
    confidence: float
    detection_confidence: float
    reasoning: List[str]
    suggested_actions: List[str]
    model: str


class DetectBatchResponse(BaseModel):
    total_points: int
    detected_count: int
    detections: List[DetectionDto]
    execution_ms: int


def _factors_from_point(p: MonitoringPoint) -> Dict[str, float]:
    """把 MonitoringPoint v2.0 装成 DisasterAssessmentModel.score 期望的 factors 表。

    设计原则：base 因子填默认值避免 normalize 分母偏 0；真实因子从 p 字段覆盖。
    任何维度缺失 → AI 模型自动走容错路径，保证返回有效结果。
    """
    factors: Dict[str, float] = {}

    # ---- [1] 遥感影像 ----
    if p.image_anomaly_score is not None:
        factors["image_anomaly_score"] = float(p.image_anomaly_score)
    if p.image_source is not None:
        factors["image_source"] = 0.0  # 字符串，AI 端用默认处理

    # ---- [2] 气象水文（weather 字典 + 直接字段）----
    weather = p.weather or {}
    factors.setdefault("rainfall_24h", 0.0)
    factors.setdefault("rainfall_7d",  0.0)
    factors.setdefault("rainfall_30d", 0.0)
    factors.setdefault("temperature", 20.0)
    factors.setdefault("humidity", 0.5)
    factors.setdefault("soil_moisture", 0.3)
    factors.setdefault("water_level", 0.0)
    for k, v in weather.items():
        if k in ("rainfall_24h", "rainfall_7d", "rainfall_30d", "temperature", "humidity", "soil_moisture", "water_level"):
            factors[k] = float(v)
        elif k == "rain_1h" and "rainfall_24h" not in weather:
            factors["rainfall_24h"] = float(v) * 24.0

    # ---- [3] 地形地质 ----
    factors.setdefault("elevation_m", 800.0)
    factors.setdefault("slope_deg", 25.0)
    factors.setdefault("aspect_deg", 180.0)
    factors.setdefault("curvature", 0.0)
    factors.setdefault("soil_grade", 3.0)
    factors.setdefault("vegetation_cover", 0.5)
    factors.setdefault("distance_to_water_m", 1500.0)
    factors.setdefault("geology_grade", 3.0)
    factors.setdefault("fault_distance_m", 5000.0)
    if p.elevation_m is not None:           factors["elevation_m"] = float(p.elevation_m)
    if p.slope_deg is not None:             factors["slope_deg"] = float(p.slope_deg)
    if p.aspect_deg is not None:            factors["aspect_deg"] = float(p.aspect_deg)
    if p.curvature is not None:             factors["curvature"] = float(p.curvature)
    if p.soil_grade is not None:            factors["soil_grade"] = float(p.soil_grade)
    if p.vegetation_cover is not None:      factors["vegetation_cover"] = float(p.vegetation_cover)
    if p.distance_to_water_m is not None:   factors["distance_to_water_m"] = float(p.distance_to_water_m)
    if p.geology_grade is not None:         factors["geology_grade"] = float(p.geology_grade)
    if p.fault_distance_m is not None:      factors["fault_distance_m"] = float(p.fault_distance_m)

    # ---- [4] 地面传感器 ----
    if p.displacement_rate is not None: factors["displacement_rate"] = float(p.displacement_rate)
    if p.crack_width is not None:        factors["crack_width"] = float(p.crack_width)

    # ---- [5] 历史灾情与承灾体 ----
    if p.historical_count is not None:      factors["historical_count"] = float(p.historical_count)
    if p.exposure_population is not None:   factors["exposure_population"] = float(p.exposure_population)
    if p.land_use_type is not None:        factors["land_use_type"] = 0.0  # 字符串

    # ---- [6] 社交媒体 ----
    if p.social_alert_level is not None:     factors["social_alert_level"] = float(p.social_alert_level)
    if p.social_post_count is not None:      factors["social_post_count"] = float(p.social_post_count)
    if p.social_sentiment_score is not None: factors["social_sentiment_score"] = float(p.social_sentiment_score)

    # ---- [7] 辅助数据 ----
    if p.nighttime_light_index is not None: factors["nighttime_light_index"] = float(p.nighttime_light_index)
    if p.infrastructure_score is not None:   factors["infrastructure_score"] = float(p.infrastructure_score)
    if p.road_access_score is not None:     factors["road_access_score"] = float(p.road_access_score)
    if p.power_status is not None:          factors["power_status"] = float(p.power_status)

    return factors


_RISK_ORDER = {"LOW": 1, "MEDIUM": 2, "HIGH": 3, "CRITICAL": 4}


@router.post("/disaster-assessment/detect-batch", response_model=DetectBatchResponse)
async def detect_batch(req: DetectBatchRequest):
    """批量灾害监测：Java 端 AiHazardMonitorScheduler 每 90s 调一次。

    流程：
      1) 对每个监控点构建 factors（地理默认 + 天气覆盖）；
      2) 跑 DisasterAssessmentModel.score();
      3) 仅保留 risk_level ≥ min_risk_level 的检测（默认 HIGH 阈值）；
      4) 返回完整 DetectionDto 列表，Java 端持久化到 hazard_point 表。

    性能：每点 ~5ms，64 点 ~320ms，整体 < 1s。
    """
    import time

    threshold = _RISK_ORDER.get(req.min_risk_level.upper(), 3)
    t0 = time.perf_counter()
    model = get_disaster_assessment_model()
    detections: List[DetectionDto] = []

    for idx, point in enumerate(req.points):
        factors = _factors_from_point(point)
        hazard_dict = {
            "hazard_code": f"MON-{idx:03d}",
            "hazard_type": None,  # 让 AI 自己推断
        }
        try:
            result = model.score(hazard_dict, factors)
        except Exception as exc:  # 单点失败不应阻断整批
            continue

        overall = result["overall"]
        risk_level = overall.get("risk_level", "LOW")
        if _RISK_ORDER.get(risk_level, 0) < threshold:
            continue

        dt = result.get("disaster_type", {})
        hazard_type = (
            dt.get("disaster_type")
            or dt.get("disasterType")
            or "其他"
        )
        detections.append(
            DetectionDto(
                region_code=point.region_code,
                lon=point.lon,
                lat=point.lat,
                hazard_code=hazard_dict["hazard_code"],
                hazard_type=hazard_type if hazard_type in SUPPORTED_DISASTER_TYPES else "其他",
                risk_level=risk_level,
                risk_score=float(overall.get("risk_score", 0.0)),
                concern_label=overall.get("concern_label", "稳定"),
                confidence=float(overall.get("confidence", 0.0)),
                detection_confidence=float(overall.get("confidence", 0.0)),
                reasoning=result.get("reasoning", []) or [],
                suggested_actions=result.get("suggested_actions", []) or [],
                model=overall.get("model", "unknown"),
            )
        )

    elapsed_ms = int((time.perf_counter() - t0) * 1000)
    return DetectBatchResponse(
        total_points=len(req.points),
        detected_count=len(detections),
        detections=detections,
        execution_ms=elapsed_ms,
    )


class DetectRegionsRequest(BaseModel):
    """按区域生成监控网格 + 批量检测。

    v1.7.0：优先从 region_centers 映射读取经纬度（Java 调度器从 region 表加载）。
    若 region_centers 为空则降级到 _DEFAULT_REGION_CENTERS（仅 demo 用，生产请配置 region 表）。
    """

    region_codes: List[str] = Field(
        description="区域代码列表（如 ['100101', '100201']）"
    )
    # v1.7.0：可选的区域中心坐标映射。key=region_code，value=(center_lon, center_lat)
    # 若传则优先使用；为空则回退到 _DEFAULT_REGION_CENTERS
    region_centers: Optional[Dict[str, Tuple[float, float]]] = Field(
        default=None,
        description="region_code → (center_lon, center_lat) 映射",
    )
    # 网格步长（度）：默认 0.02° ≈ 2.2 km（中国中纬度），网格越密 → 监测越准但越慢
    grid_step_deg: float = Field(default=0.02, ge=0.005, le=0.5)
    weather: Dict[str, float] = Field(
        default_factory=dict,
        description="统一天气快照（应用到所有网格点）",
    )
    min_risk_level: str = Field(default="HIGH")


class DetectRegionsResponse(BaseModel):
    region_codes: List[str]
    total_points: int
    detected_count: int
    detections: List[DetectionDto]
    grid_step_deg: float
    execution_ms: int


# -------------------------------------------------------------------------------------------------
# v1.7.0 降级用：仅当 region_centers 未传入时作为兜底（仅 demo/测试用，不保证覆盖全国）
# 生产环境请通过 Java 调度器从 region 表加载 center_lon/center_lat 并通过 region_centers 传入
# -------------------------------------------------------------------------------------------------
_DEFAULT_REGION_CENTERS: Dict[str, Tuple[float, float]] = {
    "100000": (107.34, 30.55),  # 龙川县
    "100100": (107.21, 30.61),  # 龙安镇
    "100101": (107.21, 30.61),  # 龙安镇青龙村
    "100102": (107.22, 30.62),  # 龙安镇石桥村
    "100200": (107.35, 30.48),  # 石桥镇
    "100201": (107.34, 30.48),  # 石桥镇大岭村
    "100202": (107.36, 30.49),  # 石桥镇云台村
    "100300": (107.58, 30.45),  # 清河镇
    "100301": (107.55, 30.42),  # 清河镇白石沟村
    "100302": (107.61, 30.67),  # 清河镇云台山村
    "100400": (107.42, 30.52),  # 清溪镇
    "100401": (107.14, 30.56),  # 清溪镇石门沟村
}


@router.post("/disaster-assessment/detect-regions", response_model=DetectRegionsResponse)
async def detect_regions(req: DetectRegionsRequest):
    """按区域中心派生 3x3 网格点 + 批量检测。

    v1.7.0 改造：
      - 优先从请求的 region_centers 映射读取坐标（Java 调度器从 region 表加载）；
      - region_centers 为空时降级到 _DEFAULT_REGION_CENTERS（仅 demo 用）；
      - 每个 region 生成 9 个网格点（中心 + 八方位）。
    """
    import time

    t0 = time.perf_counter()
    centers = req.region_centers if req.region_centers else _DEFAULT_REGION_CENTERS
    points: List[MonitoringPoint] = []
    step = float(req.grid_step_deg)
    for code in req.region_codes:
        center = centers.get(code)
        if center is None:
            # 完全未知的 region：跳过（不降级到默认值，避免坐标漂移到错误地区）
            continue
        cx, cy = center
        for dx in (-step, 0.0, step):
            for dy in (-step, 0.0, step):
                points.append(
                    MonitoringPoint(
                        lon=cx + dx,
                        lat=cy + dy,
                        region_code=code,
                        weather=dict(req.weather or {}),
                    )
                )

    # 复用 detect-batch 的核心逻辑
    inner = await detect_batch(
        DetectBatchRequest(points=points, min_risk_level=req.min_risk_level)
    )
    elapsed_ms = int((time.perf_counter() - t0) * 1000)
    return DetectRegionsResponse(
        region_codes=req.region_codes,
        total_points=len(points),
        detected_count=inner.detected_count,
        detections=inner.detections,
        grid_step_deg=step,
        execution_ms=elapsed_ms,
    )


@router.post("/disaster-assessment/score", response_model=DisasterAssessmentResponse)
async def disaster_assessment_score(req: DisasterAssessmentRequest):
    factors = dict(req.factors or {})
    if not factors:
        raise HTTPException(
            status_code=400,
            detail=(
                "factors 不能为空；至少需要 elevation_m / slope_deg / rainfall_24h "
                "等核心地理/天气因子中的一项"
            ),
        )
    model = get_disaster_assessment_model()
    hazard_dict = {"hazard_code": req.hazard.hazard_code, "hazard_type": req.hazard.hazard_type}
    result = model.score(hazard_dict, factors)
    return DisasterAssessmentResponse(
        hazard_code=req.hazard.hazard_code,
        overall=result["overall"],
        disaster_type=result["disaster_type"],
        dimensions=result["dimensions"],
        reasoning=result["reasoning"],
        suggested_actions=result["suggested_actions"],
        model=result["overall"]["model"],
        mock=result["overall"]["mock"],
        device_available=result["overall"]["device_available"],
    )


@router.get("/disaster-assessment/status")
async def disaster_assessment_status():
    """自检：暴露支持的灾害类型字典、concern_label 映射、权重。"""
    from app.models.disaster_assessment_model import BASE_WEIGHTS

    m = get_disaster_assessment_model()
    return {
        "model_loaded": True,
        "disaster_types": SUPPORTED_DISASTER_TYPES,
        "concern_label_map": CONCERN_LABEL_MAP,
        "base_weights": BASE_WEIGHTS,
        "device_present_weights": {"geographic": 0.50, "precipitation": 0.40, "device": 0.10},
        "device_absent_weights": {"geographic": 0.55, "precipitation": 0.45, "device": 0.0},
        "type_hint_count": {k: len(v) for k, v in m.TYPE_FEATURES.items()},
    }


# ================================================================================================
# v1.7.0：独立测算路由 —— 不依赖接入设备，不依赖已知灾害点档案，
#   只需传入坐标 + 天气（或仅坐标，AI 自动估算天气），返回完整七维度报告。
#   支持中国任意经纬度（支持 lat∈[18,54]、lon∈[73,135] 范围）。
# ================================================================================================


class IndependentPoint(BaseModel):
    """独立测算的单个监控点：仅需经纬度和可选的天气/地理因子。"""

    lon: float = Field(description="WGS-84 经度（73-135°）", ge=73.0, le=135.0)
    lat: float = Field(description="WGS-84 纬度（18-54°）", ge=18.0, le=54.0)
    region_code: Optional[str] = Field(
        default=None,
        description="行政区划代码（可选；用于自适应归一化区域标识）",
    )
    region_name: Optional[str] = Field(
        default=None,
        description="行政区划名称（可选；仅用于响应报告展示）",
    )
    # ---- 可选天气因子 ----
    rainfall_24h: Optional[float] = Field(
        default=None, description="过去 24 小时累计降雨量（mm）"
    )
    rainfall_7d: Optional[float] = Field(
        default=None, description="过去 7 天累计降雨量（mm）"
    )
    temperature: Optional[float] = Field(
        default=None, description="当前气温（℃）"
    )
    humidity: Optional[float] = Field(
        default=None, description="相对湿度（0.0-1.0）"
    )
    # ---- 可选地理因子 ----
    elevation_m: Optional[float] = Field(
        default=None, description="海拔（m）"
    )
    slope_deg: Optional[float] = Field(
        default=None, description="地面坡度（°）"
    )
    soil_grade: Optional[float] = Field(
        default=None, description="土壤易蚀性等级（1-5，越大越易蚀）"
    )
    vegetation_cover: Optional[float] = Field(
        default=None, description="植被覆盖度（0.0-1.0）"
    )
    distance_to_water_m: Optional[float] = Field(
        default=None, description="距最近水体距离（m）"
    )
    geology_grade: Optional[float] = Field(
        default=None, description="地质条件等级（1-5，越大越脆弱）"
    )
    geology_label: Optional[str] = Field(
        default=None, description="地质岩性标签（如「碳酸盐岩」「碎屑岩」）"
    )


class IndependentBatchRequest(BaseModel):
    """独立测算批量请求：支持最多 32 个点（detect-batch 是 64，但独立测算权重更高，限 32）。"""

    points: List[IndependentPoint] = Field(
        description="监控点列表（最多 32 个，超过会被截断）",
        max_length=32,
    )
    min_risk_level: str = Field(
        default="MEDIUM",
        description="HIGH / MEDIUM / LOW，返回 ≥ 该等级的检测（独立测算默认 MEDIUM 以便展示关注/预警）",
    )
    mock_weather: bool = Field(
        default=True,
        description="当 points 中未提供天气因子时，是否自动用季节性模拟值填充（默认 true）",
    )


class IndependentResult(BaseModel):
    """独立测算单个结果。"""

    lon: float
    lat: float
    region_code: Optional[str]
    region_name: Optional[str]
    # 七维度核心输出
    risk_level: str        # LOW / MEDIUM / HIGH / CRITICAL
    risk_score: float
    concern_label: str      # 稳定 / 关注 / 预警 / 异常严重
    disaster_type: str      # 滑坡 / 崩塌 / 泥石流 / 地裂缝 / 地面沉降 / 塌陷 / 待定
    confidence: float
    # 明细
    geographic_score: float
    precipitation_score: float
    device_available: bool
    reasoning: List[str]
    suggested_actions: List[str]
    model: str
    mock_weather_used: bool


class IndependentBatchResponse(BaseModel):
    total_points: int
    detected_count: int
    results: List[IndependentResult]
    execution_ms: int


def _independent_factors_from_point(p: IndependentPoint, use_mock_weather: bool) -> Tuple[Dict[str, float], bool]:
    """从 IndependentPoint 构建 factors 字典，并返回是否使用了模拟天气。"""
    factors: Dict[str, float] = {}
    used_mock = False

    # 地理因子（全部可选，有就用，没有则用模型默认值）
    if p.elevation_m is not None:
        factors["elevation_m"] = float(p.elevation_m)
    if p.slope_deg is not None:
        factors["slope_deg"] = float(p.slope_deg)
    if p.soil_grade is not None:
        factors["soil_grade"] = float(p.soil_grade)
    if p.vegetation_cover is not None:
        factors["vegetation_cover"] = float(p.vegetation_cover)
    if p.distance_to_water_m is not None:
        factors["distance_to_water_m"] = float(p.distance_to_water_m)
    if p.geology_grade is not None:
        factors["geology_grade"] = float(p.geology_grade)
    if p.geology_label is not None:
        factors["geology_label"] = str(p.geology_label)

    # 天气因子
    if p.rainfall_24h is not None:
        factors["rainfall_24h"] = float(p.rainfall_24h)
    if p.rainfall_7d is not None:
        factors["rainfall_7d"] = float(p.rainfall_7d)

    # 若天气全缺失且允许 mock，用季节性模拟值
    if use_mock_weather and "rainfall_24h" not in factors and "rainfall_7d" not in factors:
        from datetime import datetime
        day_of_year = datetime.now().timetuple().tm_yday
        seasonal = math.sin((day_of_year - 80) / 365.0 * 2 * math.pi)
        rain24 = max(0.0, 25.0 + seasonal * 80) if seasonal > 0 else seasonal * 15
        rain7 = max(0.0, 80.0 + seasonal * 220) if seasonal > 0 else seasonal * 50
        factors["rainfall_24h"] = rain24
        factors["rainfall_7d"] = rain7
        used_mock = True

    return factors, used_mock


_RISK_ORDER_INDEP = {"LOW": 1, "MEDIUM": 2, "HIGH": 3, "CRITICAL": 4}


@router.post("/disaster-assessment/independent", response_model=IndependentBatchResponse)
async def disaster_assessment_independent(req: IndependentBatchRequest):
    """v1.7.0 独立测算端点：不依赖接入设备/已知灾害点档案，仅需坐标+可选因子。

    典型用途：
      - 运维/值班员在全国任意位置快速评估风险；
      - 对接外部系统（如气象预警联动）直接推入坐标获取 AI 研判；
      - 离线/无设备环境下依然能输出关注/预警/异常严重的七维度结论。

    请求示例（单个点）：
      POST /api/ai/disaster-assessment/independent
      {
        "points": [{
          "lon": 107.34, "lat": 30.55,
          "region_code": "510000",
          "region_name": "四川省",
          "rainfall_24h": 85.0,
          "rainfall_7d": 220.0
        }],
        "min_risk_level": "MEDIUM",
        "mock_weather": true
      }

    响应：
      {
        "total_points": 1,
        "detected_count": 1,   // ≥ min_risk_level 的点数
        "results": [{ risk_level, concern_label, disaster_type, reasoning, ... }],
        "execution_ms": 45
      }
    """
    import time

    t0 = time.perf_counter()
    points = req.points[:32]  # 截断到 32
    threshold = _RISK_ORDER_INDEP.get(req.min_risk_level.upper(), 2)
    model = get_disaster_assessment_model()
    results: List[IndependentResult] = []

    for idx, pt in enumerate(points):
        region_code = pt.region_code or f"_indep_{idx}"
        factors, used_mock = _independent_factors_from_point(pt, req.mock_weather)
        hazard_dict = {
            "hazard_code": f"IND-{idx:04d}",
            "hazard_type": None,
            "region_code": region_code,
        }
        try:
            result = model.score(hazard_dict, factors)
        except Exception:
            continue

        overall = result["overall"]
        risk_level = overall.get("risk_level", "LOW")
        # 独立测算总是返回结果（不过滤），detected_count 只统计 ≥ 阈值的
        dims = result.get("dimensions", {})
        geo_score = dims.get("geographic", {}).get("score", 0.0)
        prec_score = dims.get("precipitation", {}).get("score", 0.0)
        dt = result.get("disaster_type", {})
        disaster_type = (
            dt.get("disaster_type")
            or dt.get("disasterType")
            or "待定"
        )

        results.append(IndependentResult(
            lon=round(pt.lon, 6),
            lat=round(pt.lat, 6),
            region_code=pt.region_code,
            region_name=pt.region_name,
            risk_level=risk_level,
            risk_score=float(overall.get("risk_score", 0.0)),
            concern_label=overall.get("concern_label", "未知"),
            disaster_type=disaster_type,
            confidence=float(overall.get("confidence", 0.0)),
            geographic_score=float(geo_score),
            precipitation_score=float(prec_score),
            device_available=overall.get("device_available", False),
            reasoning=result.get("reasoning", []) or [],
            suggested_actions=result.get("suggested_actions", []) or [],
            model=overall.get("model", "unknown"),
            mock_weather_used=used_mock,
        ))

    detected_count = sum(
        1 for r in results
        if _RISK_ORDER_INDEP.get(r.risk_level, 0) >= threshold
    )
    elapsed_ms = int((time.perf_counter() - t0) * 1000)
    return IndependentBatchResponse(
        total_points=len(results),
        detected_count=detected_count,
        results=results,
        execution_ms=elapsed_ms,
    )
