<script setup>
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  ArrowLeft,
  FileText,
  Gauge,
  Layers3,
  MapPin,
  Mountain,
  PencilLine,
  Paperclip,
  GitBranch,
  History,
  Link2,
  Save,
  MapPinned,
  BrainCircuit,
  TrendingUp,
} from "lucide-vue-next";
import PermissionButton from "../../components/PermissionButton.vue";
import { dashboardApi } from "../../services/dashboardApi";
import { aiApi } from "../../services/aiApi";

const route = useRoute();
const router = useRouter();
const point = ref(null);
const loading = ref(true);
const editMode = ref(false);
const form = ref({});
const cesiumBusy = ref(false);
const cesiumMessage = ref("");
let cesiumCleanup = null;
const aiForecast = ref(null);
const aiForecastLoading = ref(false);
const aiComprehensive = ref(null);
const aiComprehensiveLoading = ref(false);
const aiLevelLabel = (level) => ({ CRITICAL: '极高', HIGH: '高', MEDIUM: '中', LOW: '低' }[level] || '—');
const aiLevelClass = (level) => ({ CRITICAL: 'orange', HIGH: 'yellow', MEDIUM: 'blue', LOW: 'teal' }[level] || 'blue');

async function loadAiComprehensive() {
  if (!point.value) return;
  aiComprehensiveLoading.value = true;
  try {
    const code = point.value.hazardCode ?? point.value.code;
    if (!code) {
      // 无 hazardCode：退化为读 point.aiLevel/aiScore（DTO 已被 AiRiskEnricher 注入）
      aiComprehensive.value = point.value.aiScore != null
        ? {
            hazardCode: code,
            riskLevel: point.value.aiLevel,
            riskScore: point.value.aiScore,
            confidence: null,
            model: null,
            analyzedAt: point.value.aiAnalyzedAt,
            dimensions: null,
            reasoning: [],
            suggestedActions: [],
          }
        : null;
      return;
    }
    const cached = await dashboardApi.getAiComprehensiveCached(code);
    aiComprehensive.value = cached;
  } catch (_) {
    // 端点 404（未落库）或网络失败：退化为 DTO 自带 aiLevel/aiScore
    if (point.value.aiScore != null) {
      aiComprehensive.value = {
        hazardCode: point.value.hazardCode ?? point.value.code,
        riskLevel: point.value.aiLevel,
        riskScore: point.value.aiScore,
        confidence: null,
        model: null,
        analyzedAt: point.value.aiAnalyzedAt,
        dimensions: null,
        reasoning: [],
        suggestedActions: [],
      };
    } else {
      aiComprehensive.value = null;
    }
  } finally {
    aiComprehensiveLoading.value = false;
  }
}

async function refreshAiComprehensive() {
  if (!point.value) return;
  const code = point.value.hazardCode ?? point.value.code;
  if (!code) return;
  try {
    // 强制实时推理 + 写回落库（让下次点进直接走缓存）
    aiComprehensive.value = await aiApi.comprehensiveAnalysis(code);
  } catch (_) {
    // 实时推理失败：保留当前值
  }
}

async function loadData() {
  loading.value = true;
  const data = await dashboardApi.getHazardDetail(route.params.id);
  point.value = data.item;
  form.value = {
    name: data.item.name,
    type: data.item.type,
    level: data.item.level,
    owner: data.item.owner,
    status: data.item.status,
    lon: data.item.lon,
    lat: data.item.lat,
  };
  loading.value = false;
  loadAiForecast();
  loadAiComprehensive();
}

async function loadAiForecast() {
  if (!point.value) return;
  aiForecastLoading.value = true;
  try {
    // 从后端直接拉取该设备的真实历史指标，交由 AI 完成预测；
    // 不合成/伪造历史数据，AI 不可用时按 null 处理，界面显示不可用提示。
    aiForecast.value = await aiApi.forecastAuto(
      point.value.deviceCode || "DEV-001",
      "displacement",
      7,
      30,
    );
  } catch (_e) {
    aiForecast.value = null;
  } finally {
    aiForecastLoading.value = false;
  }
}

const latestForecast = computed(() => {
  const predictions = aiForecast.value?.predictions;
  if (!Array.isArray(predictions) || !predictions.length) return null;
  return predictions[predictions.length - 1];
});
const forecastMax = computed(() => {
  const predictions = aiForecast.value?.predictions;
  if (!Array.isArray(predictions) || !predictions.length) return 1;
  return Math.max(...predictions) || 1;
});
const forecastPercent = computed(() => {
  if (latestForecast.value == null) return 0;
  return Math.max(3, (latestForecast.value / forecastMax.value) * 100);
});

async function saveDetail() {
  if (!point.value) return;
  const result = await dashboardApi.updateHazard(point.value.id, form.value);
  // 后端 PUT /hazards/{id} 返回 HazardDto（无 item 包装），直接合并
  point.value = { ...point.value, ...result };
  editMode.value = false;
}

async function refreshDetail() {
  await loadData();
}

async function open3DView() {
  if (!point.value) return;
  cesiumBusy.value = true;
  cesiumMessage.value = "";
  try {
    if (cesiumCleanup) {
      cesiumCleanup();
      cesiumCleanup = null;
    }
    const { createCesiumViewer, addCesiumPoint, destroyCesiumViewer } =
      await import("../../services/mapService");
    const viewerBundle = await createCesiumViewer(document.body, {
      center: [Number(point.value.lon), Number(point.value.lat)],
      point: point.value,
    });
    addCesiumPoint(viewerBundle, point.value);
    cesiumCleanup = () => destroyCesiumViewer(viewerBundle);
    cesiumMessage.value = "三维视角已加载，关闭页面即可退出。";
  } catch (error) {
    cesiumMessage.value =
      error instanceof Error ? error.message : "3D 视角加载失败";
  } finally {
    cesiumBusy.value = false;
  }
}

onMounted(loadData);
</script>

<template>
  <div>
    <div class="content-grid">
      <section class="panel system-panel">
        <div class="panel-header compact">
          <div>
            <h2>灾害点详情</h2>
            <p>基础档案、编辑与空间信息</p>
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
            <button
              class="secondary-btn"
              :disabled="cesiumBusy"
              @click="open3DView"
            >
              <MapPinned :size="15" />{{ cesiumBusy ? "加载中..." : "打开 3D" }}
            </button>
          </div>
        </div>
        <div v-if="loading" style="padding: 0 20px 18px; color: #7a8d89">
          加载中...
        </div>
        <template v-else-if="point">
          <div class="system-list">
            <div>
              <span><Mountain :size="16" />点位名称</span
              ><strong v-if="!editMode">{{ point.name }}</strong
              ><input v-else v-model="form.name" />
            </div>
            <div>
              <span><Layers3 :size="16" />灾害类型</span
              ><strong v-if="!editMode">{{ point.type }}</strong
              ><input v-else v-model="form.type" />
            </div>
            <div>
              <span><MapPin :size="16" />所属辖区</span
              ><strong v-if="!editMode">{{ point.owner }}</strong
              ><input v-else v-model="form.owner" />
            </div>
            <div>
              <span><Gauge :size="16" />当前状态</span
              ><strong v-if="!editMode">{{ point.status }}</strong
              ><input v-else v-model="form.status" />
            </div>
            <div>
              <span><Link2 :size="16" />空间坐标</span
              ><strong v-if="!editMode"
                >{{ point.lon }}, {{ point.lat }}</strong
              >
              <div v-else class="modal-row">
                <input v-model="form.lon" /><input v-model="form.lat" />
              </div>
            </div>
          </div>
          <div class="task-summary">
            <div class="progress-ring">
              <span v-if="latestForecast != null"
                >{{ latestForecast.toFixed(1) }}<small>mm</small></span
              >
              <span v-else style="color: #a4b3b0">--</span>
            </div>
            <div class="task-overview">
              <strong v-if="!editMode">{{ point.level }}</strong>
              <select v-else v-model="form.level">
                <option value="高风险">高风险</option>
                <option value="关注">关注</option>
                <option value="正常">正常</option>
              </select>
              <span>空间位置：{{ point.lon }}, {{ point.lat }}</span>
              <div class="task-bars">
                <i
                  v-if="latestForecast != null"
                  :style="{
                    width: forecastPercent + '%',
                    background:
                      latestForecast > 15
                        ? '#e06060'
                        : latestForecast > 12
                          ? '#e0a050'
                          : '#6dbfa5',
                  }"
                ></i>
                <i v-else style="width: 0%"></i>
              </div>
              <p v-if="latestForecast != null" style="color: #57817d">
                AI 预测未来 {{ aiForecast.horizon }} 天位移
                {{ latestForecast.toFixed(1) }} mm
              </p>
              <p v-else style="color: #d88a6a">
                AI 识别服务不可用，不提供虚拟监测数据
              </p>
            </div>
          </div>

          <!-- AI 综合研判（多维度） -->
          <div v-if="aiComprehensive" class="ai-hazard-card">
            <div class="ai-card-head">
              <div>
                <small><BrainCircuit :size="13" />AI 综合风险研判</small>
                <strong :class="`ai-tag ai-tag-${(aiComprehensive.riskLevel || '').toLowerCase()}`">
                  {{ aiLevelLabel(aiComprehensive.riskLevel) }} · {{ Number(aiComprehensive.riskScore || 0).toFixed(1) }} / 100
                </strong>
              </div>
              <span v-if="aiComprehensive.analyzedAt" class="ai-card-time">{{ new Date(aiComprehensive.analyzedAt).toLocaleString('zh-CN', { hour12: false }) }}</span>
            </div>
            <div v-if="aiComprehensive.dimensions" class="ai-hazard-dims">
              <div class="ai-dim-cell">
                <small>图像现况</small>
                <strong :style="{ color: aiLevelClass(aiComprehensive.dimensions.image?.level) === 'orange' ? '#d57252' : aiLevelClass(aiComprehensive.dimensions.image?.level) === 'yellow' ? '#c69132' : '#34575a' }">
                  {{ aiLevelLabel(aiComprehensive.dimensions.image?.level) }} {{ Number(aiComprehensive.dimensions.image?.score ?? 0).toFixed(0) }}
                </strong>
              </div>
              <div class="ai-dim-cell">
                <small>当地降水量</small>
                <strong :style="{ color: aiLevelClass(aiComprehensive.dimensions.precipitation?.level) === 'orange' ? '#d57252' : aiLevelClass(aiComprehensive.dimensions.precipitation?.level) === 'yellow' ? '#c69132' : '#34575a' }">
                  {{ aiLevelLabel(aiComprehensive.dimensions.precipitation?.level) }} {{ Number(aiComprehensive.dimensions.precipitation?.score ?? 0).toFixed(0) }}
                </strong>
              </div>
              <div class="ai-dim-cell">
                <small>当地地理情况</small>
                <strong :style="{ color: aiLevelClass(aiComprehensive.dimensions.geographic?.level) === 'orange' ? '#d57252' : aiLevelClass(aiComprehensive.dimensions.geographic?.level) === 'yellow' ? '#c69132' : '#34575a' }">
                  {{ aiLevelLabel(aiComprehensive.dimensions.geographic?.level) }} {{ Number(aiComprehensive.dimensions.geographic?.score ?? 0).toFixed(0) }}
                </strong>
              </div>
              <div class="ai-dim-cell">
                <small>设备指标</small>
                <strong :style="{ color: aiLevelClass(aiComprehensive.dimensions.device?.level) === 'orange' ? '#d57252' : aiLevelClass(aiComprehensive.dimensions.device?.level) === 'yellow' ? '#c69132' : '#34575a' }">
                  {{ aiLevelLabel(aiComprehensive.dimensions.device?.level) }} {{ Number(aiComprehensive.dimensions.device?.score ?? 0).toFixed(0) }}
                </strong>
              </div>
            </div>
            <div v-if="aiComprehensive.reasoning?.length" class="ai-hazard-reasons">
              <small>关键归因：</small>
              <ul>
                <li v-for="(r, i) in aiComprehensive.reasoning.slice(0, 4)" :key="i">{{ r }}</li>
              </ul>
            </div>
            <div v-if="aiComprehensive.suggestedActions?.length" class="ai-hazard-actions">
              <small>建议动作：</small>
              <span v-for="(a, i) in aiComprehensive.suggestedActions.slice(0, 4)" :key="i" class="ai-action-pill">{{ a }}</span>
            </div>
            <div class="heading-actions" style="padding: 6px 0 0; justify-content: flex-end; gap: 6px;">
              <span v-if="aiComprehensive.model" style="font-size: 10px; color: #99a7a1;">模型：{{ aiComprehensive.model }} · 置信度 {{ ((aiComprehensive.confidence ?? 0) * 100).toFixed(0) }}%</span>
              <PermissionButton permission="ai:insight:refresh" :icon="BrainCircuit" @click="async ({ allowed, reason }) => {
                if (!allowed) return
                await refreshAiComprehensive()
              }">立即重算</PermissionButton>
            </div>
          </div>
          <div v-else-if="aiComprehensiveLoading" class="ai-hazard-card" style="text-align: center; color: #7a8d89; font-size: 12px;">AI 综合研判加载中...</div>
          <div v-else class="ai-hazard-card" style="text-align: center; color: #99a7a1; font-size: 11px;">
            等待 AI 综合研判（5 分钟内自动更新，<a href="javascript:void(0)" @click="refreshAiComprehensive" style="color: #4c8b87; text-decoration: underline;">立即重算</a>）
          </div>

          <!-- AI 预测趋势 -->
          <div class="chart-card" style="margin: 0 20px 18px">
            <div class="mini-chart-header">
              <strong
                ><TrendingUp :size="16" />AI 位移趋势预测（未来 7 天）</strong
              >
              <span v-if="aiForecast?.mock" style="color: #e0a050"
                >启发式降级</span
              >
              <span v-else-if="aiForecast" style="color: #6dbfa5">{{
                aiForecast.model
              }}</span>
            </div>
            <div v-if="aiForecastLoading" style="padding: 12px; color: #7a8d89">
              AI 预测中...
            </div>
            <div v-else-if="aiForecast" style="padding: 8px 12px">
              <div
                style="
                  display: flex;
                  align-items: flex-end;
                  gap: 8px;
                  height: 80px;
                "
              >
                <div
                  v-for="(val, i) in aiForecast.predictions"
                  :key="i"
                  style="
                    flex: 1;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    gap: 2px;
                  "
                >
                  <span style="font-size: 10px; color: #7a8d89">{{
                    val.toFixed(1)
                  }}</span>
                  <div
                    style="
                      width: 100%;
                      background: #6dbfa5;
                      border-radius: 3px 3px 0 0;
                      min-height: 3px;
                    "
                    :style="{
                      height:
                        (val / Math.max(...aiForecast.predictions)) * 70 + 'px',
                      background:
                        val > 15 ? '#e06060' : val > 12 ? '#e0a050' : '#6dbfa5',
                    }"
                  ></div>
                  <span style="font-size: 9px; color: #7a8d89"
                    >D{{ i + 1 }}</span
                  >
                </div>
              </div>
              <div
                style="
                  display: flex;
                  justify-content: space-between;
                  margin-top: 6px;
                  font-size: 11px;
                  color: #7a8d89;
                "
              >
                <span
                  >置信度 {{ (aiForecast.confidence * 100).toFixed(0) }}%</span
                >
                <span
                  >metric={{ aiForecast.metric }}, horizon={{
                    aiForecast.horizon
                  }}</span
                >
              </div>
            </div>
            <div v-else style="padding: 12px; color: #7a8d89">
              AI 服务不可用，已跳过预测
            </div>
          </div>

          <p v-if="cesiumMessage" class="weather-error" style="padding: 0 20px">
            {{ cesiumMessage }}
          </p>
          <div
            class="heading-actions"
            style="padding: 0 20px 18px; justify-content: flex-end"
          >
            <PermissionButton
              permission="hazards:edit"
              :icon="Save"
              @click="
                async ({ allowed, reason }) => {
                  if (!allowed) return;
                  try {
                    await saveDetail();
                  } catch (error) {
                    alert(error instanceof Error ? error.message : reason);
                  }
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
            <h2>历史记录与附件</h2>
            <p>查看完整历史轨迹、附件与联动信息</p>
          </div>
        </div>
        <div v-if="point" class="warning-list">
          <article
            v-for="record in point.history"
            :key="`${record.time}-${record.title}`"
            class="warning-item"
          >
            <div class="warning-symbol orange">H</div>
            <div class="warning-body">
              <div class="warning-title">
                <strong>{{ record.title }}</strong
                ><span class="orange">{{ record.time }}</span>
              </div>
              <p>{{ record.desc }}</p>
            </div>
          </article>
        </div>
        <div v-if="point" class="chart-card">
          <div class="mini-chart-header">
            <strong><Paperclip :size="16" />附件</strong>
            <span>{{ point.attachments.length }} 个文件</span>
          </div>
          <div class="pie-list">
            <div
              v-for="file in point.attachments"
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
        <div v-if="point" class="chart-card">
          <div class="mini-chart-header">
            <strong><GitBranch :size="16" />关联联动</strong>
            <span>点击跳转 · 设备 / 任务 / 预警</span>
          </div>
          <div v-if="point.linked?.length" class="pie-list">
            <RouterLink
              v-for="item in point.linked"
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
