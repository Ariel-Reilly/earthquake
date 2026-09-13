<script setup>
import { onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  ArrowLeft,
  Bell,
  FileText,
  GitBranch,
  History,
  Layers3,
  MapPin,
  PencilLine,
  Save,
  ShieldCheck,
  Paperclip,
  BrainCircuit,
} from "lucide-vue-next";
import PermissionButton from "../../components/PermissionButton.vue";
import { dashboardApi } from "../../services/dashboardApi";
import { aiApi } from "../../services/aiApi";

const route = useRoute();
const router = useRouter();
const warning = ref(null);
const loading = ref(true);
const editMode = ref(false);
const form = ref({});
const aiRisk = ref(null);
const aiRiskLoading = ref(false);

async function loadData() {
  loading.value = true;
  const data = await dashboardApi.getWarningDetail(route.params.id);
  warning.value = data.item;
  // 仅 title/level/status 为可编辑字段（后端 WarningUpdateRequest 契约），
  // point(=hazardCode)/time/desc(=triggerValue) 属于只读展示，不加入表单回传。
  form.value = {
    title: data.item.title,
    level: data.item.level,
    status: data.item.status,
  };
  loading.value = false;
  loadAiRisk();
}

async function loadAiRisk() {
  // point 在后端契约中即为 hazardCode，但显式用 hazardCode 字段更清晰、避免歧义
  const hazardCode = warning.value?.hazardCode;
  if (!hazardCode) return;
  aiRiskLoading.value = true;
  try {
    aiRisk.value = await aiApi.fusionRiskAuto(hazardCode);
  } catch (_e) {
    aiRisk.value = null;
  } finally {
    aiRiskLoading.value = false;
  }
}

function riskLevelColor(level) {
  const map = {
    CRITICAL: "#e06060",
    HIGH: "#e0a050",
    MEDIUM: "#d9c040",
    LOW: "#6dbfa5",
  };
  return map[String(level || "").toUpperCase()] || "#7a8d89";
}

function riskLevelLabel(level) {
  const map = {
    CRITICAL: "极端",
    HIGH: "高风险",
    MEDIUM: "中风险",
    LOW: "低风险",
  };
  return map[String(level || "").toUpperCase()] || level || "未知";
}

async function saveDetail() {
  if (!warning.value) return;
  const result = await dashboardApi.updateWarning(warning.value.id, {
    ...form.value,
  });
  // 后端 PUT /warnings/{id} 返回 WarningDto（无 item 包装），直接合并
  warning.value = { ...warning.value, ...result };
  editMode.value = false;
}

async function refreshDetail() {
  await loadData();
}

onMounted(loadData);
</script>

<template>
  <div>
    <div class="content-grid">
      <section class="panel system-panel">
        <div class="panel-header compact">
          <div>
            <h2>预警详情</h2>
            <p>预警等级、编辑与处置状态</p>
          </div>
          <div class="heading-actions">
            <button class="text-btn" @click="router.back()">
              <ArrowLeft :size="15" />返回
            </button>
            <button class="text-btn" @click="refreshDetail">
              <History :size="15" />刷新
            </button>
            <button class="primary-btn" @click="editMode = !editMode">
              <PencilLine :size="15" />{{ editMode ? "取消编辑" : "编辑详情" }}
            </button>
          </div>
        </div>
        <div v-if="loading" style="padding: 0 20px 18px; color: #7a8d89">
          加载中...
        </div>
        <template v-else-if="warning">
          <div class="system-list">
            <div>
              <span><Bell :size="16" />预警等级</span
              ><strong v-if="!editMode">{{ warning.level }}</strong
              ><select v-else v-model="form.level">
                <option>橙色预警</option>
                <option>黄色预警</option>
                <option>蓝色提醒</option>
              </select>
            </div>
            <div>
              <span><Layers3 :size="16" />触发点位</span
              ><strong>{{ warning.point }}</strong>
            </div>
            <div>
              <span><MapPin :size="16" />触发时间</span
              ><strong>{{ warning.time }}</strong>
            </div>
            <div>
              <span><ShieldCheck :size="16" />当前状态</span
              ><strong v-if="!editMode">{{ warning.status }}</strong
              ><select v-else v-model="form.status">
                <option>待确认</option>
                <option>已确认</option>
                <option>处理中</option>
              </select>
            </div>
            <div v-if="warning.closeReason && !editMode">
              <span><GitBranch :size="16" />关闭原因</span
              ><strong>{{ warning.closeReason }}</strong>
            </div>
          </div>

          <!-- AI 融合风险评分 -->
          <div class="chart-card" style="margin: 0 20px 18px">
            <div class="mini-chart-header">
              <strong><BrainCircuit :size="16" />AI 融合风险评分</strong>
              <span v-if="aiRisk?.mock" style="color: #e0a050">启发式降级</span>
              <span v-else-if="aiRisk" style="color: #6dbfa5">{{
                aiRisk.model
              }}</span>
            </div>
            <div v-if="aiRiskLoading" style="padding: 12px; color: #7a8d89">
              AI 分析中...
            </div>
            <div v-else-if="aiRisk" style="padding: 8px 12px">
              <div style="display: flex; align-items: center; gap: 16px">
                <div
                  class="progress-ring"
                  style="width: 54px; height: 54px; font-size: 18px"
                >
                  <span
                    :style="{
                      color:
                        aiRisk.risk_score >= 60
                          ? '#e06060'
                          : aiRisk.risk_score >= 35
                            ? '#e0a050'
                            : '#6dbfa5',
                    }"
                  >
                    {{ aiRisk.risk_score }}<small>%</small>
                  </span>
                </div>
                <div style="flex: 1">
                  <div
                    style="
                      display: flex;
                      justify-content: space-between;
                      margin-bottom: 4px;
                    "
                  >
                    <strong
                      :style="{ color: riskLevelColor(aiRisk.risk_level) }"
                      >{{ riskLevelLabel(aiRisk.risk_level) }}</strong
                    >
                    <span style="color: #7a8d89; font-size: 12px"
                      >置信度 {{ (aiRisk.confidence * 100).toFixed(0) }}%</span
                    >
                  </div>
                  <div class="task-bars">
                    <i
                      :style="{
                        width: aiRisk.risk_score + '%',
                        background: riskLevelColor(aiRisk.risk_level),
                      }"
                    ></i>
                  </div>
                </div>
              </div>
            </div>
            <div v-else style="padding: 12px; color: #7a8d89">
              AI 服务不可用，已跳过评分
            </div>
          </div>

          <div class="task-summary">
            <div class="progress-ring">
              <span
                :style="
                  aiRisk ? { color: riskLevelColor(aiRisk.risk_level) } : {}
                "
              >
                {{ aiRisk ? aiRisk.risk_score : "--" }}<small>%</small>
              </span>
            </div>
            <div class="task-overview">
              <strong v-if="!editMode">{{ warning.title }}</strong>
              <input v-else v-model="form.title" />
              <span v-if="!editMode">{{ warning.desc }}</span>
              <span v-else style="color: #7a8d89">{{ warning.desc }}</span>
              <div class="task-bars">
                <i
                  v-if="aiRisk"
                  :style="{
                    width: aiRisk.risk_score + '%',
                    background: riskLevelColor(aiRisk.risk_level),
                  }"
                ></i>
                <i v-else style="width: 0%"></i>
              </div>
              <p v-if="aiRisk" style="color: #57817d">
                AI 融合评分：{{ riskLevelLabel(aiRisk.risk_level) }}（置信度
                {{ (aiRisk.confidence * 100).toFixed(0) }}%）
              </p>
              <p v-else style="color: #d88a6a">
                AI 识别服务不可用，未生成风险评分（无虚拟数据）
              </p>
            </div>
          </div>
          <div
            class="heading-actions"
            style="padding: 0 20px 18px; justify-content: flex-end"
          >
            <PermissionButton
              permission="warnings:confirm"
              :icon="Save"
              @click="
                async ({ allowed }) => {
                  if (!allowed) return;
                  await saveDetail();
                }
              "
              >保存</PermissionButton
            >
          </div>
        </template>
      </section>

      <section class="panel warning-panel">
        <div class="panel-header compact">
          <div>
            <h2>处置记录</h2>
            <p>预警闭环处理轨迹、附件与联动</p>
          </div>
        </div>
        <div v-if="warning" class="warning-list">
          <article
            v-for="record in warning.history"
            :key="`${record.time}-${record.title}`"
            class="warning-item"
          >
            <div class="warning-symbol orange">W</div>
            <div class="warning-body">
              <div class="warning-title">
                <strong>{{ record.title }}</strong
                ><span class="orange">{{ record.time }}</span>
              </div>
              <p>{{ record.desc }}</p>
            </div>
          </article>
        </div>
        <div v-if="warning" class="chart-card">
          <div class="mini-chart-header">
            <strong><Paperclip :size="16" />附件</strong>
            <span>预警材料</span>
          </div>
          <div class="pie-list">
            <div
              v-for="file in warning.attachments"
              :key="file.name"
              class="pie-item"
            >
              <span>{{ file.name }}</span>
              <div class="pie-track">
                <i style="width: 100%; background: #4f8f99"></i>
              </div>
              <strong>{{ file.size }}</strong>
            </div>
          </div>
        </div>
        <div v-if="warning" class="chart-card">
          <div class="mini-chart-header">
            <strong><GitBranch :size="16" />关联联动</strong>
            <span>点击跳转 · 灾害点 / 设备 / 任务 / 应急</span>
          </div>
          <div v-if="warning.linked?.length" class="pie-list">
            <RouterLink
              v-for="item in warning.linked"
              :key="`${item.type}-${item.label}`"
              :to="item.path"
              class="pie-item link-item"
            >
              <span class="link-type">{{ item.type }}</span>
              <span class="link-label">{{ item.label }}</span>
              <div class="pie-track">
                <i style="width: 100%; background: #6dbfa5"></i>
              </div>
              <strong class="link-go">查看 ›</strong>
            </RouterLink>
          </div>
          <p v-else class="people-empty" style="padding: 12px">暂无关联记录</p>
        </div>
      </section>
    </div>
  </div>
</template>
