import os
from contextlib import asynccontextmanager
from pathlib import Path
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

# .env 加载（必须在读取任何业务环境变量之前完成）
# 顺序：ai-service/.env（优先,服务专用） → 项目根 .env（共享配置,后到,不覆盖已设置）
# 依赖 python-dotenv,见 requirements.txt
try:
    from dotenv import load_dotenv
    _ROOT = Path(__file__).resolve().parent.parent  # ai-service/
    _REPO = _ROOT.parent                             # repo root

    for _env_path in [_ROOT / ".env", _REPO / ".env"]:
        if _env_path.exists():
            load_dotenv(_env_path, override=False)
except ImportError:
    # dotenv 未安装时静默降级（适合裸 uvicorn 部署）
    pass

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


# 应用 lifespan：启动/关闭 HTTP client（共享 aiohttp session）
@asynccontextmanager
async def lifespan(app: FastAPI):
    """FastAPI lifespan:启动时 lazy init HttpClientHolder,关闭时优雅释放连接池。

    为什么必须显式 close:
      aiohttp 的连接器(TCPConnector)在 GC 时不会立即关闭 keep-alive 连接,会打印
      "Unclosed client session / connector" 警告,生产环境更可能泄漏 socket 句柄。
    """
    from app.utils.http_client import HttpClientHolder
    holder = HttpClientHolder.instance()
    # 主动触发 session 创建,让连接池在启动期就绪(避免首请求冷启动)
    await holder.session()

    try:
        yield
    finally:
        await holder.close()


app = FastAPI(
    title="DisasterGuard AI Service v3.0",
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
        "四、历史灾害与承灾体数据（用于模型训练和风险区划）\n"
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
    lifespan=lifespan,
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

# Sentinel 合成图静态目录：必须先把环境变量设好再 include_router(sentinel)，
# 否则 sentinel.py 模块导入时会用 /tmp 默认值锁死路径。
# 真实生产应改 CDN（OSS/S3），开发模式本地服务足矣。
_SENTINEL_DIR = Path(os.environ.get(
    "SENTINEL_STORAGE_DIR",
    str(Path(__file__).resolve().parent.parent / "outputs" / "sentinel"),
))
_SENTINEL_DIR.mkdir(parents=True, exist_ok=True)
os.environ["SENTINEL_STORAGE_DIR"] = str(_SENTINEL_DIR)
os.environ.setdefault("SENTINEL_CDN_BASE", "http://localhost:8001/sentinel_files")

app.include_router(detection.router)
app.include_router(forecast.router)
app.include_router(anomaly.router)
app.include_router(fusion.router)
app.include_router(comprehensive.router)
app.include_router(device_health.router)
app.include_router(disaster_assessment.router)
app.include_router(feedback.router)
app.include_router(model_info.router)
app.include_router(social_media.router)
app.include_router(sentinel.router)

# 静态文件挂载（必须在 include_router 之后，所有路由尝试匹配后才会落到 static files）
app.mount("/sentinel_files", StaticFiles(directory=str(_SENTINEL_DIR)), name="sentinel_files")


@app.get("/api/ai/health", tags=["system"])
def health():
    return {"status": "UP", "service": "ai-service"}