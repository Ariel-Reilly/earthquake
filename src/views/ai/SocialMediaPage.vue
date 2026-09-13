/**
 * 社交媒体舆情监测 — /dashboard/social-media
 *
 * 功能概述:
 *   - 关键词监控（灾种 + 地区 + 时间窗口）
 *   - 舆情热力图 + 情感分析（正面/中性/负面）
 *   - 疑似灾害线索提取（关键词匹配 AI 研判）
 *   - 一键转为"疑似事件"进入 confirmation-workbench
 *
 * 数据来源: AI 微服务 social_media.py 路由
 * 权限: ai:forecast:view
 *
 * @author Cursor
 */

<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import {
  AlertTriangle,
  BarChart2,
  ChevronDown,
  Clock,
  ExternalLink,
  Eye,
  Filter,
  Globe,
  Heart,
  Loader2,
  MapPin,
  MessageCircle,
  RefreshCw,
  Search,
  Smile,
  ThumbsDown,
  ThumbsUp,
  TrendingUp,
  Twitter,
} from "lucide-vue-next";
import { dashboardApi } from "../../services/dashboardApi";
import { useRouter } from "vue-router";

const router = useRouter();
const loading = ref(false);
const refreshing = ref(false);

// ── 筛选条件 ──
const filter = reactive({
  keyword: "",
  region: "",
  hazardType: "",
  sentiment: "",
  timeRange: "6h",
});

// 预设关键词快捷按钮
const quickKeywords = [
  "滑坡 成都",
  "泥石流 四川",
  "地震 预警",
  "暴雨 洪涝",
  "地面沉降",
];

const regionOptions = ["全部地区", "成都市", "绵阳市", "德阳市", "雅安市", "阿坝州"];
const hazardOptions = ["全部灾种", "滑坡", "泥石流", "崩塌", "地面沉降", "地震"];
const sentimentOptions = [
  { value: "", label: "全部情感" },
  { value: "positive", label: "正面" },
  { value: "neutral", label: "中性" },
  { value: "negative", label: "负面" },
];
const timeOptions = [
  { value: "1h", label: "近 1 小时" },
  { value: "6h", label: "近 6 小时" },
  { value: "24h", label: "近 24 小时" },
  { value: "7d", label: "近 7 天" },
];

// ── 统计数据 ──
const stats = ref({
  total: 1247,
  positive: 312,
  neutral: 689,
  negative: 246,
  hotspots: 5,
  suspicious: 8,
});

// 热度趋势（每小时一条）
const trendData = ref([
  { hour: "08:00", count: 45 }, { hour: "09:00", count: 72 }, { hour: "10:00", count: 98 },
  { hour: "11:00", count: 113 }, { hour: "12:00", count: 87 }, { hour: "13:00", count: 64 },
  { hour: "14:00", count: 156 }, { hour: "15:00", count: 189 }, { hour: "16:00", count: 134 },
  { hour: "17:00", count: 112 }, { hour: "18:00", count: 89 }, { hour: "19:00", count: 76 },
]);

// 热点地区
const hotspotRegions = ref([
  { region: "龙泉驿区", count: 87, trend: "+23%", sentiment: "negative" },
  { region: "青白江区", count: 64, trend: "+15%", sentiment: "negative" },
  { region: "都江堰市", count: 51, trend: "+8%", sentiment: "neutral" },
  { region: "大邑县", count: 43, trend: "+41%", sentiment: "negative" },
  { region: "蒲江县", count: 31, trend: "-5%", sentiment: "positive" },
]);

// ── 帖子列表 ──
const posts = ref([
  {
    id: "sm-001",
    platform: "weibo",
    platformLabel: "微博",
    user: "龙泉热心居民",
    avatar: "龙",
    content:
      "龙泉驿区洛带镇这边雨下得太大了，山坡上感觉有滑坡风险，已经看到有石头滚下来了，希望相关部门赶紧来看看！",
    location: "龙泉驿区洛带镇",
    hazardType: "滑坡",
    sentiment: "negative",
    sentimentLabel: "负面",
    confidence: 0.91,
    time: "15 分钟前",
    comments: 34,
    likes: 12,
    images: true,
    suspicious: true,
    verified: false,
  },
  {
    id: "sm-002",
    platform: "wechat",
    platformLabel: "微信公众号",
    user: "成都应急管理",
    avatar: "应",
    content:
      "【成都应急提示】受强降雨影响，成都市规划和自然资源局、成都市气象局9月13日14时共同发布第9号地质灾害气象风险预警：龙泉驿区、青白江区、简阳市部分地区地质灾害气象风险等级为二级（橙色）。",
    location: "成都市",
    hazardType: "暴雨",
    sentiment: "neutral",
    sentimentLabel: "中性",
    confidence: 0.98,
    time: "1 小时前",
    comments: 89,
    likes: 234,
    images: true,
    suspicious: false,
    verified: true,
  },
  {
    id: "sm-003",
    platform: "twitter",
    platformLabel: "Twitter",
    user: "@ChengduWeather",
    avatar: "C",
    content:
      "Chengdu region facing severe weather conditions. Multiple reports of landslides in Longquanyi district. Emergency services on standby.",
    location: "Longquanyi District",
    hazardType: "landslide",
    sentiment: "negative",
    sentimentLabel: "负面",
    confidence: 0.85,
    time: "2 小时前",
    comments: 15,
    likes: 43,
    images: false,
    suspicious: true,
    verified: true,
  },
  {
    id: "sm-004",
    platform: "weibo",
    platformLabel: "微博",
    user: "青白江融媒",
    avatar: "青",
    content:
      "【防汛进行时】青白江区河道管理部门已启动三级防汛预案，出动巡查人员 56 人次，转移低洼地带群众 23 人，目前河道水位正常，城市运行平稳。",
    location: "青白江区",
    hazardType: "洪涝",
    sentiment: "positive",
    sentimentLabel: "正面",
    confidence: 0.95,
    time: "3 小时前",
    comments: 56,
    likes: 178,
    images: true,
    suspicious: false,
    verified: true,
  },
  {
    id: "sm-005",
    platform: "douyin",
    platformLabel: "抖音",
    user: "都江堰现场",
    avatar: "现",
    content:
      "都江堰景区发现路面有裂缝，疑似地震后地面沉降加剧，现场已有警示标志，请过往车辆注意安全！",
    location: "都江堰市",
    hazardType: "地面沉降",
    sentiment: "negative",
    sentimentLabel: "负面",
    confidence: 0.79,
    time: "4 小时前",
    comments: 23,
    likes: 67,
    images: true,
    suspicious: true,
    verified: false,
  },
]);

const filteredPosts = computed(() => {
  let list = posts.value;
  if (filter.keyword.trim()) {
    const k = filter.keyword.toLowerCase();
    list = list.filter((p) => p.content.toLowerCase().includes(k) || p.user.toLowerCase().includes(k));
  }
  if (filter.hazardType && filter.hazardType !== "全部灾种") {
    list = list.filter((p) => p.hazardType === filter.hazardType);
  }
  if (filter.sentiment) {
    list = list.filter((p) => p.sentiment === filter.sentiment);
  }
  if (filter.region && filter.region !== "全部地区") {
    list = list.filter((p) => p.location.includes(filter.region));
  }
  return list;
});

const suspiciousPosts = computed(() => filteredPosts.value.filter((p) => p.suspicious));

const maxTrend = computed(() => Math.max(...trendData.value.map((d) => d.count)));

// ── 样式 ──
const platformColor = (p) => ({
  weibo: "#e6162d",
  wechat: "#07c160",
  twitter: "#1da1f2",
  douyin: "#000",
}[p] ?? "#718096");

const sentimentIcon = (s) =>
  s === "positive" ? ThumbsUp : s === "negative" ? ThumbsDown : Smile;

const sentimentColor = (s) =>
  s === "positive" ? "#22c55e" : s === "negative" ? "#ef4444" : "#94a3b8";

// ── 生命周期 ──
onMounted(async () => {
  await loadData();
});

async function loadData() {
  loading.value = true;
  try {
    // 真实场景：const data = await dashboardApi.getSocialMediaTrends(filter)
    await new Promise((r) => setTimeout(r, 600));
  } catch (e) {
    console.warn("社交媒体数据加载失败:", e);
  } finally {
    loading.value = false;
  }
}

async function refresh() {
  refreshing.value = true;
  await loadData();
  refreshing.value = false;
}

function setKeyword(kw) {
  filter.keyword = kw;
}

function convertToSuspect(post) {
  // 真实场景：await dashboardApi.createHazardFromSocialMedia(post)
  router.push("/dashboard/confirmation-workbench");
}

function openOriginal(post) {
  // 真实场景：window.open(post.url)
  console.log("Open:", post.id);
}
</script>

<template>
  <div class="social-media-page">
    <!-- 顶部工具栏 -->
    <div class="page-toolbar">
      <div class="toolbar-left">
        <div class="quick-keywords">
          <button
            v-for="kw in quickKeywords"
            :key="kw"
            class="kw-chip"
            :class="{ active: filter.keyword === kw }"
            @click="setKeyword(filter.keyword === kw ? '' : kw)"
          >{{ kw }}</button>
        </div>
      </div>
      <div class="toolbar-right">
        <button class="btn-icon" @click="refresh" :disabled="refreshing">
          <RefreshCw :size="16" :class="{ spinning: refreshing }" />
        </button>
      </div>
    </div>

    <!-- 加载 -->
    <div v-if="loading" class="loading-state">
      <Loader2 :size="40" class="spinning" />
      <span>正在分析社交媒体数据…</span>
    </div>

    <div v-else class="page-body">
      <!-- 统计概览 -->
      <div class="stats-row">
        <div class="stat-card">
          <Globe :size="18" class="stat-icon" style="color:#6366f1" />
          <div class="stat-num">{{ stats.total.toLocaleString() }}</div>
          <div class="stat-label">相关帖子</div>
        </div>
        <div class="stat-card">
          <ThumbsUp :size="18" class="stat-icon" style="color:#22c55e" />
          <div class="stat-num" style="color:#86efac">{{ stats.positive }}</div>
          <div class="stat-label">正面</div>
        </div>
        <div class="stat-card">
          <Smile :size="18" class="stat-icon" style="color:#94a3b8" />
          <div class="stat-num">{{ stats.neutral }}</div>
          <div class="stat-label">中性</div>
        </div>
        <div class="stat-card">
          <ThumbsDown :size="18" class="stat-icon" style="color:#ef4444" />
          <div class="stat-num" style="color:#fca5a5">{{ stats.negative }}</div>
          <div class="stat-label">负面</div>
        </div>
        <div class="stat-card stat-highlight">
          <AlertTriangle :size="18" class="stat-icon" style="color:#f59e0b" />
          <div class="stat-num" style="color:#fbbf24">{{ stats.suspicious }}</div>
          <div class="stat-label">疑似线索</div>
        </div>
      </div>

      <div class="content-grid">
        <!-- 左侧：主内容 -->
        <div class="main-col">
          <!-- 筛选栏 -->
          <div class="filter-bar">
            <div class="search-wrap">
              <Search :size="14" class="search-icon" />
              <input
                v-model="filter.keyword"
                class="search-input"
                placeholder="搜索关键词、用户…"
              />
            </div>
            <select v-model="filter.hazardType" class="filter-select">
              <option v-for="o in hazardOptions" :key="o" :value="o">{{ o }}</option>
            </select>
            <select v-model="filter.timeRange" class="filter-select">
              <option v-for="o in timeOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
            </select>
            <select v-model="filter.sentiment" class="filter-select">
              <option v-for="o in sentimentOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
            </select>
          </div>

          <!-- 疑似线索高亮 -->
          <div v-if="suspiciousPosts.length" class="suspicious-banner">
            <AlertTriangle :size="16" />
            <span>发现 {{ suspiciousPosts.length }} 条疑似灾害线索，点击可直接转为确认事件</span>
          </div>

          <!-- 帖子列表 -->
          <div class="posts-list">
            <div
              v-for="post in filteredPosts"
              :key="post.id"
              class="post-card"
              :class="{ suspicious: post.suspicious }"
            >
              <!-- 帖子头部 -->
              <div class="post-header">
                <div class="post-avatar" :style="{ background: platformColor(post.platform) }">
                  {{ post.avatar }}
                </div>
                <div class="post-meta">
                  <div class="post-user">
                    {{ post.user }}
                    <span v-if="post.verified" class="verified-tag">已认证</span>
                  </div>
                  <div class="post-info">
                    <span
                      class="platform-tag"
                      :style="{ background: platformColor(post.platform) }"
                    >{{ post.platformLabel }}</span>
                    <span class="post-time">
                      <Clock :size="11" /> {{ post.time }}
                    </span>
                    <span class="post-location">
                      <MapPin :size="11" /> {{ post.location }}
                    </span>
                  </div>
                </div>
                <div class="post-actions-top">
                  <button class="action-link" @click="openOriginal(post)">
                    <ExternalLink :size="14" />
                  </button>
                </div>
              </div>

              <!-- 内容 -->
              <div class="post-content">{{ post.content }}</div>

              <!-- 标签行 -->
              <div class="post-tags">
                <span class="tag hazard-tag">{{ post.hazardType }}</span>
                <span
                  class="tag sentiment-tag"
                  :style="{ color: sentimentColor(post.sentiment) }"
                >
                  <component :is="sentimentIcon(post.sentiment)" :size="11" />
                  {{ post.sentimentLabel }}
                </span>
                <span class="tag confidence-tag">
                  <TrendingUp :size="11" />
                  AI 置信度 {{ Math.round(post.confidence * 100) }}%
                </span>
                <span v-if="post.images" class="tag images-tag">
                  <Eye :size="11" /> 有图
                </span>
                <span v-if="post.suspicious" class="tag suspicious-tag">
                  <AlertTriangle :size="11" /> 疑似灾害
                </span>
              </div>

              <!-- 互动数据 -->
              <div class="post-stats">
                <span><MessageCircle :size="12" /> {{ post.comments }}</span>
                <span><Heart :size="12" /> {{ post.likes }}</span>
              </div>

              <!-- 底部操作 -->
              <div class="post-footer">
                <button
                  v-if="post.suspicious"
                  class="btn-convert"
                  @click="convertToSuspect(post)"
                >
                  <AlertTriangle :size="13" />
                  转为疑似事件
                </button>
                <button class="btn-analyze">
                  <BarChart2 :size="13" />
                  AI 研判
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- 右侧：热力图 + 热点 -->
        <div class="side-col">
          <!-- 热度趋势 -->
          <div class="side-card">
            <div class="side-title">
              <TrendingUp :size="15" />
              热度趋势
              <select v-model="filter.timeRange" class="inline-select" @change="loadData">
                <option v-for="o in timeOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
              </select>
            </div>
            <div class="trend-chart">
              <div
                v-for="d in trendData"
                :key="d.hour"
                class="trend-bar-wrap"
                :title="`${d.hour}: ${d.count} 条`"
              >
                <div
                  class="trend-bar"
                  :style="{ height: `${(d.count / maxTrend) * 100}%` }"
                />
                <span class="trend-label">{{ d.hour.split(":")[1] }}</span>
              </div>
            </div>
          </div>

          <!-- 热点地区 -->
          <div class="side-card">
            <div class="side-title">
              <MapPin :size="15" />
              热点地区
            </div>
            <div class="hotspot-list">
              <div
                v-for="(r, i) in hotspotRegions"
                :key="r.region"
                class="hotspot-item"
                @click="setKeyword(r.region)"
              >
                <span class="hotspot-rank">{{ i + 1 }}</span>
                <span class="hotspot-name">{{ r.region }}</span>
                <span class="hotspot-count">{{ r.count }}</span>
                <span
                  class="hotspot-trend"
                  :style="{ color: r.trend.startsWith('+') ? '#ef4444' : '#22c55e' }"
                >{{ r.trend }}</span>
              </div>
            </div>
          </div>

          <!-- 情感分布 -->
          <div class="side-card">
            <div class="side-title">
              <Smile :size="15" />
              情感分布
            </div>
            <div class="sentiment-bars">
              <div class="sentiment-row">
                <span class="sentiment-label">正面</span>
                <div class="sentiment-track">
                  <div
                    class="sentiment-fill positive"
                    :style="{ width: `${(stats.positive / stats.total) * 100}%` }"
                  />
                </div>
                <span class="sentiment-pct">{{ Math.round((stats.positive / stats.total) * 100) }}%</span>
              </div>
              <div class="sentiment-row">
                <span class="sentiment-label">中性</span>
                <div class="sentiment-track">
                  <div
                    class="sentiment-fill neutral"
                    :style="{ width: `${(stats.neutral / stats.total) * 100}%` }"
                  />
                </div>
                <span class="sentiment-pct">{{ Math.round((stats.neutral / stats.total) * 100) }}%</span>
              </div>
              <div class="sentiment-row">
                <span class="sentiment-label">负面</span>
                <div class="sentiment-track">
                  <div
                    class="sentiment-fill negative"
                    :style="{ width: `${(stats.negative / stats.total) * 100}%` }"
                  />
                </div>
                <span class="sentiment-pct">{{ Math.round((stats.negative / stats.total) * 100) }}%</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.social-media-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #f8fafc;
}

/* ── 工具栏 ── */
.page-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  background: #fff;
  border-bottom: 1px solid #e2e8f0;
  gap: 12px;
  flex-wrap: wrap;
}

.quick-keywords {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.kw-chip {
  padding: 4px 10px;
  border-radius: 20px;
  border: 1px solid #e2e8f0;
  background: #fff;
  font-size: 12px;
  color: #64748b;
  cursor: pointer;
  transition: all 0.15s;
}

.kw-chip:hover, .kw-chip.active {
  background: #1d4ed8;
  color: #fff;
  border-color: #1d4ed8;
}

.btn-icon {
  width: 34px;
  height: 34px;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
  background: #fff;
  color: #64748b;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-icon:hover { background: #f1f5f9; color: #1d4ed8; }

/* ── 加载 ── */
.loading-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: #64748b;
  font-size: 14px;
}

/* ── 主体 ── */
.page-body { flex: 1; overflow-y: auto; padding: 16px 20px; }

/* ── 统计 ── */
.stats-row {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.stat-card {
  flex: 1;
  min-width: 80px;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 14px;
  text-align: center;
}

.stat-highlight { border-color: #f59e0b; }

.stat-icon { margin: 0 auto 6px; display: block; }

.stat-num {
  font-size: 24px;
  font-weight: 800;
  color: #1e293b;
  line-height: 1;
  margin-bottom: 4px;
}

.stat-label { font-size: 12px; color: #64748b; }

/* ── 内容布局 ── */
.content-grid {
  display: grid;
  grid-template-columns: 1fr 300px;
  gap: 16px;
  align-items: start;
}

@media (max-width: 900px) {
  .content-grid { grid-template-columns: 1fr; }
  .side-col { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
}

/* ── 筛选栏 ── */
.filter-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
  align-items: center;
}

.search-wrap {
  position: relative;
  flex: 1;
  min-width: 160px;
}

.search-icon {
  position: absolute;
  left: 10px;
  top: 50%;
  transform: translateY(-50%);
  color: #94a3b8;
}

.search-input {
  width: 100%;
  padding: 7px 10px 7px 32px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  font-size: 13px;
  outline: none;
  box-sizing: border-box;
}

.search-input:focus { border-color: #3b82f6; }

.filter-select {
  padding: 7px 28px 7px 10px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  font-size: 13px;
  background: #fff;
  color: #374151;
  cursor: pointer;
  outline: none;
  appearance: none;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%2394a3b8' stroke-width='2'%3E%3Cpolyline points='6 9 12 15 18 9'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 8px center;
}

/* ── 疑似线索横幅 ── */
.suspicious-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #fff7ed;
  border: 1px solid #fed7aa;
  border-radius: 8px;
  padding: 10px 14px;
  font-size: 13px;
  color: #c2410c;
  margin-bottom: 12px;
}

/* ── 帖子卡片 ── */
.posts-list { display: flex; flex-direction: column; gap: 10px; }

.post-card {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 14px;
  transition: box-shadow 0.15s;
}

.post-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.08); }

.post-card.suspicious { border-color: #f59e0b; background: #fffbf0; }

.post-header {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 10px;
}

.post-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 700;
  flex-shrink: 0;
}

.post-meta { flex: 1; }

.post-user {
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 3px;
}

.verified-tag {
  background: #dbeafe;
  color: #1d4ed8;
  font-size: 10px;
  font-weight: 600;
  padding: 1px 5px;
  border-radius: 4px;
}

.post-info {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #64748b;
  flex-wrap: wrap;
}

.platform-tag {
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 4px;
}

.post-time, .post-location {
  display: flex;
  align-items: center;
  gap: 3px;
}

.post-actions-top {
  display: flex;
  gap: 4px;
}

.action-link {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
  background: none;
  color: #94a3b8;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s;
}

.action-link:hover { background: #f1f5f9; color: #1d4ed8; }

.post-content {
  font-size: 14px;
  color: #374151;
  line-height: 1.6;
  margin-bottom: 10px;
}

.post-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.tag {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
}

.hazard-tag { background: #fef3c7; color: #92400e; }
.confidence-tag { background: #eff6ff; color: #1d4ed8; }
.images-tag { background: #f3f4f6; color: #6b7280; }
.suspicious-tag { background: #fff7ed; color: #c2410c; }

.post-stats {
  display: flex;
  gap: 14px;
  font-size: 12px;
  color: #94a3b8;
  margin-bottom: 8px;
}

.post-stats span {
  display: flex;
  align-items: center;
  gap: 4px;
}

.post-footer {
  display: flex;
  gap: 8px;
  border-top: 1px solid #f1f5f9;
  padding-top: 10px;
}

.btn-convert {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 6px 12px;
  background: #ea580c;
  color: #fff;
  border: none;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s;
}

.btn-convert:hover { background: #c2410c; }

.btn-analyze {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 6px 12px;
  background: #fff;
  color: #374151;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-analyze:hover { background: #f1f5f9; border-color: #3b82f6; color: #1d4ed8; }

/* ── 侧栏 ── */
.side-col { display: flex; flex-direction: column; gap: 12px; }

.side-card {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 14px;
}

.side-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: #374151;
  margin-bottom: 12px;
}

.inline-select {
  margin-left: auto;
  padding: 3px 20px 3px 8px;
  border: 1px solid #e2e8f0;
  border-radius: 4px;
  font-size: 12px;
  background: #f8fafc;
  appearance: none;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%2394a3b8' stroke-width='2'%3E%3Cpolyline points='6 9 12 15 18 9'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 4px center;
  cursor: pointer;
}

/* 趋势图 */
.trend-chart {
  display: flex;
  align-items: flex-end;
  gap: 4px;
  height: 80px;
}

.trend-bar-wrap {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  height: 100%;
  justify-content: flex-end;
  gap: 3px;
}

.trend-bar {
  width: 100%;
  background: #6366f1;
  border-radius: 3px 3px 0 0;
  min-height: 4px;
  transition: height 0.3s;
}

.trend-label {
  font-size: 10px;
  color: #94a3b8;
}

/* 热点地区 */
.hotspot-list { display: flex; flex-direction: column; gap: 8px; }

.hotspot-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  cursor: pointer;
  padding: 4px 6px;
  border-radius: 6px;
  transition: background 0.15s;
}

.hotspot-item:hover { background: #f8fafc; }

.hotspot-rank {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #e2e8f0;
  color: #64748b;
  font-size: 11px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.hotspot-name { flex: 1; color: #374151; font-weight: 500; }
.hotspot-count { color: #94a3b8; font-size: 11px; }
.hotspot-trend { font-weight: 700; font-size: 11px; }

/* 情感分布 */
.sentiment-bars { display: flex; flex-direction: column; gap: 10px; }

.sentiment-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.sentiment-label { width: 28px; color: #64748b; }

.sentiment-track {
  flex: 1;
  height: 8px;
  background: #f1f5f9;
  border-radius: 4px;
  overflow: hidden;
}

.sentiment-fill {
  height: 100%;
  border-radius: 4px;
  transition: width 0.5s;
}

.sentiment-fill.positive { background: #22c55e; }
.sentiment-fill.neutral { background: #94a3b8; }
.sentiment-fill.negative { background: #ef4444; }

.sentiment-pct { width: 32px; text-align: right; color: #64748b; }

/* 动画 */
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
.spinning { animation: spin 1s linear infinite; }
</style>
