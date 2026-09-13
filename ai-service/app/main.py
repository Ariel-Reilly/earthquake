import os
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.routers import (
    anomaly,
    comprehensive,
    detection,
    device_health,
    disaster_assessment,
    feedback,
    forecast,
    fusion,
    model_info,
    sentinel,
    social_media,
)

app = FastAPI(
    title="GeoSentry AI Service v3.0",
    description=(
        "地质灾害智能巡检预警 AI 推理服务 v3.0 —— 多源数据融合体系：\n\n"
        "一、核心遥感影像数据（用于识别地表灾害特征）\n"
        "  - Sentinel-1 SAR（C波段雷达）：洪水、滑坡、地表形变（InSAR）、地震同震位移\n"
        "  - Sentinel-2 多光谱：植被指数、水体提取、火灾迹地、干旱监测\n"
        "  - Landsat 8/9：长期土地覆盖变化、历史对比\n"
        "  - MODIS / VIIRS：大范围火点、气溶胶、洪水快速初判\n"
        "  - 高分辨率商业（可选）：建筑物损毁评估、精细滑坡边界\n\n"
        "二、气象与水文数据（用于触发条件分析和预警）\n"
        "  - ERA5 再分析：降雨、温度、风速、湿度、气压（0.25° 逐小时）\n"
        "  - GPM IMERG：全球卫星降雨（0.1° 每30分钟）\n"
        "  - SMAP 土壤湿度：干旱监测、滑坡前期土壤饱和度\n"
        "  - 河流水位（可选）：洪水演进验证\n\n"
        "三、地形与地质数据（用于易发性评估和特征提取）\n"
        "  - SRTM DEM：坡度、坡向、汇流分析、地形湿度指数（30m）\n"
        "  - Copernicus DEM：更高精度地形分析（30m/10m）\n"
        "  - 地质图/断层数据：地震、滑坡易发性因子\n"
        "  - 土壤类型：渗透性、滑坡敏感性\n\n"
        "四、历史灾害与承灾体数据（用于模型训练和风险评估）\n"
        "  - EM-DAT：全球灾害事件库（模型训练标签、风险区划）\n"
        "  - GDACS：全球灾害警报和影响评估（实时触发）\n"
        "  - Copernicus EMS：重大灾害快速测绘产品\n"
        "  - WorldPop / GHS：人口密度分布\n"
        "  - OpenStreetMap：建筑物、道路、土地利用\n\n"
        "五、地面传感器与实时观测数据（用于快速预警和验证）\n"
        "  - USGS 地震目录：地震触发报警\n"
        "  - 全球地震台网（可选）：强震动预警\n"
        "  - 现场 IoT 传感器：雨量计、位移计、倾斜仪、水位计\n\n"
        "六、辅助数据（可选，用于增强分析或可视化）\n"
        "  - NASA Black Marble：断电范围评估、灾后影响\n"
        "  - 社交媒体 API：灾情现场文字/图片\n"
        "  - 行政区划矢量：结果分区统计\n\n"
        "端点：图像识别 / 时序预测 / 异常检测 / 融合预警 / 综合分析 / "
        "灾害类型评估 / 社交媒体分析 / 设备健康评分 / 反馈学习闭环 / "
        "真实 XGBoost 模型"
    ),
    version="3.0.0",
)

# CORS：从环境变量读取允许的来源（逗号分隔），开发默认 localhost，生产应显式配置
_cors_origins_raw = os.environ.get("AI_CORS_ORIGINS", "http://localhost:5173")
_cors_origins = [s.strip() for s in _cors_origins_raw.split(",") if s.strip() and s.strip() != "*"]
if not _cors_origins:
    _cors_origins = ["http://localhost:5173"]

app.add_middleware(
    CORSMiddleware,
    allow_origins=_cors_origins,
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(detection.router)
app.include_router(forecast.router)
app.include_router(anomaly.router)
app.include_router(fusion.router)
app.include_router(comprehensive.router)
app.include_router(device_health.router)
app.include_router(disaster_assessment.router)
app.include_router(feedback.router)  # v1.4.0: 反馈学习闭环
app.include_router(model_info.router)  # v1.5.0: 真实模型信息/重训/重载
app.include_router(social_media.router)  # v2.0: 社交媒体文本分析
app.include_router(sentinel.router)  # v1.8.0: Sentinel-1+2 合成卫星图层


@app.get("/api/ai/health", tags=["system"])
def health():
    return {"status": "UP", "service": "ai-service"}