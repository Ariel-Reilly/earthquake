# 地脉智巡项目

## 本地开发

### 前端
```bash
npm install
npm run dev
```

### 基础服务
```bash
docker compose up -d
```

### 后端
进入 `backend` 目录后使用 Maven 启动：

```bash
mvn spring-boot:run
```

## 端口约定
- 前端开发服务器：`5173`
- 后端服务：`8080`
- PostgreSQL：`5432`
- Redis：`6379`
- MinIO API：`9000`
- MinIO Console：`9001`
- EMQX MQTT：`1883`
- EMQX Dashboard：`18083`
- TDengine：`6030` / `6041`

## 后端当前骨架
- `POST /api/auth/login`
- `POST /api/auth/logout`
- `GET /api/system/health`
- Swagger：`/swagger-ui.html`

## 配置说明
请复制根目录的 `.env.example` 作为本地环境变量参考，并根据需要修改数据库、Redis、MinIO 和消息服务地址。
