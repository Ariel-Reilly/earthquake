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
import PermissionButton from "../components/PermissionButton.vue";
import { dashboardApi } from "../services/dashboardApi";
import { aiApi } from "../services/aiApi";

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
  form.value = {
    title: data.item.title,
    level: data.item.level,
    point: data.item.point,
    time: data.item.time,
    status: data.item.status,
    desc: data.item.desc,
  };
  loading.value = false;
  loadAiRisk();
}

async function loadAiRisk() {
  if (!warning.value?.point) return;
  aiRiskLoading.value = true;
  try {
    aiRisk.value = await aiApi.fusionRisk(warning.value.point, {
      displacement_rate: 15.2,
      rainfall_24h: 80.0,
      rainfall_7d: 250.0,
      site_grade: 3.0,
    });
  } catch (e) {
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
  warning.value = { ...warning.value, ...result.item };
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
              ><strong v-if="!editMode">{{ warning.point }}</strong
              ><input v-else v-model="form.point" />
            </div>
            <div>
              <span><MapPin :size="16" />触发时间</span
              ><strong v-if="!editMode">{{ warning.time }}</strong
              ><input v-else v-model="form.time" />
            </div>
            <div>
              <span><ShieldCheck :size="16" />当前状态</span
              ><strong v-if="!editMode">{{ warning.status }}</strong
              ><input v-else v-model="form.status" />
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
                >{{
                  warning.level.includes("橙色")
                    ? "86"
                    : warning.level.includes("黄色")
                      ? "71"
                      : "54"
                }}<small>%</small></span
              >
            </div>
            <div class="task-overview">
              <strong v-if="!editMode">{{ warning.title }}</strong>
              <input v-else v-model="form.title" />
              <span v-if="!editMode">{{ warning.desc }}</span>
              <textarea v-else v-model="form.desc" rows="3"></textarea>
              <div class="task-bars"><i style="width: 78%"></i></div>
              <p>建议立即复核并通知责任人</p>
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
