# A1 现场项自动化预检报告（2026-11-13）

> 目的：把 `DELIVERY_BACKLOG.md` D 节第 4 项里「自动化覆盖不到」的部分尽量再压缩一截，
> 让现场只保留**真正需要人耳、真机、甲方凭证和人工话术**的确认项。
> 结论口径：本轮 **4 个可复现脚本 / 34 项判定全通过**；A1 各子项**仍未整体关闭**，
> 现场按 `ONSITE_ACCEPTANCE_CHECKLIST.md` 继续执行剩余人工步骤。

---

## 1. 验证环境（隔离，未触碰开发库）

```
浏览器(Edge headless CDP 9222)
  → nginx(dist) 25173  ← 演练 conf 含 Origin 改写 + Cesium CSP 放行
  → 后端 28083（本轮最新 fat jar；AI_ENABLED=false）
  → 隔离 postgres 25432 / redis 26379 / minio 29000
  → ai-service 28081（仅时相对比链路使用）
```

- 数据：从开发库 `pg_dump` 恢复到隔离库（7 用户 / 46 预警 / 9 统一事件 / 1795 审计）。
- 口令：只用 `scripts/delivery/compose.delivery-verify.yml` 内明确标注「仅本机隔离演练」的值，未读取 `.env`。
- 所有写操作（创建预警、关闭、会签、导出）都落在隔离库，演练结束 `down -v` 清空。
- **诚实边界**：验证后端以 `AI_ENABLED=false` 启动，因此 `/api/ai/**` 的 503 属预期降级；
  `EXPECT_AI_DISABLED=1` 只把这类 503 单独归类，脚本在真实环境仍严格按 5xx 判失败。

---

## 2. 结果总表

| 脚本 | 对应清单项 | 判定 | 关键结果 |
|---|---|---|---|
| `verify-mobile-responsive.mjs` | A1-3（4.2 / 4.3 / 4.5） | **8/8 PASS** | 4 档视口 × 5 路由 = 20 组：横向溢出 0、空白页 0、5xx 0、异常 0、控制台错误 0；红色弹窗在 360×800 下不溢出、按钮全可点；9 张截图 |
| `verify-alarm-audio.mjs` | A1-1（2.2 / 2.3 / 2.4） | **10/10 PASS** | 红色弹窗 500 ms 出现；自动播放被拦截时显示「开启警报声」；真实输入事件点击后振荡器 +1 / start +1；点「确认收到」后 stop +1 / close +1、弹窗关闭；0 异常 / 0 5xx / 0 控制台错误 |
| `verify-demo-walkthrough.mjs` | A1-5（页面预演） | **5/5 PASS** | 按 `DEMO_SCRIPT.md` 走 13 步，AI 分析 / 处置时间轴 / 通知送达 3 个 Tab 均可点击；纯页面加载累计 **15.7 s** |
| `verify-side-effect-buttons.mjs` | A1-4（5.1 / 5.3 / 5.4 / 5.5） | **11/11 PASS** | 关闭原因必填校验生效、DB 落库「已解除 + 原因」；导出 xlsx 6,808 B / `PK` 魔数；≥2 人会签发起 + admin 代签 200/0 + 状态落库；通知送达 Tab；发布预警 4 步向导 |

明细 JSON 与截图均在 `output/delivery-verify/`：`mobile-responsive.json`、`alarm-audio.json`、
`demo-walkthrough.json`、`side-effect-buttons.json`、`mobile-*.png`。

---

## 3. 各项细节与实测发现

### 3.1 A1-3 移动端响应式

- 视口：iPhone 13 (390×844) / Android (360×800) / 平板 (768×1024) / 桌面基线 (1440×900)，
  配合 `Emulation.setTouchEmulationEnabled`。
- 路由：`/dashboard`、`/dashboard/warnings`、`/dashboard/hazards`、
  `/dashboard/analytics`、`/dashboard/satellite-compare`。
- 判定：根元素 `scrollWidth - innerWidth <= 2`、正文 > 100 字符、无 5xx / 未捕获异常 / 控制台错误。
- 窄屏红色弹窗：`getBoundingClientRect()` 校验遮罩与所有按钮都在视口内。
- **脚本加固记录**：首版只固定等待 1.5 s，在隔离库数据增多后误判空白页、且 WS 推送窗口易错过；
  现改为轮询等内容渲染完成 + WS 连接稳定后创建预警 + 最多 3 次重试创建，复跑稳定 8/8。

### 3.2 A1-1 音频链路

- 用 `Page.addScriptToEvaluateOnNewDocument` 包裹原生 `AudioContext`，统计
  `createOscillator` / `start` / `stop` / `close` 调用，不改动产品代码。
- headless 自动播放策略会拦截 `resume()` → 页面按设计显示「开启警报声」；
  用 CDP `Input.dispatchMouseEvent`（`isTrusted=true`，区别于 JS `.click()`）点击后，
  振荡器创建并启动，证明**恢复路径可用**；点「确认收到」后 `stop + close` 均被调用，
  对应「彻底静音」要求。
- 仍不能替代「人耳听到声音」：见第 4 节。

### 3.3 A1-5 演示流程预演

- 13 步覆盖首页 / 预警中心 / 预警详情 / 3 个 Tab / 实时地图 / 设备 / 审计 / 时相对比 / 会签 / AI 洞察 / 收尾。
- 纯页面加载累计 **15.7 s**，不含讲解、停顿与话术；**不能**用来宣称「彩排 ≤20 分钟已达标」。
- 演练环境 AI 关闭时，预警详情有 1 个 `503 /api/ai/fusion-risk/auto`，页面按
  `DEMO_SCRIPT.md` 显示「加载失败 + 重试」而非假数据，属**设计内诚实降级**；
  现场启动 AI 服务后应消失。

### 3.4 A1-4 有副作用按钮

| 子项 | 实测 |
|---|---|
| 5.1 关闭预警 | 不填原因点「确认关闭」→ 提示「请输入关闭原因」且弹窗不关；填原因后提交成功，DB `status=已解除`、`close_reason` 与输入一致，页面回显原因 |
| 5.3 发布向导 | 预警中心「发布预警」按钮可打开 4 步向导（基本信息 → 发布范围 → 渠道与内容 → 确认发布） |
| 5.4 会签 / 代签 | `EVT-HZ-001` 上发起 2 人会签（HTTP 200）、admin 持 `sign:proxy` 代签确认（HTTP 200 / `code=0`）、`event_sign` 落库且 ≥1 人 `CONFIRMED` |
| 5.5 导出 | `POST /api/warnings/export` → HTTP 200、6,808 B、ZIP 魔数 `504b`（真实 xlsx） |
| 5.2 送达 Tab | Tab 可打开；`MOCK_SENT` 口径仍需现场人工核对，不得说成真实送达 |
| 5.6 通知渠道 / 模板 | 已于 §6 关闭，无需现场复核 500 |

AI 洞察页在 `AI_ENABLED=false` 下停在「正在分析 / 后端连接中」加载态（143 字符），
**未展示伪造评分**；现场启动 AI 服务后须确认该页完整渲染。

---

## 4. 现场仍需人工执行（脚本无法替代）

| 清单项 | 为什么脚本不能替代 | 现场动作 |
|---|---|---|
| A1-1 **2.1 真实听声** | headless 只验证调用链，没有人耳 | 真实 Chrome/Edge 窗口触发红色预警，确认听到警报声 + 闪烁 |
| A1-1 **2.3 真人手势出声** | CDP 输入事件验证了 resume 路径，仍未验证真实外放 | 真人点「开启警报声」，确认立即出声 |
| A1-1 2.2 / 2.4 | 已预检（stop+close / 0 异常） | 现场录屏留证即可 |
| A1-3 **4.1 / 4.4 真机** | CDP 是设备模拟，不是真机触摸/网络 | 手机访问 5173，确认抽屉、表格横滑、地图手势、弹窗布局 |
| A1-3 4.2 / 4.3 / 4.5 | 已 CDP 预检 | 现场截图对照即可 |
| A1-4 **5.2 送达口径** | 脚本只验证 Tab 可打开 | 人工核对回执状态与 `MOCK_SENT` / `BLOCKED` 口径，不谎称「已送达」 |
| A1-4 5.1 / 5.3 / 5.4 / 5.5 | 已在隔离库实跑通过 | 现场按演示流程走一遍留证 |
| A1-5 **8.1~8.4 彩排** | 脚本只跑页面加载，不含讲解与话术 | 按 `DEMO_SCRIPT.md` 完整走 2 遍，计时 ≤20 分钟，核对兜底话术 |
| SM-19 真实影像 | 无 CDSE / GEE / STAC 凭证 | 配好凭证后确认灾前 / 灾后 / 热力图三栏真实影像 |
| AI 洞察完整渲染 | 演练环境 AI 关闭 | 现场按 `start-dev.ps1 -All` 启动 AI 后确认 |

> A4 上线日注入、A6-3 / A6-4 甲方决策、B3/B4-3/B6/B7-1 与 C 类仍按 `DELIVERY_BACKLOG.md` 原状态保留，本轮未触碰。

---

## 5. 复现命令

```bash
# 前置：compose.delivery-verify.yml 拉起隔离基础设施 + 恢复开发库数据；
#       启动 28083 后端、28081 ai-service、nginx 25173、Edge headless CDP 9222
cd E:/Projects/DisasterGuard
node scripts/delivery/verify-mobile-responsive.mjs
node scripts/delivery/verify-alarm-audio.mjs
EXPECT_AI_DISABLED=1 node scripts/delivery/verify-demo-walkthrough.mjs
EXPECT_AI_DISABLED=1 node scripts/delivery/verify-side-effect-buttons.mjs
```

> `EXPECT_AI_DISABLED=1` 仅适用于「演练后端刻意关闭 AI」的场景；
> 现场 / 真实环境不要设置，脚本会把任何 5xx 判为失败。
