# 现场演示脚本（DisasterGuard · 御灾智巡）

> 适用场景：甲方现场演示 / 验收答辩 / 试运行前彩排。建议总时长 **15–20 分钟**，全程使用真实后端数据，不做假指标。

## 一、演示前 30 分钟准备

```powershell
# 1) 基础设施（Postgres/Redis/MinIO）
.\start-dev.ps1 -Infra -Up

# 2) 后端 8080 + 前端 5173 + AI 8001
.\start-dev.ps1 -All -Wait

# 3) 健康检查
Invoke-RestMethod http://127.0.0.1:8080/api/system/health
Invoke-RestMethod http://127.0.0.1:8001/api/ai/model/health
```

浏览器打开 `http://127.0.0.1:5173`，使用 `admin / admin123` 登录。

**演示前检查清单**

| 检查项 | 期望 |
|---|---|
| 右上角「后端连接中」变为正常状态 | 后端/WS 已连接 |
| 综合态势首页有真实统计值、无「加载失败」 | 数据链路正常 |
| 提前准备一条 `HZ-001` 或 `HZ-002` 的待确认预警（用于 AI 分析详情） | 有 AI 分数/指标/影像 |
| 系统音量开启、浏览器未静音 | 红色警报可听到 |
| 预警中心存在红/橙/黄/蓝四色样例数据 | 便于讲分级 |
| （可选）灾害点已上传现场照片 | 分析详情影像区有真实图片 |

如需自动回归一遍全部页面，可启动无头浏览器后执行
`node scripts/delivery/browser-smoke.mjs`（37 项检查，约 2 分钟）。

## 二、演示流程（建议台词顺序）

### 0. 开场（30 秒）
> "这是御灾智巡 DisasterGuard，覆盖监测预警、AI 研判、预警处置、应急协同与审计全链路。
> 数据来自后端真实接口与 AI 服务，演示环境中的标注为演示种子数据。"

### 1. 综合态势首页（2 分钟）
- 展示 4 张 KPI、预警趋势、今日任务、实时动态。
- 说明：KPI 与任务均来自真实接口；接口失败时显示"—/加载失败+重试"，**不会用假数字冒充**。

### 2. 预警中心与四档颜色（2 分钟）
- 打开「预警与处置 → 预警中心」，说明颜色与 AI 研判分数区间一致：
  **≥80 红 / 60–80 橙 / 35–60 黄 / <35 蓝**。
- 点开一条预警 →「AI 分析详情」：展示研判结论、依据、建议、事发前后指标、现场影像。

### 3. 现场红色警报（3 分钟，高潮）
在浏览器保持打开任意页面，另开一个 PowerShell 触发红色预警：

```powershell
$login = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/auth/login' `
  -ContentType 'application/json' -Body (@{username='admin';password='admin123'} | ConvertTo-Json)
$h = @{ Authorization = "Bearer $($login.data.token)" }
$body = @{
  title='演示-红色预警'; level='red'; hazardCode='HZ-002'; hazardName='歌乐山危岩';
  hazardType='collapse'; region='沙坪坝区'; lon=106.4495; lat=29.5598; bufferKm=1;
  channels=@('APP'); content='现场演示红色预警'; operator='admin'
} | ConvertTo-Json -Depth 3
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/warnings' `
  -Headers $h -ContentType 'application/json' -Body $body
```

讲解点：
- 任意页面弹出**满屏红色警报 + 警报声 + 闪烁**；
- 必须点「确认收到」才停止（声音彻底停止），点「查看详情」直达该预警；
- 若浏览器拦截自动播放，红色卡片内会出现「开启警报声」按钮（点击即响）；
- 对比：橙色/黄色/蓝色为对应颜色弹窗，**3 秒自动跳详情**（可点「关闭」取消）。

> 演示时也可直接在「预警与处置 → 预警中心 → 发布预警」向导中选"红色预警"发布。

### 4. 预警处置闭环（2 分钟）
- 在预警详情右侧：**确认预警 → 派发资源 → 关闭预警**；
- 切「处置时间轴」展示每一步操作与操作人；
- 切「通知与送达」展示真实送达统计。

### 5. AI 分析详情（3 分钟，本次新增）
- 预警详情第 2 个 Tab「AI 分析详情」：
  1. **AI 研判结论**：评分、等级、关注度、灾害类型、置信度、模型、分析时间；
  2. **AI 详细分析**：研判依据 + 建议动作；
  3. **事发前后关键指标**：触发时刻 ±2h 的真实监测时序图，触发前/后值、变化率；
  4. **现场影像**：灾害点/预警附件 + 关联设备最近影像。
- 诚实说明边界：无监测样本时显示最新快照并标注"非前后对比"；影像需真实上传，
  种子附件地址失效时显示占位。

### 6. 实时地图 / 设备 / 审计（2 分钟）
- 实时地图：灾害点、设备、预警图层与 canvas 渲染；
- 设备页：状态分布、设备列表；
- 审计日志：按「登录登出」筛选，展示真实筛选效果（不是前端假过滤）。
- （可选）角色演示：`duty / admin123` 登录值班员，直接访问用户管理会被 403 拦截。

### 7. 收尾（30 秒）
> "整套系统可一键启动、可执行 37 项浏览器冒烟与后端/前端构建测试；
> 已知限制与二期能力在《KNOWN_LIMITATIONS.md》中如实列出。"

## 三、常见问题与兜底话术

| 现象 | 原因 | 现场处理 |
|---|---|---|
| 红色警报无声 | 浏览器自动播放策略（页面尚无用户手势） | 点击红色遮罩任意位置或「开启警报声」按钮 |
| 分析详情图片显示占位 | 种子附件 URL 失效 / 未上传影像 | 说明"需在灾害点详情上传真实照片"；可切到设备页看实时数据 |
| 指标区显示"最新快照" | 触发时刻 ±2h 无 device_metric 样本 | 按页面提示说明，不伪装成前后对比 |
| AI 结论带"降级/兜底" | AI 服务不可用时代理模型兜底 | 说明降级策略，AI 恢复后自动刷新 |
| 页面显示"加载失败+重试" | 后端不可达 | 按提示重启后端；说明不会误报"态势平稳" |

## 四、演示后清理

```powershell
# 删除本次演示创建的预警（按标题精确删除，避免误删业务数据）
$login = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/auth/login' `
  -ContentType 'application/json' -Body (@{username='admin';password='admin123'} | ConvertTo-Json)
$h = @{ Authorization = "Bearer $($login.data.token)" }
$page = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/warnings?page=1&size=200' -Headers $h
$page.data.records | Where-Object { $_.title -like '演示-*' -or $_.title -like '浏览器验收-*' } |
  ForEach-Object { Invoke-RestMethod -Method Delete -Uri ("http://127.0.0.1:8080/api/warnings/" + $_.id) -Headers $h }

# 停止服务
.\start-dev.ps1 -Stop
```

## 五、录屏/截图建议

- 红色满屏警报：`output/browser-verify/01-red-alarm-devices-page.png`
- 四档弹窗：`03-orange-popup.png`、`03b-yellow-popup.png`、`03c-blue-popup.png`
- 确认→派发：`20-warning-confirm-dispatch.png`
- AI 分析详情：`21-warning-analysis-detail.png`
- 系统设置落库：`18-settings-persisted.png`

录屏时建议 1440×900、浏览器缩放 100%，提前关闭通知弹窗与无关标签页。
