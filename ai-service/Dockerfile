FROM python:3.11-slim

WORKDIR /app

# 安装依赖
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

# 先复制 requirements 并安装依赖（利用 Docker 缓存）
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 复制应用代码
COPY app ./app

# 创建模型目录
# 注意：原此处有 `chown -R python:python /app`，但官方 python:3.11-slim 镜像
# 并不存在名为 python 的用户，该命令会返回非 0 并导致镜像构建直接失败。
# 属主统一由下一步创建 appuser 后设置，故此处只建目录。
RUN mkdir -p models

# 创建非 root 用户（安全最佳实践）
RUN useradd --create-home --shell /bin/bash appuser && \
    chown -R appuser:appuser /app
USER appuser

EXPOSE 8000

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/api/ai/health')" || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
