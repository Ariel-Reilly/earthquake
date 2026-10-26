# 核心 API 参考 · DisasterGuard

> 覆盖范围：预警（`WarningController`）、设备图像（`DeviceMediaController`）、AI 图像识别（Python `detection` 路由）。
>
> 所有示例均取自代码中的实际字段与默认值。

---

## 目录

- [通用约定](#通用约定)
- [认证](#认证)
- [预警 API](#预警-api)
- [设备图像 API](#设备图像-api)
- [AI 图像识别 API](#ai-图像识别-api)
- [WebSocket 推送](#websocket-推送)
- [错误码](#错误码)

---

## 通用约定

### Base URL

| 环境 | 地址 |
|---|---|
| 本地后端 | `http://localhost:8080` |
| 本地 AI 服务 | `http://localhost:8001` |
| 容器内（前端经 Nginx 反代） | `/api` |

### 统一响应信封

所有 Java 接口返回 `ApiResponse`：

```json
{
  "code": 0,
  "message": "success",
  "data": { },
  "timestamp": 1761456000123
}
```

- `code = 0` 表示成功；非 0 时 `data` 字段会被省略（`@JsonInclude(NON_NULL)`）。
- 失败时 **HTTP 状态码与 `code` 一致**（如 404、413、500），便于网关直接透传。

### 分页信封

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "records": [],
    "total": 128,
    "page": 1,
    "size": 10,
    "pages": 13
  },
  "timestamp": 1761456000123
}
```

### 鉴权

除登录、健康检查、`/ws` 握手外，所有接口需携带 JWT：

```http
Authorization: Bearer <token>
```

### 时间格式

统一 `ISO-8601` 带时区偏移，服务端按 UTC 存储：

```text
2026-10-26T13:45:00+08:00
```

---

## 认证

### POST /api/auth/login

获取访问令牌。

**请求**

```json
{
  "username": "duty",
  "password": "admin123",
  "role": "attendant"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `username` | string | 是 | 用户名（开发种子账号见 [README](README.md#开发账号)） |
| `password` | string | 是 | 密码 |
| `role` | string | 否 | 角色**编码**，用于多角色账号的选择登录 |

**响应**

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "user": { "name": "值班员", "role": "attendant", "username": "attendant" }
  },
  "timestamp": 1761456000123
}
```

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"duty","password":"admin123"}'
```

### GET /api/auth/ws-ticket

获取一次性 WebSocket 票据（60 秒内一次性消费），避免 JWT 暴露在 WS URL 中。

```bash
curl http://localhost:8080/api/auth/ws-ticket -H "Authorization: Bearer <token>"
```

```json
{ "code": 0, "message": "success", "data": { "ticket": "8f3c...", "expiresIn": 60 }, "timestamp": 1761456000123 }
```

---

## 预警 API

权限点：`warnings:view`（查询）、`warnings:create`（创建）、`warnings:confirm`（确认）、`warnings:dispatch`（派发）、`warnings:close`（关闭/解除/忽略）、`hasRole('ADMIN')`（修改）。

### 预警对象（WarningDto）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | number | 主键 |
| `warningCode` | string | 预警编号，如 `WG-20261026-001` |
| `level` | string | 中文等级：`红色预警` / `橙色预警` / `黄色预警` / `蓝色预警` |
| `cssClass` | string | 前端样式类，如 `level-red` |
| `title` | string | 预警标题 |
| `desc` | string | 描述 |
| `time` | string | 展示用时间文本 |
| `point` | string | 关联点位名称 |
| `status` | string | 状态，见下表 |
| `hazardCode` | string | 关联隐患点编号（外键） |
| `ruleCode` | string | 触发规则编号，如 `R005` |
| `triggerValue` | string | 触发值 |
| `triggeredAt` | string | 触发时间 |
| `closeReason` / `closedBy` / `closedAt` | string | 关闭原因 / 操作人 / 时间 |
| `confirmedAt` / `dispatchedAt` | string | 确认 / 派发时间 |
| `aiLevel` / `aiLevelLabel` / `aiScore` / `aiAnalyzedAt` | mixed | 该隐患点当前 AI 综合风险（由 `AiRiskEnricher` 注入） |

**状态取值**：`待确认` → `已确认` → `处理中` → `已关闭` / `已解除` / `已忽略`

> `POST /{id}/close` 会按 `reason` 自动决定终态：含"误"→`已忽略`，含"解除"→`已解除`，否则→`已关闭`。

### POST /api/warnings —— 人工发布预警

**请求**

```json
{
  "title": "嘉陵江洪水红色预警",
  "level": "red",
  "hazardCode": "HZ-001",
  "hazardName": "嘉陵江沿岸",
  "hazardType": "洪水",
  "region": "重庆市北碚区",
  "lon": 106.45,
  "lat": 29.83,
  "bufferKm": 3.0,
  "channels": ["sms", "app"],
  "content": "水位持续上涨，请沿江居民立即撤离至高处",
  "validUntil": "2026-10-27T12:00:00+08:00",
  "operator": "duty"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `title` | string | 是 | 预警标题 |
| `level` | string | 是 | 前端英文码：`red` / `orange` / `yellow` / `blue`，服务端归一化为中文入库 |
| `hazardCode` | string | 是 | 隐患点编号，必须是库中已存在的值（外键） |
| `hazardName` | string | 否 | 隐患点名称 |
| `hazardType` | string | 否 | 中文灾种（`滑坡` / `崩塌` / …），服务端转换为英文码 |
| `region` | string | 否 | 行政区名称 |
| `lon` / `lat` | number | 否 | 中心坐标 |
| `bufferKm` | number | 否 | 影响半径（公里） |
| `channels` | string[] | 否 | 下发渠道：`sms` / `app` / `email` / `led` |
| `content` | string | 否 | 正文内容 |
| `validUntil` | string | 否 | 有效期 |
| `operator` | string | 否 | 操作人，缺省取当前登录人 |

**响应**：`ApiResponse<WarningDto>`，`status` 初始为 `待确认`。

```bash
curl -X POST http://localhost:8080/api/warnings \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d @warning.json
```

### GET /api/warnings —— 分页查询

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `page` | int | `1` | 页码，从 1 开始 |
| `size` | int | `10` | 每页条数 |
| `keyword` | string | — | 标题/编号模糊匹配 |
| `status` | string | — | 按状态过滤 |

```bash
curl "http://localhost:8080/api/warnings?page=1&size=10&status=待确认" \
  -H "Authorization: Bearer <token>"
```

### GET /api/warnings/stats —— 顶栏统计

```json
{
  "code": 0,
  "data": {
    "pending": 7,
    "dispatched": 3,
    "closed": 12,
    "total": 22,
    "byLevel": { "红色预警": 2, "橙色预警": 5, "黄色预警": 9, "蓝色预警": 6 },
    "byStatus": { "待确认": 7, "处理中": 3, "已关闭": 12 }
  },
  "timestamp": 1761456000123
}
```

| 字段 | 口径 |
|---|---|
| `pending` | 活动态预警数（排除 `已关闭` / `已忽略` / `已解除`） |
| `dispatched` | `处理中` 数量 |
| `closed` | `已关闭` + `已解除` + `已忽略` |
| `byLevel` | 按 `warning_level` 分组 |
| `byStatus` | 按 `status` 分组 |

### GET /api/warnings/viewport —— 地图视野内预警

| 参数 | 说明 |
|---|---|
| `bbox` | 视野范围，格式由前端约定（`minLng,minLat,maxLng,maxLat`） |
| `zoom` | 缩放级别，用于抽稀 |
| `keyword` / `status` | 同分页查询 |

返回 `ApiResponse<List<WarningDto>>`。

### GET /api/warnings/preview —— 预览态预警

返回 `ApiResponse<List<WarningDto>>`，供驾驶舱/大屏轮播使用。

### GET /api/warnings/{id} —— 详情

```json
{ "code": 0, "message": "success", "data": { "item": { } }, "timestamp": 1761456000123 }
```

> 注意外层多包了一层 `item`，与列表接口结构不同。

### PUT /api/warnings/{id} —— 修改（仅 ADMIN）

```json
{ "title": "修订后的标题", "warningLevel": "橙色预警", "status": "已确认" }
```

字段均为可选，仅更新非空项。

### 状态流转接口

| 方法 | 路径 | 权限 | 请求体 | 结果状态 |
|---|---|---|---|---|
| POST | `/api/warnings/{id}/confirm` | `warnings:confirm` | 无 | `已确认` |
| POST | `/api/warnings/{id}/dispatch` | `warnings:dispatch` | 无 | `处理中` |
| POST | `/api/warnings/{id}/close` | `warnings:close` | `{"reason":"现场核实为误报"}` | 按 reason 推导终态 |
| POST | `/api/warnings/{id}/dismiss` | `warnings:close` | 无 | `已忽略` |
| POST | `/api/warnings/{id}/resolve` | `warnings:close` | 见下 | `已解除` |

**解除请求**

```json
{
  "reason": "现场核查，隐患已排除",
  "scope": "ALL",
  "scopeArea": "",
  "resolvedBy": "duty"
}
```

**辅助接口**

| 方法 | 路径 | 返回 |
|---|---|---|
| GET | `/api/warnings/{id}/resolve/preview` | `{ "title", "content", "templates": [...], "channels": ["SMS","APP","SCREEN"] }` |
| GET | `/api/warnings/{id}/resolve/scope` | `{ "scopes": [{ "value": "ALL", "label": "全域" }, ...] }` |

> `resolve/scope` 当前返回硬编码的候选区域（`TODO` 标记：后续接入 `RegionRepository.findByLevel("TOWN")`）。

**完整示例**

```bash
TOKEN="<your-jwt>"
curl -X POST http://localhost:8080/api/warnings/12/confirm -H "Authorization: Bearer $TOKEN"
curl -X POST http://localhost:8080/api/warnings/12/dispatch -H "Authorization: Bearer $TOKEN"
curl -X POST http://localhost:8080/api/warnings/12/resolve \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"reason":"现场核查，隐患已排除","scope":"ALL"}'
```

---

## 设备图像 API

### POST /api/devices/{deviceId}/upload-image

上传设备图像并写入 MinIO，同时发布 `DeviceImageUploadedEvent` 触发异步 AI 研判。

**权限**：`devices:edit` 或 `device:report`

**Content-Type**：`multipart/form-data`

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `deviceId` | path | string | 是 | 设备编号（如 `DV-002`）或设备主键 id，两者都接受 |
| `file` | form | file | 是 | 图片，字段名固定为 `file`，上限 **20MB** |
| `lng` | query | number | 否 | 经度，缺省取设备档案坐标 |
| `lat` | query | number | 否 | 纬度，同上 |

**支持格式**：`jpg` / `jpeg` / `png` / `gif` / `bmp` / `webp` / `tif` / `tiff` / `heic`。
判定规则：`Content-Type` 为 `image/*` 时放行（无扩展名兜底 `jpg`）；否则按文件扩展名判定。

**响应**

```json
{
  "code": 0,
  "message": "图像已接收",
  "data": {
    "deviceId": 12,
    "deviceCode": "DV-002",
    "imageUrl": "http://localhost:19000/disasterguard/device-image/DV-002/20261026/8f3c....jpg?X-Amz-...",
    "objectKey": "device-image/DV-002/20261026/8f3c....jpg",
    "longitude": 106.45,
    "latitude": 29.83,
    "capturedAt": "2026-10-26T13:45:00+08:00"
  },
  "timestamp": 1761456000123
}
```

| 字段 | 说明 |
|---|---|
| `imageUrl` | MinIO 预签名地址，**有效期 7 天**，可直接给前端展示 |
| `objectKey` | 长期有效的对象键，`device-image/{设备编号}/{yyyyMMdd}/{uuid}.{ext}` |
| `capturedAt` | 服务端接收时间 |

**示例**

```bash
curl -X POST "http://localhost:8080/api/devices/DV-002/upload-image?lng=106.45&lat=29.83" \
  -H "Authorization: Bearer <token>" \
  -F "file=@./crack.jpg"
```

**错误**

| HTTP | 场景 |
|---|---|
| 400 | 文件为空 / 非图片 / 坐标越界 |
| 404 | 设备不存在 |
| 413 | 文件超过 20MB |
| 500 | MinIO 写入失败（原始异常只进日志，不外泄） |

> 本接口**只负责接收与存储**，不返回识别结果。识别在 `@Async` 监听器中异步执行，
> 失败不会影响本次上传的响应，也不会回滚已存入的对象。

---

## AI 图像识别 API

Python 服务（FastAPI），与 Java 后端通过 HTTP 解耦。

### POST /api/ai/detect-image

按 URL 拉取图片并做目标检测，返回业务化结论。

**请求**

```json
{
  "image_url": "http://localhost:19000/disasterguard/device-image/DV-002/20261026/8f3c....jpg?X-Amz-...",
  "device_code": "DV-002",
  "hazard_code": "HZ-004"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `image_url` | string | 是 | 必须是 `http/https`，且主机在 `DETECT_IMAGE_ALLOWED_HOSTS` 白名单内（防 SSRF） |
| `device_code` | string | 否 | 仅用于日志回溯 |
| `hazard_code` | string | 否 | 同上 |

**响应**

```json
{
  "disasterType": "debris_flow",
  "confidence": 0.88,
  "detections": [
    { "class_name": "person", "confidence": 0.91, "bbox": [120.0, 64.0, 210.0, 300.0] }
  ],
  "detection_count": 1,
  "image_url": "http://localhost:19000/...",
  "device_code": "DV-002",
  "model": "yolo_crack.onnx",
  "mock": false,
  "message": "ok"
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `disasterType` | string | 业务灾种码：`debris_flow` / `none`（**camelCase**，由 Pydantic `serialization_alias` 输出） |
| `confidence` | number | 业务置信度 `0.0 ~ 1.0`。检出目标时为 `0.88`，未检出为 `0.99` |
| `detections` | array | 原始检测框，`bbox` 为 `[x1, y1, x2, y2]` 像素坐标 |
| `detection_count` | number | 检出目标数 |
| `model` | string | 模型标识，写入 `risk_score_log` 便于回溯 |
| `mock` | boolean | `true` 表示模型未加载、返回启发式结果，置信度不可信 |

> Java 侧 `AiImageResponse` 用 `@JsonAlias` 同时兼容 `disasterType` / `disaster_type`，
> 任一端改命名风格都不会打崩反序列化。

**错误**

| HTTP | 场景 |
|---|---|
| 400 | `image_url` 格式非法 / 图片内容为空 |
| 403 | 主机不在白名单（SSRF 防护） |
| 413 | 图片超过 10MB |
| 502 | 下载图片失败（上游非 200） |
| 504 | 下载超时（默认 10s）或推理超时（默认 30s） |

**示例**

```bash
curl -X POST http://localhost:8001/api/ai/detect-image \
  -H "Content-Type: application/json" \
  -d '{"image_url":"https://example.com/demo.jpg","device_code":"DV-002"}'
```

### POST /api/ai/detect

设备直传 multipart 文件的识别入口（嵌入式相机场景）。

| 参数 | 位置 | 说明 |
|---|---|---|
| `file` | form | 图片文件，上限 10MB |
| `model_type` | form | 模型类型，默认 `yolo`（预留扩展） |

```json
{
  "detections": [ { "class_name": "crack", "confidence": 0.87, "bbox": [10, 20, 110, 220] } ],
  "processed_image_url": null,
  "model": "yolo_crack.onnx",
  "mock": false,
  "detection_count": 1,
  "message": "ok"
}
```

```bash
curl -X POST http://localhost:8001/api/ai/detect -F "file=@./crack.jpg"
```

### GET /api/ai/detect/status

检测器健康状态，用于确认模型是否真正加载。

```json
{ "model_loaded": true, "model_path": "models/yolo_crack.onnx", "input_size": 640 }
```

---

## WebSocket 推送

### 连接

**推荐（一次性 ticket）**

```javascript
const { data } = await fetch('/api/auth/ws-ticket', {
  headers: { Authorization: `Bearer ${token}` }
}).then(r => r.json());

const ws = new WebSocket(`ws://localhost:8080/ws?ticket=${data.ticket}`);
```

**兼容旧客户端**（生产建议 `WS_REQUIRE_TICKET=true` 禁用）

```javascript
const ws = new WebSocket(`ws://localhost:8080/ws?token=${token}`);
```

Origin 由 `FRONTEND_ORIGIN` 控制（支持逗号分隔多个）。

### 消息信封

```json
{
  "type": "warning",
  "data": { },
  "version": 1761456000123
}
```

| `type` | 触发时机 |
|---|---|
| `warning` | 预警新建或状态变更 |
| `device` | 设备上线/离线/指标刷新 |
| `personnel` | 值守人员位置更新 |
| `emergency` | 应急事件新建或闭环 |
| `hazard` | AI 实时监测发现新灾害点 |
| `screen` | 大屏通知 / 应急指令 |

`version` 为单调递增序号，前端可用于丢弃乱序消息。

### 完整示例

```javascript
ws.onmessage = (evt) => {
  const { type, data } = JSON.parse(evt.data);
  if (type === 'warning') {
    warningStore.upsert(data);   // 列表 + 地图高亮
  }
};
```

---

## 错误码

| `code` | HTTP | 含义 |
|---|---|---|
| `0` | 200 | 成功 |
| `400` | 400 | 参数校验失败 / 业务校验不通过 |
| `401` | 401 | 未登录或令牌无效 |
| `403` | 403 | 无权限（缺少 `@PreAuthorize` 声明的权限点） |
| `404` | 404 | 资源不存在（如预警、设备） |
| `413` | 413 | 文件过大 |
| `500` | 500 | 服务端异常（响应体为通用文案，堆栈只进日志） |
| `50301` | — | 注册验证码渠道未接入（prod 下显式关闭时返回） |

**失败响应示例**

```json
{
  "code": 404,
  "message": "未找到设备: DV-999",
  "timestamp": 1761456000123
}
```

**校验失败示例**

```json
{
  "code": 400,
  "message": "请输入密码",
  "timestamp": 1761456000123
}
```
