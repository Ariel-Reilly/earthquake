# DisasterGuard 快速启动指南

## 🚀 完整启动流程

### 1️⃣ 启动基础设施（Docker）

```powershell
# 启动 PostgreSQL、Redis、MinIO 等服务
docker-compose -f docker-compose.dev.yml up -d

# 验证服务状态（应全部 healthy）
docker ps
```

---

### 2️⃣ 启动后端（Spring Boot）

**方式 A：使用快速启动脚本（推荐）**

```powershell
.\START_BACKEND.ps1
```

**方式 B：手动启动**

```powershell
cd backend
mvn clean package -DskipTests
java -jar target/disasterguard-backend-0.0.1-SNAPSHOT.jar
```

**验证后端启动成功**：
- 控制台输出：`Started DisasterguardBackendApplication`
- 访问：http://localhost:8080/actuator/health（应返回 `{"status":"UP"}`）

---

### 3️⃣ 启动前端（Vite）

```powershell
cd frontend
npm install  # 首次运行或依赖变更后执行
npm run dev
```

**访问前端**：http://localhost:5173

**默认账号**：
- 管理员：`admin` / `admin123`
- 值守员：`attendant` / `admin123`
- 维护员：`maintenance` / `admin123`

---

### 4️⃣ 启动 AI 服务（可选）

```powershell
cd ai-service
pip install -r requirements.txt  # 首次运行
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

**验证 AI 服务**：http://localhost:8000/docs

---

## 🔧 常见问题

### ❌ 前端报错：`/api/auth/captcha` 连接被拒

**原因**：后端未启动或端口冲突

**解决**：
1. 检查后端是否运行：`Get-Process -Name "java" | Where-Object { $_.CommandLine -like "*disasterguard*" }`
2. 检查 8080 端口占用：`netstat -ano | findstr :8080`
3. 启动后端：`.\START_BACKEND.ps1`

### ❌ 后端报错：`Connection refused: postgres`

**原因**：PostgreSQL 未启动

**解决**：
```powershell
docker-compose -f docker-compose.dev.yml up -d postgres
docker logs disasterguard-postgres  # 查看日志
```

### ❌ Maven 编译失败

**常见原因**：
1. Java 版本不匹配（需要 17+）：`java -version`
2. Maven 仓库连接超时：检查网络或使用国内镜像
3. 依赖冲突：删除 `backend/target` 后重新编译

### ❌ 前端 Vite 启动失败

**常见原因**：
1. Node.js 版本过低（建议 18+）：`node -v`
2. 端口 5173 被占用：修改 `frontend/vite.config.js` 中的 `port`
3. 依赖未安装：`cd frontend && npm install`

---

## 📦 服务端口总览

| 服务         | 端口  | 访问地址                              |
|------------|-----|-----------------------------------|
| 前端（Vite）    | 5173 | http://localhost:5173             |
| 后端（Spring） | 8080 | http://localhost:8080             |
| AI 服务       | 8000 | http://localhost:8000             |
| PostgreSQL | 5432 | `jdbc:postgresql://localhost:5432/disasterguard` |
| Redis      | 6379 | `redis://localhost:6379`          |
| MinIO      | 9000 | http://localhost:9000 (API)       |
| MinIO 控制台  | 9001 | http://localhost:9001 (Console)   |

---

## 🛑 停止服务

```powershell
# 停止前端：按 Ctrl+C

# 停止后端：按 Ctrl+C 或查找进程 Kill
Get-Process -Name "java" | Where-Object { $_.CommandLine -like "*disasterguard*" } | Stop-Process

# 停止 Docker 服务
docker-compose -f docker-compose.dev.yml down
```

---

## 📚 更多文档

- [开发者文档](docs/DEVELOPER.md)
- [前端 API 文档](docs/FRONTEND_API.md)
- [部署指南](docs/DEPLOYMENT.md)
