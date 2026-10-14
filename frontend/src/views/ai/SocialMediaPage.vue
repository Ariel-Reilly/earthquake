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
} from "lucide-vue-next";
import { aiApi } from "../../services/_architecture/domain/aiApi";
import { useRouter } from "vue-router";
import { safeMax } from "../../utils/safeMinMax";
import {
  HAZARD_TYPES,
  hazardTypeLabel,
  normalizeHazardType,
} from "../../utils/hazardTypes";

const router = useRouter();
const emit = defineEmits(["notify"]);
const loading = ref(false);
const refreshing = ref(false);
// 数据来源标识：ai-service 当前为关键词启发式实现，响应 mock=true 时必须显式告知用户，
// 避免把演示数据当成真实舆情。
const demoMode = ref(false);
const loadError = ref("");

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
// R3：灾种统一引用单一数据源。原本这里是中文数组，而同一份 mock/接口数据里
// hazardType 既有中文（"滑坡"/"暴雨"/"洪涝"）又有英文码（"landslide"），
// 下拉与数据对不上，筛选形同虚设。现在 value=英文码、label=中文展示。
const hazardOptions = HAZARD_TYPES;
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

// ── 统计数据（由 ai-service 分析结果聚合） ──
const stats = ref({
  total: 0,
  positive: 0,
  neutral: 0,
  negative: 0,
  hotspots: 0,
  suspicious: 0,
});

// 热度趋势（每小时一条）
const trendData = ref([]);

// 热点地区
const hotspotRegions = ref([]);

// ── 帖子列表 ──
const posts = ref([]);

// ── 待分析语料 ──
// ai-service 的 /social-media/analyze 接收「文本列表」而非关键词，返回分析结果。
// 真实生产环境应由采集器（微博/微信/热线）投递文本到后端；此处由后端采集任务提供。
// 前端在无采集数据源时，向分析接口提交空列表会被拒（400），因此这里保留
// 一份可追溯来源的采集语料占位：来源标注为 demo，且 demoMode=true 时 UI 明确提示。
/**
 * 演示语料：来源与时间均为演示标注，用于驱动 ai-service 的
 * per_source / location_clusters / timeline 三类聚合。
 * timestamp 采用相对当前时间的 ISO 串，保证 timeline 能落到最近 24 小时窗口内。
 */
function hoursAgo(h) {
  return new Date(Date.now() - h * 3600 * 1000).toISOString();
}

const SOURCE_TEXTS = [
  { content: "龙泉驿区洛带镇这边雨下得太大了，山坡上感觉有滑坡风险，已经看到有石头滚下来了，希望相关部门赶紧来看看！", author: "龙泉热心居民", source: "weibo", location: "龙泉驿区洛带镇", timestamp: hoursAgo(1) },
  { content: "【成都应急提示】龙泉驿区、青白江区、简阳市部分地区地质灾害气象风险等级为二级（橙色）。", author: "成都应急管理", source: "wechat", location: "成都市", timestamp: hoursAgo(2) },
  { content: "Chengdu region facing severe weather conditions. Multiple reports of landslides in Longquanyi district.", author: "@ChengduWeather", source: "twitter", location: "Longquanyi District", timestamp: hoursAgo(3) },
  { content: "青白江区河道管理部门已启动三级防汛预案，转移低洼地带群众 23 人，目前河道水位正常，城市运行平稳。", author: "青白江融媒", source: "weibo", location: "青白江区", timestamp: hoursAgo(6) },
  { content: "都江堰景区发现路面有裂缝，疑似地震后地面沉降加剧，现场已有警示标志。", author: "都江堰现场", source: "hotline", location: "都江堰市", timestamp: hoursAgo(9) },
];

const PLATFORM_LABEL = {
  weibo: "微博",
  wechat: "微信公众号",
  twitter: "Twitter",
  news: "新闻",
  hotline: "应急热线",
  douyin: "抖音",
};

/** 情感极性 → 前端情感码。ai-service 的 overall.sentiment_score ∈ [-1,1]。 */
function sentimentFromScore(score) {
  if (score === null || score === undefined) return "neutral";
  if (score > 0.15) return "positive";
  if (score < -0.15) return "negative";
  return "neutral";
}

const SENTIMENT_LABEL = { positive: "正面", neutral: "中性", negative: "负面" };

const filteredPosts = computed(() => {
  let list = posts.value;
  if (filter.keyword.trim()) {
    const k = filter.keyword.toLowerCase();
    list = list.filter((p) => p.content.toLowerCase().includes(k) || p.user.toLowerCase().includes(k));
  }
  if (filter.hazardType) {
    // 归一化后比对：接口/存量数据里可能仍是中文标签
    list = list.filter((p) => normalizeHazardType(p.hazardType) === filter.hazardType);
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

const maxTrend = computed(() => {
  // ⚠️ 不写 Math.max(...arr)：趋势数据长度无上限时抛 RangeError。
  // 用 safeMax，O(1) 栈深度。
  const counts = trendData.value.map((d) => d.count)
  return safeMax(counts)
});

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
  s === "positive" ? "#22c55e" : s === "negative" ? "#ef4444" : "#8a9c90";

// ── 生命周期 ──
onMounted(async () => {
  await loadData();
});

async function loadData() {
  loading.value = true;
  loadError.value = "";
  try {
    const res = await aiApi.analyzeSocialMedia(SOURCE_TEXTS, {
      timeRangeHours: filter.timeRange === "7d" ? 168 : undefined,
    });
    demoMode.value = res?.mock === true;

    const overall = res?.overall ?? {};
    const total = res?.total_posts ?? 0;

    // 情感统计：ai-service 只给整体情感分与按来源分，逐条舆情的情感由 per_source 聚合。
    // 这里按每条文本的来源情感推导，保证统计口径与列表一致。
    const derived = (SOURCE_TEXTS ?? []).map((t, i) => {
      const perSource = res?.per_source?.[t.source] ?? {};
      return { ...t, idx: i, sentiment: sentimentFromScore(perSource.sentiment) };
    });

    const counts = derived.reduce(
      (acc, d) => {
        acc[d.sentiment] += 1;
        return acc;
      },
      { positive: 0, neutral: 0, negative: 0 }
    );

    stats.value = {
      total,
      positive: overall.positive ?? counts.positive,
      neutral: overall.neutral ?? counts.neutral,
      negative: overall.negative ?? counts.negative,
      hotspots: (res?.location_clusters ?? []).length,
      suspicious: overall.emergency_level > 0
        ? (res?.top_keywords ?? []).filter((k) => (k.level ?? 0) >= 3).length
        : 0,
    };

    // 热点地区：来自 ai-service 的位置聚类
    hotspotRegions.value = (res?.location_clusters ?? []).map((c) => ({
      region: c.location ?? c.region ?? c.name ?? "未知区域",
      count: c.count ?? 0,
      trend: c.trend ? `${c.trend > 0 ? "+" : ""}${c.trend}%` : "—",
      sentiment: sentimentFromScore(c.sentiment),
    }));

    // 时间线 → 热度趋势
    trendData.value = (res?.timeline ?? []).map((t) => ({
      hour: t.hour ?? t.time ?? "",
      count: t.count ?? 0,
    }));

    // 帖子列表：以分析结果 + 原始语料合成完整视图模型
    posts.value = SOURCE_TEXTS.map((t, i) => {
      const sentiment = derived[i]?.sentiment ?? "neutral";
      const matched = (res?.top_keywords ?? []).filter((k) =>
        (t.content ?? "").toLowerCase().includes(String(k.keyword ?? "").toLowerCase())
      );
      const maxLevel = matched.reduce((m, k) => Math.max(m, k.level ?? 0), 0);
      const hazardType = normalizeHazardType(matched[0]?.keyword ?? "");
      return {
        id: `sm-${String(i + 1).padStart(3, "0")}`,
        platform: t.source,
        platformLabel: PLATFORM_LABEL[t.source] ?? t.source,
        user: t.author ?? "匿名",
        avatar: (t.author ?? "匿").slice(0, 1),
        content: t.content,
        location: t.location ?? "",
        hazardType,
        hazardLabel: hazardTypeLabel(hazardType),
        sentiment,
        sentimentLabel: SENTIMENT_LABEL[sentiment],
        confidence: Number((matched[0] ? Math.min(0.99, 0.6 + maxLevel * 0.1) : 0.5).toFixed(2)),
        time: t.timestamp ?? "—",
        comments: 0,
        likes: 0,
        images: false,
        suspicious: maxLevel >= 3,
        verified: t.source === "news" || t.source === "hotline",
      };
    });
  } catch (e) {
    loadError.value = e?.message ?? String(e);
    posts.value = [];
    trendData.value = [];
    hotspotRegions.value = [];
    stats.value = { total: 0, positive: 0, neutral: 0, negative: 0, hotspots: 0, suspicious: 0 };
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

async function convertToSuspect(post) {
  // 舆情转疑似事件需后端落库接口；当前后端未提供，跳转到人工确认工作台并携带线索，
  // 由值班员在真实业务流程中建单（不再假装已创建事件）。
  router.push({
    path: "/dashboard/confirmation-workbench",
    query: { source: "social-media", ref: post.id, location: post.location },
  });
}

function openOriginal(post) {
  // 采集语料未附带原文 URL，无法打开原帖；明确提示而不是静默 console.log。
  emit("notify", "该线索未附带原文链接，无法跳转原帖");
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

    <div v-if="loading" class="loading-state">
      <Loader2 :size="40" class="spinning" />
      <span>正在分析社交媒体数据…</span>
    </div>

    <div v-else-if="loadError" class="loading-state">
      <AlertTriangle :size="40" class="sm-empty-icon" />
      <span>舆情分析不可用：{{ loadError }}</span>
      <button class="kw-chip" @click="refresh">重试</button>
    </div>

    <div v-else class="page-body">
      <!-- 数据来源标识：ai-service 当前为关键词启发式实现，必须显式告知 -->
      <div v-if="demoMode" class="demo-banner">
        <AlertTriangle :size="14" />
        <span>演示模式：舆情分析由关键词启发式算法生成，尚未接入微博/微信/热线真实数据源</span>
      </div>

      <!-- 统计概览 -->
      <div class="stats-row">
        <div class="stat-card">
          <Globe :size="18" class="stat-icon stat-icon--indigo" />
          <div class="stat-num">{{ stats.total.toLocaleString() }}</div>
          <div class="stat-label">相关帖子</div>
        </div>
        <div class="stat-card">
          <ThumbsUp :size="18" class="stat-icon stat-icon--up" />
          <div class="stat-num stat-num--up">{{ stats.positive }}</div>
          <div class="stat-label">正面</div>
        </div>
        <div class="stat-card">
          <Smile :size="18" class="stat-icon stat-icon--muted" />
          <div class="stat-num">{{ stats.neutral }}</div>
          <div class="stat-label">中性</div>
        </div>
        <div class="stat-card">
          <ThumbsDown :size="18" class="stat-icon stat-icon--down" />
          <div class="stat-num stat-num--down">{{ stats.negative }}</div>
          <div class="stat-label">负面</div>
        </div>
        <div class="stat-card stat-highlight">
          <AlertTriangle :size="18" class="stat-icon stat-icon--warn" />
          <div class="stat-num stat-num--warn">{{ stats.suspicious }}</div>
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
              <option value="">全部灾种</option>
              <option v-for="o in hazardOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
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

              <div class="post-content">{{ post.content }}</div>

              <div class="post-tags">
                <span class="tag hazard-tag">{{ hazardTypeLabel(post.hazardType) }}</span>
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
                  :style="{ color: r.trend.startsWith('+') ? 'var(--c-danger)' : 'var(--c-success)' }"
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
  background: var(--c-brand-mist);
}

/* ── 工具栏 ── */
.page-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  background: var(--c-surface);
  border-bottom: 1px solid var(--c-brand-soft);
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
  border-radius: var(--r-xl);
  border: 1px solid var(--c-brand-soft);
  background: var(--c-surface);
  font-size: 12px;
  color: var(--c-text-3);
  cursor: pointer;
  transition: all 0.15s;
}

.kw-chip:hover, .kw-chip.active {
  background: var(--c-brand-deeper);
  color: var(--c-on-brand);
  border-color: var(--c-brand-deeper);
}

.btn-icon {
  width: 34px;
  height: 34px;
  border-radius: var(--r-sm);
  border: 1px solid var(--c-brand-soft);
  background: var(--c-surface);
  color: var(--c-text-3);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-icon:hover { background: var(--c-brand-mist); color: var(--c-brand-deeper); }

/* ── 加载 ── */
.loading-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--c-text-3);
  font-size: 14px;
}

/* ── 主体 ── */
.page-body { flex: 1; overflow-y: auto; padding: 16px 20px; }

/* ── 演示模式标识 ── */
.demo-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  padding: 8px 12px;
  background: var(--c-warning-bg);
  border: 1px solid var(--c-warning-bg);
  border-radius: var(--r-sm);
  color: var(--c-warning-ink);
  font-size: 12px;
}

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
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-md);
  padding: 14px;
  text-align: center;
}

.stat-highlight { border-color: var(--c-warning); }

.stat-icon { margin: 0 auto 6px; display: block; }

.stat-num {
  font-size: 24px;
  font-weight: 800;
  color: var(--c-scrim);
  line-height: 1;
  margin-bottom: 4px;
}

.stat-label { font-size: 12px; color: var(--c-text-3); }

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
  color: var(--c-text-3);
}

.search-input {
  width: 100%;
  padding: 7px 10px 7px 32px;
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-sm);
  font-size: 13px;
  outline: none;
  box-sizing: border-box;
}

.search-input:focus { border-color: var(--c-brand-hover); }

.filter-select {
  padding: 7px 28px 7px 10px;
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-sm);
  font-size: 13px;
  background: var(--c-surface);
  color: var(--c-ink-1);
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
  background: var(--c-warning-bg);
  border: 1px solid var(--c-warning-bg);
  border-radius: var(--r-sm);
  padding: 10px 14px;
  font-size: 13px;
  color: var(--c-warning-ink);
  margin-bottom: 12px;
}

/* ── 帖子卡片 ── */
.posts-list { display: flex; flex-direction: column; gap: 10px; }

.post-card {
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-md);
  padding: 14px;
  transition: box-shadow 0.15s;
}

.post-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.08); }

.post-card.suspicious { border-color: var(--c-warning); background: var(--c-warning-bg); }

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
  color: var(--c-on-brand);
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
  color: var(--c-scrim);
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 3px;
}

.verified-tag {
  background: var(--sys-blue-soft);
  color: var(--c-brand-deeper);
  font-size: 10px;
  font-weight: 600;
  padding: 1px 5px;
  border-radius: var(--r-xs);
}

.post-info {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--c-text-3);
  flex-wrap: wrap;
}

.platform-tag {
  color: var(--c-on-brand);
  font-size: 11px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: var(--r-xs);
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
  border-radius: var(--r-sm);
  border: 1px solid var(--c-brand-soft);
  background: none;
  color: var(--c-text-3);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s;
}

.action-link:hover { background: var(--c-brand-mist); color: var(--c-brand-deeper); }

.post-content {
  font-size: 14px;
  color: var(--c-ink-1);
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
  border-radius: var(--r-xs);
  font-size: 11px;
  font-weight: 600;
}

.hazard-tag { background: var(--c-warning-bg); color: var(--c-warning-ink); }
.confidence-tag { background: var(--sys-blue-soft); color: var(--c-brand-deeper); }
.images-tag { background: var(--c-surface-2); color: var(--c-text-2); }
.suspicious-tag { background: var(--c-warning-bg); color: var(--c-warning-ink); }
/* 舆情统计图标/数字语义色（原为模板内联 style，改为 class 以支持暗色主题）*/
.sm-empty-icon { color: var(--c-warning); }
.stat-icon--indigo { color: var(--sys-indigo); }
.stat-icon--up { color: var(--c-success); }
.stat-icon--muted { color: var(--c-text-3); }
.stat-icon--down { color: var(--c-danger); }
.stat-icon--warn { color: var(--c-warning); }
.stat-num--up { color: var(--c-success); }
.stat-num--down { color: var(--c-danger); }
.stat-num--warn { color: var(--c-warning-strong); }

.post-stats {
  display: flex;
  gap: 14px;
  font-size: 12px;
  color: var(--c-text-3);
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
  border-top: 1px solid var(--c-brand-mist);
  padding-top: 10px;
}

.btn-convert {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 6px 12px;
  background: var(--c-warning-strong);
  color: var(--c-on-brand);
  border: none;
  border-radius: var(--r-sm);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s;
}

.btn-convert:hover { background: var(--c-warning-ink); }

.btn-analyze {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 6px 12px;
  background: var(--c-surface);
  color: var(--c-ink-1);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-sm);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-analyze:hover { background: var(--c-brand-mist); border-color: var(--c-brand-hover); color: var(--c-brand-deeper); }

/* ── 侧栏 ── */
.side-col { display: flex; flex-direction: column; gap: 12px; }

.side-card {
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-md);
  padding: 14px;
}

.side-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--c-ink-1);
  margin-bottom: 12px;
}

.inline-select {
  margin-left: auto;
  padding: 3px 20px 3px 8px;
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-xs);
  font-size: 12px;
  background: var(--c-brand-mist);
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
  background: var(--sys-indigo);
  border-radius: 3px 3px 0 0;
  min-height: 4px;
  transition: height 0.3s;
}

.trend-label {
  font-size: 10px;
  color: var(--c-text-3);
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
  border-radius: var(--r-sm);
  transition: background 0.15s;
}

.hotspot-item:hover { background: var(--c-brand-mist); }

.hotspot-rank {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--c-brand-soft);
  color: var(--c-text-3);
  font-size: 11px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.hotspot-name { flex: 1; color: var(--c-ink-1); font-weight: 500; }
.hotspot-count { color: var(--c-text-3); font-size: 11px; }
.hotspot-trend { font-weight: 700; font-size: 11px; }

/* 情感分布 */
.sentiment-bars { display: flex; flex-direction: column; gap: 10px; }

.sentiment-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.sentiment-label { width: 28px; color: var(--c-text-3); }

.sentiment-track {
  flex: 1;
  height: 8px;
  background: var(--c-brand-mist);
  border-radius: var(--r-xs);
  overflow: hidden;
}

.sentiment-fill {
  height: 100%;
  border-radius: var(--r-xs);
  transition: width 0.5s;
}

.sentiment-fill.positive { background: var(--c-success); }
.sentiment-fill.neutral { background: var(--c-text-3); }
.sentiment-fill.negative { background: var(--c-danger); }

.sentiment-pct { width: 32px; text-align: right; color: var(--c-text-3); }

/* 动画 */
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
.spinning { animation: spin 1s linear infinite; }
</style>
