/**
 * 帮助中心 — /dashboard/help
 *
 * 功能概述:
 *   - 操作手册（按角色展示对应内容）
 *   - 常见问题 FAQ
 *   - API 文档（Swagger UI 嵌入 / 链接）
 *   - 联系管理员 / 提交反馈
 *   - 事件状态机图（Mermaid 可视化）
 *
 * @author Cursor
 */

<script setup>
import { ref } from "vue";
import {
  BookOpen,
  ChevronDown,
  ChevronRight,
  ExternalLink,
  FileText,
  HelpCircle,
  MessageSquare,
  Minus,
  Plus,
  ScrollText,
  Send,
  ShieldCheck,
  Terminal,
  Users,
  Video,
} from "lucide-vue-next";
import { getAuthUser } from "../../stores/auth";

const operator = getAuthUser() ?? { name: "李晨曦", role: "admin" };

// ── 反馈表单 ──
const feedback = ref({ type: "question", subject: "", content: "", contact: "" });
const feedbackSending = ref(false);
const feedbackSuccess = ref(false);

async function submitFeedback() {
  if (!feedback.value.content.trim()) return;
  feedbackSending.value = true;
  try {
    // 真实场景：await dashboardApi.submitFeedback(feedback.value)
    await new Promise((r) => setTimeout(r, 800));
    feedbackSuccess.value = true;
    feedback.value = { type: "question", subject: "", content: "", contact: "" };
    setTimeout(() => { feedbackSuccess.value = false; }, 3000);
  } finally {
    feedbackSending.value = false;
  }
}

// ── FAQ 展开 ──
const expandedFaq = ref(new Set([0, 4]));

function toggleFaq(i) {
  if (expandedFaq.value.has(i)) {
    expandedFaq.value.delete(i);
  } else {
    expandedFaq.value.add(i);
  }
}

// ── 状态机图数据（Mermaid） ──
const stateMachineDiagram = `stateDiagram-v2
  [*] --> 疑似事件: AI发现/舆情上报
  疑似事件 --> 人工确认: 认领
  人工确认 --> 确认中: 开始确认
  人工确认 --> 已驳回: 驳回
  已驳回 --> [*]
  确认中 --> 已确认: 确认
  确认中 --> 已驳回: 驳回
  已确认 --> 预警发布: 创建预警
  预警发布 --> 预警发布: 发布
  预警发布 --> 已解除: 解除
  预警发布 --> 应急事件: 升级应急
  应急事件 --> 处置中: 开始处置
  处置中 --> 已闭环: 完成
  处置中 --> 处置中: 持续处置
  已闭环 --> [*]
  已解除 --> [*]`;

// ── 手册目录 ──
const manuals = [
  {
    role: "admin",
    label: "管理员手册",
    icon: ShieldCheck,
    sections: [
      { title: "1. 用户与权限管理", items: ["创建用户", "分配角色", "配置菜单权限", "配置操作权限", "数据权限范围"] },
      { title: "2. 系统监控与告警", items: ["查看服务状态", "设置告警阈值", "处理告警"] },
      { title: "3. 审计日志", items: ["查询操作日志", "导出审计报告"] },
      { title: "4. 备份与恢复", items: ["执行备份", "恢复数据"] },
      { title: "5. AI 模型管理", items: ["上线新模型", "切换在线模型", "查看推理指标"] },
    ],
  },
  {
    role: "emergency_commander",
    label: "应急指挥官手册",
    icon: ScrollText,
    sections: [
      { title: "1. 预警发布", items: ["创建预警", "审批预警", "发布/解除预警"] },
      { title: "2. 应急事件处置", items: ["启动应急", "资源调度", "任务指派", "事件闭环"] },
      { title: "3. 预案管理", items: ["查看预案", "启动预案演练"] },
      { title: "4. 审批流", items: ["审批资源申请", "会签操作"] },
    ],
  },
  {
    role: "attendant",
    label: "值班员手册",
    icon: Users,
    sections: [
      { title: "1. 综合态势大屏", items: ["查看核心 KPI", "按角色切换视角", "下钻区域大屏"] },
      { title: "2. 人工确认工作台", items: ["认领任务", "确认/驳回灾害", "转派任务"] },
      { title: "3. 预警中心", items: ["查看预警列表", "订阅预警通知"] },
      { title: "4. 待办中心", items: ["处理待确认", "处理待审批", "跟踪任务"] },
    ],
  },
  {
    role: "expert",
    label: "专家手册",
    icon: BookOpen,
    sections: [
      { title: "1. AI 综合研判", items: ["查看融合评分", "理解可解释性推理", "给出专家意见"] },
      { title: "2. 灾损评估", items: ["评估受灾范围", "生成评估报告"] },
      { title: "3. 灾害预测", items: ["查看风险预测", "调整预测参数"] },
    ],
  },
];

// ── FAQ 列表 ──
const faqs = [
  {
    q: "如何发布一条预警？",
    a: "路径：预警与处置 → 预警中心 → 新建预警。首先需要在'灾害事件列表'中确认一个灾害点，然后在预警创建页选择该灾害点、设定预警等级（蓝/黄/橙/红）、选择发布范围和通知渠道，最后提交审批（橙/红色需审批）。",
  },
  {
    q: "橙色/红色预警需要哪些审批流？",
    a: "橙色预警：需经过应急指挥官（attendant 角色）审批 → 系统管理员审批。红色预警：需经过应急指挥官 → 首席专家 → 系统管理员三签会签。所有审批均可通过'审批中心'查看进度，超时未审批会自动升级。",
  },
  {
    q: "AI 发现的疑似事件如何处理？",
    a: "AI 推理结果会自动推送到'人工确认工作台'。值班员认领后进行二次确认：确认则进入灾害事件档案并可创建预警；驳回则注明原因后归档。所有操作自动留痕，可在审计日志中查询。",
  },
  {
    q: "如何添加新设备并接入数据？",
    a: "路径：数据与模型 → 设备管理 → 添加设备。填写设备基本信息（名称、类型、协议、位置坐标），保存后设备进入待认证状态。设备上报首次数据后自动完成认证。数据源接入在'数据源接入'页面配置。",
  },
  {
    q: "为什么收不到预警通知？",
    a: "检查以下三方面：①'通知模板管理'中对应灾种/等级/渠道的模板是否启用；②'通知渠道配置'中该渠道（短信/APP/邮件）是否配置并测试通过；③您的用户账号是否在预警发布范围内。",
  },
  {
    q: "如何申请数据权限？",
    a: "路径：数据与模型 → 数据资产目录。找到所需数据资源后点击'申请权限'，填写申请理由和数据范围，提交后由数据 Owner 审批。审批通过后可在有效期内访问该数据。",
  },
  {
    q: "OTA 固件升级失败如何处理？",
    a: "升级失败后设备会自动回滚到原固件。在'OTA 与边缘升级'页面查看失败详情：如果是网络问题等待重试；如果是固件包损坏重新上传包；如持续失败请联系设备厂商排查。",
  },
  {
    q: "如何导出历史灾害数据？",
    a: "路径：系统与统计 → 历史灾害库。使用筛选条件定位目标灾害记录后，点击'导出'按钮选择格式（Excel/CSV/PDF），系统会生成文件并发送下载链接。也可在统计分析页面按维度批量导出。",
  },
];

// ── 视频教程 ──
const videos = [
  { title: "系统快速入门（5分钟）", duration: "5:32", thumb: "👋" },
  { title: "预警发布完整流程", duration: "8:15", thumb: "🔔" },
  { title: "应急事件处置实战", duration: "12:40", thumb: "🚨" },
  { title: "AI 研判与人工确认", duration: "7:20", thumb: "🤖" },
  { title: "资源调度指挥操作", duration: "6:50", thumb: "🚛" },
  { title: "数据源接入配置", duration: "9:10", thumb: "🔌" },
];

// 当前手册角色
const currentManual = manuals.find((m) => m.role === operator.role) ?? manuals[0];
</script>

<template>
  <div class="help-page">
    <!-- 顶部标题 -->
    <div class="page-header">
      <div class="header-icon">
        <HelpCircle :size="28" />
      </div>
    </div>

    <!-- 快速入口 -->
    <div class="quick-links">
      <a href="#manuals" class="quick-link">
        <BookOpen :size="20" />
        <span>操作手册</span>
      </a>
      <a href="#faq" class="quick-link">
        <HelpCircle :size="20" />
        <span>常见问题</span>
      </a>
      <a href="#api" class="quick-link">
        <Terminal :size="20" />
        <span>API 文档</span>
      </a>
      <a href="#state-machine" class="quick-link">
        <ScrollText :size="20" />
        <span>状态机图</span>
      </a>
      <a href="#feedback" class="quick-link">
        <MessageSquare :size="20" />
        <span>提交反馈</span>
      </a>
    </div>

    <div class="help-body">
      <div class="help-main">
        <!-- 操作手册 -->
        <section id="manuals" class="help-section">
          <h2 class="section-title">
            <BookOpen :size="18" />
            操作手册
            <span class="role-badge">{{ currentManual.label }}</span>
          </h2>
          <div class="manuals-grid">
            <div
              v-for="manual in manuals"
              :key="manual.role"
              class="manual-card"
              :class="{ active: manual.role === currentManual.role }"
            >
              <component :is="manual.icon" :size="20" class="manual-icon" />
              <div class="manual-info">
                <div class="manual-title">{{ manual.label }}</div>
                <div class="manual-meta">{{ manual.sections.length }} 个章节</div>
              </div>
            </div>
          </div>

          <!-- 当前手册详情 -->
          <div class="manual-detail">
            <div
              v-for="section in currentManual.sections"
              :key="section.title"
              class="manual-section"
            >
              <h3 class="section-heading">{{ section.title }}</h3>
              <ul class="section-list">
                <li v-for="item in section.items" :key="item">
                  <ChevronRight :size="13" />
                  {{ item }}
                </li>
              </ul>
            </div>
          </div>
        </section>

        <!-- FAQ -->
        <section id="faq" class="help-section">
          <h2 class="section-title">
            <HelpCircle :size="18" />
            常见问题
          </h2>
          <div class="faq-list">
            <div
              v-for="(faq, i) in faqs"
              :key="i"
              class="faq-item"
              :class="{ open: expandedFaq.has(i) }"
            >
              <button class="faq-question" @click="toggleFaq(i)">
                <span>{{ faq.q }}</span>
                <component :is="expandedFaq.has(i) ? Minus : Plus" :size="16" />
              </button>
              <div v-if="expandedFaq.has(i)" class="faq-answer">
                {{ faq.a }}
              </div>
            </div>
          </div>
        </section>

        <!-- 事件状态机 -->
        <section id="state-machine" class="help-section">
          <h2 class="section-title">
            <ScrollText :size="18" />
            事件状态机图
          </h2>
          <p class="section-desc">
            统一事件从生成到归档的全链路状态流转说明。7 种状态 × 12 种转移，覆盖 AI 发现 → 人工确认 → 预警发布 → 应急处置 → 归档闭环。
          </p>
          <div class="mermaid-wrap">
            <pre class="mermaid">{{ stateMachineDiagram }}</pre>
            <p class="mermaid-note">
              提示：如需交互式状态机图，请安装
              <code>mermaid</code> 渲染插件（Vue Mermaid Chart / Mermaid.js）
            </p>
          </div>

          <!-- 状态说明表 -->
          <div class="state-table">
            <div class="state-row header">
              <span>状态</span>
              <span>说明</span>
              <span>可执行操作</span>
            </div>
            <div v-for="s in stateRows" :key="s.state" class="state-row">
              <span class="state-name" :style="{ color: s.color }">{{ s.state }}</span>
              <span class="state-desc">{{ s.desc }}</span>
              <span class="state-ops">{{ s.ops }}</span>
            </div>
          </div>
        </section>

        <!-- API 文档 -->
        <section id="api" class="help-section">
          <h2 class="section-title">
            <Terminal :size="18" />
            API 文档
          </h2>
          <p class="section-desc">
            Swagger UI 提供完整的 REST API 文档，支持在线调试。
          </p>
          <div class="api-links">
            <div class="api-card">
              <div class="api-endpoint">
                <span class="method get">GET</span>
                <code>/api/v1/hazards</code>
              </div>
              <div class="api-desc">查询灾害事件列表</div>
            </div>
            <div class="api-card">
              <div class="api-endpoint">
                <span class="method post">POST</span>
                <code>/api/v1/warnings</code>
              </div>
              <div class="api-desc">创建预警</div>
            </div>
            <div class="api-card">
              <div class="api-endpoint">
                <span class="method get">GET</span>
                <code>/api/v1/ai/results</code>
              </div>
              <div class="api-desc">获取 AI 推理结果</div>
            </div>
            <div class="api-card">
              <div class="api-endpoint">
                <span class="method post">POST</span>
                <code>/api/v1/events/transition</code>
              </div>
              <div class="api-desc">推进事件状态</div>
            </div>
          </div>
          <a
            href="/swagger-ui.html"
            target="_blank"
            class="swagger-link"
          >
            <ExternalLink :size="14" />
            打开 Swagger UI
          </a>
        </section>
      </div>

      <div class="help-side">
        <!-- 视频教程 -->
        <div class="side-card">
          <h3 class="side-title">
            <Video :size="15" />
            视频教程
          </h3>
          <div class="video-list">
            <div v-for="v in videos" :key="v.title" class="video-item">
              <div class="video-thumb">{{ v.thumb }}</div>
              <div class="video-info">
                <div class="video-title">{{ v.title }}</div>
                <div class="video-duration">{{ v.duration }}</div>
              </div>
            </div>
          </div>
        </div>

        <!-- 反馈表单 -->
        <div id="feedback" class="side-card">
          <h3 class="side-title">
            <MessageSquare :size="15" />
            提交反馈
          </h3>
          <div v-if="feedbackSuccess" class="feedback-success">
            ✓ 反馈已提交，我们会尽快处理
          </div>
          <form v-else class="feedback-form" @submit.prevent="submitFeedback">
            <div class="form-field">
              <label>类型</label>
              <select v-model="feedback.type" class="form-select">
                <option value="question">问题咨询</option>
                <option value="bug">Bug 反馈</option>
                <option value="suggestion">功能建议</option>
                <option value="other">其他</option>
              </select>
            </div>
            <div class="form-field">
              <label>主题</label>
              <input
                v-model="feedback.subject"
                class="form-input"
                placeholder="简要描述问题"
                maxlength="60"
              />
            </div>
            <div class="form-field">
              <label>详细描述 <span class="required">*</span></label>
              <textarea
                v-model="feedback.content"
                class="form-textarea"
                placeholder="请详细描述您遇到的问题或建议…"
                rows="4"
                required
              />
            </div>
            <div class="form-field">
              <label>联系方式（可选）</label>
              <input
                v-model="feedback.contact"
                class="form-input"
                placeholder="手机号或邮箱"
              />
            </div>
            <button
              type="submit"
              class="submit-btn"
              :disabled="feedbackSending || !feedback.content.trim()"
            >
              <Send :size="14" />
              {{ feedbackSending ? "提交中…" : "提交反馈" }}
            </button>
          </form>
        </div>

        <!-- 联系信息 -->
        <div class="side-card contact-card">
          <h3 class="side-title">
            <Users :size="15" />
            联系我们
          </h3>
          <div class="contact-list">
            <div class="contact-item">
              <span class="contact-label">技术支持</span>
              <span class="contact-value">support@disasterguard.cn</span>
            </div>
            <div class="contact-item">
              <span class="contact-label">应急热线</span>
              <span class="contact-value">400-888-0000</span>
            </div>
            <div class="contact-item">
              <span class="contact-label">工作时间</span>
              <span class="contact-value">7×24 小时</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
// 状态机说明（非 SFC setup 块）
const stateRows = [
  { state: "疑似事件", color: "#6366f1", desc: "AI 检测到异常或舆情上报的初始状态", ops: "认领 / 驳回" },
  { state: "人工确认", color: "#f59e0b", desc: "值班员认领并开始核实", ops: "开始确认 / 转派" },
  { state: "确认中", color: "#3b82f6", desc: "正在二次核实与评估", ops: "确认 / 驳回" },
  { state: "已确认", color: "#22c55e", desc: "灾害事件确认，进入档案", ops: "创建预警 / 升级应急" },
  { state: "预警发布", color: "#ef4444", desc: "预警已发布，等待处置", ops: "解除 / 升级应急" },
  { state: "应急事件", color: "#dc2626", desc: "橙/红预警触发的应急处置流程", ops: "开始处置" },
  { state: "已闭环", color: "#64748b", desc: "处置完成，等待归档", ops: "归档" },
  { state: "已解除", color: "#94a3b8", desc: "预警已解除，系统恢复", ops: "归档" },
  { state: "已驳回", color: "#94a3b8", desc: "事件被驳回，无需处置", ops: "归档" },
];
</script>

<style scoped>
.help-page {
  height: 100%;
  overflow-y: auto;
  background: #f8fafc;
}

/* ── 头部 ── */
.page-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 24px 24px 16px;
  background: #fff;
  border-bottom: 1px solid #e2e8f0;
}

.header-icon {
  width: 52px;
  height: 52px;
  background: #eff6ff;
  color: #3b82f6;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.page-title {
  font-size: 22px;
  font-weight: 700;
  color: #1e293b;
  margin: 0 0 4px;
}

.page-desc {
  font-size: 14px;
  color: #64748b;
  margin: 0;
}

/* ── 快速入口 ── */
.quick-links {
  display: flex;
  gap: 8px;
  padding: 12px 24px;
  background: #fff;
  border-bottom: 1px solid #e2e8f0;
  flex-wrap: wrap;
}

.quick-link {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  border-radius: 20px;
  font-size: 13px;
  color: #475569;
  text-decoration: none;
  transition: all 0.15s;
}

.quick-link:hover {
  background: #1d4ed8;
  color: #fff;
  border-color: #1d4ed8;
}

/* ── 主体布局 ── */
.help-body {
  display: grid;
  grid-template-columns: 1fr 300px;
  gap: 20px;
  padding: 20px 24px;
  max-width: 1200px;
}

@media (max-width: 900px) {
  .help-body { grid-template-columns: 1fr; }
}

/* ── 章节 ── */
.help-section {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 16px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 700;
  color: #1e293b;
  margin: 0 0 16px;
}

.role-badge {
  margin-left: auto;
  background: #dbeafe;
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 600;
  padding: 2px 10px;
  border-radius: 10px;
}

.section-desc {
  font-size: 13px;
  color: #64748b;
  margin: 0 0 14px;
  line-height: 1.6;
}

/* ── 手册 ── */
.manuals-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 10px;
  margin-bottom: 16px;
}

.manual-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s;
}

.manual-card:hover, .manual-card.active {
  border-color: #3b82f6;
  background: #eff6ff;
}

.manual-icon { color: #3b82f6; flex-shrink: 0; }

.manual-title {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
  margin-bottom: 2px;
}

.manual-meta { font-size: 11px; color: #94a3b8; }

.manual-detail {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 16px;
}

.manual-section { margin-bottom: 16px; }
.manual-section:last-child { margin-bottom: 0; }

.section-heading {
  font-size: 13px;
  font-weight: 700;
  color: #374151;
  margin: 0 0 8px;
}

.section-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.section-list li {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #64748b;
  background: #fff;
  border: 1px solid #e2e8f0;
  padding: 3px 8px;
  border-radius: 4px;
}

/* ── FAQ ── */
.faq-list { display: flex; flex-direction: column; gap: 8px; }

.faq-item {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  overflow: hidden;
  transition: border-color 0.15s;
}

.faq-item.open { border-color: #3b82f6; }

.faq-question {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  background: none;
  border: none;
  font-size: 13px;
  font-weight: 600;
  color: #374151;
  cursor: pointer;
  text-align: left;
  transition: background 0.15s;
}

.faq-question:hover { background: #f8fafc; }

.faq-answer {
  padding: 12px 14px;
  font-size: 13px;
  color: #475569;
  line-height: 1.7;
  border-top: 1px solid #f1f5f9;
  background: #fafafa;
}

/* ── 状态机图 ── */
.mermaid-wrap {
  background: #1e293b;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 14px;
  overflow-x: auto;
}

.mermaid {
  color: #e2e8f0;
  font-family: "Courier New", monospace;
  font-size: 12px;
  line-height: 1.5;
  margin: 0;
}

.mermaid-note {
  font-size: 12px;
  color: #64748b;
  margin: 8px 0 0;
}

.mermaid-note code {
  background: #334155;
  color: #38bdf8;
  padding: 1px 5px;
  border-radius: 3px;
}

.state-table {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  overflow: hidden;
  font-size: 12px;
}

.state-row {
  display: grid;
  grid-template-columns: 80px 1fr 120px;
  gap: 12px;
  padding: 8px 12px;
  border-bottom: 1px solid #f1f5f9;
}

.state-row:last-child { border-bottom: none; }
.state-row.header { background: #f8fafc; font-weight: 700; color: #64748b; }

.state-name { font-weight: 700; }
.state-desc { color: #64748b; }
.state-ops { color: #3b82f6; font-size: 11px; }

/* ── API ── */
.api-links {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-bottom: 14px;
}

.api-card {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 12px;
}

.api-endpoint {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.method {
  font-size: 10px;
  font-weight: 700;
  padding: 2px 6px;
  border-radius: 4px;
  color: #fff;
}

.method.get { background: #22c55e; }
.method.post { background: #3b82f6; }
.method.put { background: #f59e0b; }
.method.delete { background: #ef4444; }

.api-endpoint code {
  font-size: 12px;
  color: #374151;
  background: #fff;
  padding: 1px 4px;
  border-radius: 3px;
}

.api-desc { font-size: 12px; color: #64748b; }

.swagger-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  background: #1e293b;
  color: #38bdf8;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
  transition: background 0.15s;
}

.swagger-link:hover { background: #334155; }

/* ── 侧栏 ── */
.help-side { display: flex; flex-direction: column; gap: 14px; }

.side-card {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 16px;
}

.side-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 700;
  color: #374151;
  margin: 0 0 12px;
}

/* ── 视频 ── */
.video-list { display: flex; flex-direction: column; gap: 10px; }

.video-item {
  display: flex;
  gap: 10px;
  align-items: center;
  cursor: pointer;
  padding: 6px;
  border-radius: 6px;
  transition: background 0.15s;
}

.video-item:hover { background: #f8fafc; }

.video-thumb {
  width: 36px;
  height: 36px;
  background: #eff6ff;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  flex-shrink: 0;
}

.video-title { font-size: 12px; font-weight: 600; color: #374151; margin-bottom: 2px; }
.video-duration { font-size: 11px; color: #94a3b8; }

/* ── 反馈表单 ── */
.feedback-success {
  padding: 14px;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  border-radius: 8px;
  color: #166534;
  font-size: 13px;
  text-align: center;
}

.feedback-form { display: flex; flex-direction: column; gap: 10px; }

.form-field { display: flex; flex-direction: column; gap: 4px; }

.form-field label {
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
}

.required { color: #ef4444; }

.form-input, .form-select, .form-textarea {
  padding: 7px 10px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  font-size: 13px;
  color: #374151;
  background: #fff;
  outline: none;
  transition: border-color 0.15s;
  font-family: inherit;
}

.form-input:focus, .form-select:focus, .form-textarea:focus {
  border-color: #3b82f6;
}

.form-textarea { resize: vertical; }

.submit-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 9px;
  background: #1d4ed8;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s;
}

.submit-btn:hover:not(:disabled) { background: #1e40af; }
.submit-btn:disabled { opacity: 0.6; cursor: not-allowed; }

/* ── 联系 ── */
.contact-list { display: flex; flex-direction: column; gap: 10px; }

.contact-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  padding-bottom: 8px;
  border-bottom: 1px solid #f1f5f9;
}

.contact-item:last-child { border-bottom: none; padding-bottom: 0; }

.contact-label { color: #64748b; }
.contact-value { font-weight: 600; color: #374151; }
</style>
