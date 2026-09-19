# Copernicus Data Space OAuth 接入指南

> 适用版本：DisasterGuard v3.0+（ai-service v1.9.0+）
> 最后更新：2026-09-16

## 为什么用 CDSE

Copernicus Data Space Ecosystem（CDSE）是 ESA 在 2023 年接替旧 Copernicus Hub 的新一代平台，承载：

- **Sentinel-1 GRD**（SAR，洪水/滑坡/地震同震位移）
- **Sentinel-2 L2A**（光学真彩，NDVI/水体/火灾迹地）
- **Sentinel-3**（海洋/陆地色）
- **Copernicus DEM**（30m/90m 全球 DEM）
- **大量第三方数据集**（Landsat Collection 2、SMAP、MODIS 等）

注册免费，30 天自动续期。SLA 优于旧 Hub，国内访问偶有 GFW 影响，但 Earth Search AWS 镜像基本可用。

---

## 三步接入

### Step 1：注册账号

访问 https://dataspace.copernicus.eu ，右上角 Sign In → "Sign up" 用邮箱注册。验证邮箱后默认是 **General user Account**，30 天有效期。

⚠️ **时间敏感**：注册后账号有 "Valid until YYYY-MM-DD HH:MM" 标记，到期前必须续期（会发邮件提醒）。账号停用后所有 OAuth client 也停用。

### Step 2：创建 OAuth client

1. 登录后右上角头像 → **User settings** → **Applications**
2. 右侧 **OAuth clients** 卡片 → **`+ Create`** 绿色按钮
3. 填写：
   - **Name**：`DisasterGuard`（或任意可识别名字）
   - **Application type / Client type**：`Confidential` ← 必选，Public 类型无 client_credentials
   - **Grant types**：勾上 `Client credentials`（如有多选框）
   - **Redirect URL**：留空或 `http://localhost`（client_credentials 不需要回调）
4. 点 **Save / Create**
5. **立刻复制**：
   - **Client ID**（如 `sh-b5eb347e-...`）
   - **Client Secret**（长字符串，**只显示一次**，关闭即无法再读）

### Step 3：写入项目 `.env`

在仓库根目录 `.env`（如不存在从 `.env.example` 复制）追加：

```bash
COPERNICUS_CLIENT_ID=sh-b5eb347e-e4c2-4a1f-a5bc-5e5965cedd1f
COPERNICUS_CLIENT_SECRET=UnIQO8wbZASBardhoRkmeEHJ9b2PPGT3
COPERNICUS_TOKEN_URL=https://identity.dataspace.copernicus.eu/auth/realms/CDSE/protocol/openid-connect/token
COPERNICUS_STAC_URL=https://stac.dataspace.copernicus.eu/v1/
```

或更隔离：`ai-service/.env`（会被 `app/main.py` 自动 load，且优先级高于根 `.env`）。

⚠️ **安全**：

- `.env` 在 `.gitignore` 中，绝对不要 commit
- Client Secret 一旦泄露（如贴到聊天/日志/截图），立即到 Applications 页 → 详情 → **Regenerate secret**，旧 secret 立刻失效
- 建议存到 1Password / Bitwarden / KeePass 备份一份

---

## 验证接入

### 一键脚本

```powershell
cd ai-service
.\.venv\Scripts\Activate.ps1
python -m tools.test_cdse --aoi 104.07,30.67,104.15,30.75 --days 30
```

预期输出：

```
============================================================
  DisasterGuard · CDSE / STAC fallback 验证
============================================================
  AOI           : [104.07, 30.67, 104.15, 30.75]
  时间范围      : 2026-08-15T00:00:00Z/2026-09-14T23:59:59Z
  最大云量      : 15%
  跳过 CDSE     : False
  CDSE 凭证可用 : True

▶ Step 1: 验证 CDSE token 申请…
  ✓ token 长度 1349 (eyJhbGciOi...)
▶ Step 2: 搜索 Sentinel-2 L2A 场景…
  ✓ 命中 provider=copernicus-dataspace, 1 个场景
    - id=S2A_..._20260822T031651 datetime=...
▶ Step 3: 搜索 Sentinel-1 GRD 场景…
  ✓ 命中 provider=copernicus-dataspace, 1 个场景
    - id=S1A_IW_GRDH_..._20260901T...

============================================================
  ✅ PASS: S2 + S1 均命中,前端可正常生成合成图
```

退出码：

| 退出码 | 含义 |
|--------|------|
| 0 | S2 + S1 都命中，业务可用 |
| 2 | STAC 链路全挂（CDSE/Earth Search/USGS） |
| 3 | 链路跑通但 AOI 内无符合云量的数据 |

### 通过 API 验证（业务侧）

```bash
curl -X POST http://localhost:8001/api/ai/sentinel/compose \
  -H "Content-Type: application/json" \
  -d '{
    "bbox": [104.07, 30.67, 104.15, 30.75],
    "max_cloud": 15,
    "provider": "auto"
  }'
```

返回 jobId 后轮询 `GET /api/ai/sentinel/jobs/{jobId}`，终态 `succeeded` 的 `result.s2Url` / `result.sarUrl` 即为合成图 URL。

---

## Fallback 链路（自动）

`app/utils/stac_clients.py` 实现了三级 fallback，CDSE 故障时自动降级，业务无感：

```
┌──────────────────────────────────────────────┐
│  STAC search_with_fallback()                 │
└──────────────┬───────────────────────────────┘
               │
   ┌───────────▼────────────┐
   │ ① CDSE (OAuth)         │  ← stac.dataspace.copernicus.eu
   │   凭证可用且未 skip     │
   └────────────┬───────────┘
                │ 失败/无 features
   ┌────────────▼───────────┐
   │ ② Earth Search AWS     │  ← earth-search.aws.element84.com
   │   Element84 镜像,免认证 │
   └────────────┬───────────┘
                │ 失败/无 features
   ┌────────────▼───────────┐
   │ ③ USGS Earthdata CMR   │  ← cmr.earthdata.nasa.gov/stac/LPCLOUD
   │   Landsat + 部分 Sentinel│
   └────────────┬───────────┘
                │ 失败
   ┌────────────▼───────────┐
   │ ④ Mock (本地 PIL)      │  ← 演示/断网兜底,无真实数据
   └────────────────────────┘
```

注意：fallback 仅当 `req.provider != "mock"` 时启用。强制走 Earth Search（绕过 CDSE）：

```bash
curl -X POST http://localhost:8001/api/ai/sentinel/compose \
  -d '{"bbox":[...], "provider": "earth_search"}'
```

强制只用 mock（演示/UI 调试）：

```bash
curl ... -d '{"bbox":[...], "provider": "mock"}'
```

---

## 常见问题

### Q: 401 Unauthorized / token 申请失败

```text
CdseAuthError: Copernicus token 申请失败（HTTP 401）
```

**排查**：
1. 检查 `.env` 中 `COPERNICUS_CLIENT_ID` / `COPERNICUS_CLIENT_SECRET` 是否对（Client ID 通常以 `sh-` 开头）
2. 登录 https://dataspace.copernicus.eu 看账号是否还有效
3. 到 Applications 页确认 Client 是否被禁用
4. 如果 secret 曾泄露过，需要 Regenerate secret 后更新 `.env`

### Q: token 申请成功但 STAC 401

说明 OAuth 通，但 STAC endpoint 拒绝。可能是：
- 账号刚注册还没绑权限（极少情况）
- COPERNICUS_STAC_URL 写错了 URL（新版本是 `stac.dataspace.copernicus.eu/v1/`，旧的 `sh.dataspace.copernicus.eu/...` 已废弃）

### Q: AOI 内找不到场景

- 把 `max_cloud` 放宽（默认 15，可试 50）
- 把 `days` 拉长到 60 或 90
- AOI 是否在陆地上？Sentinel-2 L2A 才有意义
- 检查 bbox 经纬度顺序：CDSE 是 `[minLon, minLat, maxLon, maxLat]`，不是 `[lat1, lng1, lat2, lng2]`

### Q: 账号快到期了

账号状态显示 "Valid until 2026-MM-DD HH:MM" 时，到期前 7 天一般会发邮件提醒。续期方法：

1. 登录 CDSE → User settings → Account information
2. 看是否有 "Extend validity" 按钮（General user 30 天周期自动续）
3. 如果没自动续，到期后重新注册一个新账号 → 重建 OAuth client → 更新 `.env`

### Q: 想用 GEE 而不是 CDSE

GEE 需要 service account JSON，复杂度更高。CDSE + Earth Search AWS 已能满足 Sentinel-1/2 全部需求，不推荐 GEE。

---

## 参考链接

- 官方入门：https://dataspace.copernicus.eu/explore-data/getting-started
- OAuth client 管理：https://dataspace.copernicus.eu/profile/applications
- 新版 STAC 文档：https://documentation.dataspace.copernicus.eu/APIs/STAC.html
- Token 端点文档：https://documentation.dataspace.copernicus.eu/APIs/Token.html
- Earth Search AWS（fallback）：https://element84.com/earth-search/
