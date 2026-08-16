from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.routers import anomaly, detection, forecast, fusion

app = FastAPI(
    title="GeoSentry AI Service",
    description="地质灾害智能巡检预警 AI 推理服务（图像识别 / 时序预测 / 异常检测 / 融合预警）",
    version="1.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(detection.router)
app.include_router(forecast.router)
app.include_router(anomaly.router)
app.include_router(fusion.router)


@app.get("/api/ai/health", tags=["system"])
def health():
    return {"status": "UP", "service": "ai-service"}