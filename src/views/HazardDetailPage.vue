<script setup>
import { onMounted, ref } from "vue";
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
import PermissionButton from "../components/PermissionButton.vue";
import { dashboardApi } from "../services/dashboardApi";
import { aiApi } from "../services/aiApi";

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
}

async function loadAiForecast() {
  if (!point.value) return;
  aiForecastLoading.value = true;
  try {
    // 合成历史数据（真实场景应从设备指标拉取）
    const base = 10.0 + Math.random() * 5;
    const history = Array.from({ length: 30 }, (_, i) =>
      round1(base + 0.1 * i + Math.random() * 0.3),
    );
    aiForecast.value = await aiApi.forecast(
      point.value.deviceCode || "DEV-001",
      "displacement",
      7,
      history,
    );
  } catch (e) {
    aiForecast.value = null;
  } finally {
    aiForecastLoading.value = false;
  }
}

function round1(v) {
  return Math.round(v * 10) / 10;
}

async function saveDetail() {
  if (!point.value) return;
  const result = await dashboardApi.updateHazard(point.value.id, form.value);
  point.value = { ...point.value, ...result.item };
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
      await import("../services/mapService");
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
              <span
                >{{
                  point.level === "高风险"
                    ? "86"
                    : point.level === "关注"
                      ? "63"
                      : "41"
                }}<small>%</small></span
              >
            </div>
            <div class="task-overview">
              <strong v-if="!editMode">{{ point.level }}</strong>
              <select v-else v-model="form.level">
                <option value="高风险">高风险</option>
                <option value="关注">关注</option>
                <option value="正常">正常</option>
              </select>
              <span>空间位置：{{ point.lon }}, {{ point.lat }}</span>
              <div class="task-bars"><i style="width: 72%"></i></div>
              <p>最近巡检时间：今天 09:30</p>
            </div>
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
