<script setup>
import { computed, nextTick, onMounted, onBeforeUnmount, ref, reactive, watch } from "vue";
import { useRouter, useRoute } from "vue-router";
import { useBodyScrollLock } from "../composables/useBodyScrollLock";
import {
  ArrowRight,
  ArrowUpRight,
  BarChart3,
  Bell,
  Bot,
  ChevronDown,
  ChevronRight,
  Database,
  Globe,
  Layers,
  Lock,
  MapPin,
  Minus,
  MountainSnow,
  Plus,
  Radar,
  Radio,
  Satellite,
  Server,
  ShieldCheck,
  ShieldAlert,
  Zap,
  X,
  User,
  Users,
  Wrench,
  Eye,
  EyeOff,
  LogIn,
} from "lucide-vue-next";
import BrandMascot from "../components/BrandMascot.vue";
import AnimatedLoginBackground from "../components/AnimatedLoginBackground.vue";
import { loginRequest } from "../services/dashboardApi";
import { ROLE_OPTIONS, getHomePathByRole, setAuthSession } from "../stores/auth";

const router = useRouter();
const route = useRoute();
const sections = ref([]);
const observer = ref(null);
const scrollY = ref(0);
const openFaq = ref(0);
const showMobileNav = ref(false);

/* ====== 视差滚动 ====== */
const heroFxRef = ref(null);
let rafId = null;

function updateParallax() {
  if (!heroFxRef.value) return;
  const rect = heroFxRef.value.getBoundingClientRect();
  const scrollDist = Math.max(0, -rect.top);
  const parallaxY = Math.min(scrollDist * 0.3, 300);
  heroFxRef.value.style.setProperty('--parallax-y', `${parallaxY}px`);
  rafId = null;
}

function onParallaxScroll() {
  if (rafId) return;
  rafId = requestAnimationFrame(updateParallax);
}

/* ====== KPI 数字动画与趋势指示 ====== */
const KPI_TARGETS = { monitors: 128, devices: 346, alerts: 12, rate: 95 };
const kpiNumbers = reactive({ monitors: 0, devices: 0, alerts: 0, rate: 0 });
const kpiTrend = reactive({
  devices: 'up',
  alerts: 'down',
});
const kpiPulse = ref(0);
let kpiInterval = null;
let kpiCounted = false;
let kpiCounting = false;
/** 计数是异步的：计数期间用户可能已滚走，结束后需据此决定是否再启动跳动定时器 */
let kpiWanted = false;

/**
 * 首次进入视口时的快速滚动计数。
 * 时长压到 900ms（统计条为 1600ms），让预览窗口的数字"一进来就在跑"。
 * 计数期间不启动随机跳动，否则 clamp 会把还没涨到位的数字拉回区间。
 * @returns {Promise<void>} 计数完成后 resolve
 */
function countUpKpi() {
  return new Promise((resolve) => {
    const duration = 900;
    const start = performance.now();
    function step(now) {
      const progress = Math.min((now - start) / duration, 1);
      const eased = 1 - Math.pow(1 - progress, 3);
      Object.keys(KPI_TARGETS).forEach((k) => {
        kpiNumbers[k] = Math.round(KPI_TARGETS[k] * eased);
      });
      if (progress < 1) requestAnimationFrame(step);
      else resolve();
    }
    requestAnimationFrame(step);
  });
}

async function startKpiAnimation() {
  kpiWanted = true;
  if (kpiInterval || kpiCounting) return;
  if (!kpiCounted) {
    kpiCounting = true;
    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reduced) Object.assign(kpiNumbers, KPI_TARGETS);
    else await countUpKpi();
    kpiCounted = true;
    kpiCounting = false;
    if (reduced) return;
  }
  if (!kpiWanted || kpiInterval) return;
  // 本页是匿名可达的落地页，KPI_TARGETS 是**示意基线**，不是从后端取的真实指标。
  // 用确定性交替代替 Math.random()：保留"数字在动"的视觉效果，但不伪造随机业务数据。
  kpiInterval = setInterval(() => {
    const tick = kpiPulse.value + 1;
    const devChange = tick % 2 === 0 ? 1 : -1;
    kpiNumbers.devices = Math.max(340, Math.min(350, kpiNumbers.devices + devChange));
    kpiTrend.devices = devChange > 0 ? 'up' : 'down';
    const alertChange = tick % 3 === 0 ? 1 : -1;
    kpiNumbers.alerts = Math.max(10, Math.min(15, kpiNumbers.alerts + alertChange));
    kpiTrend.alerts = alertChange > 0 ? 'up' : 'down';
    kpiPulse.value = tick;
  }, 2400);
}

function stopKpiAnimation() {
  kpiWanted = false;
  if (kpiInterval) {
    clearInterval(kpiInterval);
    kpiInterval = null;
  }
}

/* ====== 终端日志流动效 ====== */
/** 递增 id 作为 v-for 的 key：日志池里的条目会被重复取用，不能用内容当 key */
let logSeq = 0;
/*
 * 日志条目用结构化字段（tag / text / value / badge）而不是在模板里拆字符串，
 * 这样改文案不会悄悄破坏高亮位置。
 */
const terminalLogs = ref(
  [
    { type: 'prompt', text: '$ hazard.monitor --realtime' },
    { type: 'comment', text: '// 位移传感器 · 在线 346 / 离线 2' },
    { type: 'warn', tag: 'WARNING', text: '牛栏湾滑坡 · 位移超阈值', badge: '高' },
    { type: 'info', text: 'AI 置信度', value: '94.7%' },
  ].map((l) => ({ ...l, id: ++logSeq })),
);
let logInterval = null;
/** 日志池按序轮播：不再用 Math.random 随机挑，避免落地页出现"随机业务事件"的误解。 */
let logCursor = 0;
const logPool = [
  { type: 'info', text: '累计降雨', value: '32 mm' },
  { type: 'warn', tag: 'WARNING', text: '大坪坡崩塌 · 雨量激增', badge: '中' },
  { type: 'info', text: '倾斜仪异常 · 已触发复核', value: 'T-0412' },
  { type: 'comment', text: '// 巡检任务 · 已派发 3 / 待完成 1' },
  { type: 'info', text: 'GPS 轨迹核查通过', value: '牛栏湾' },
];

function startLogStream() {
  if (logInterval) return;
  logInterval = setInterval(() => {
    const randomLog = logPool[logCursor % logPool.length];
    logCursor += 1;
    terminalLogs.value.push({ ...randomLog, id: ++logSeq });
    if (terminalLogs.value.length > 6) terminalLogs.value.shift();
  }, 2600);
}

function stopLogStream() {
  if (logInterval) {
    clearInterval(logInterval);
    logInterval = null;
  }
}

/* ====== 全流程状态机（自动推进 + 可点击切换） ====== */
const flowSteps = [
  { label: "隐患发现", desc: "传感器越限或人工上报，自动建档" },
  { label: "预警研判", desc: "规则引擎与 AI 模型联合判定风险等级" },
  { label: "任务派发", desc: "按责任区推送处置任务，GPS 打卡留痕" },
  { label: "解除警报", desc: "复核通过后闭环归档，全程可追溯" },
];
const flowActive = ref(1);
let flowTimer = null;

function startFlow() {
  if (flowTimer) return;
  // 减弱动态效果偏好下不自动轮播，保留手动点击
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  flowTimer = setInterval(() => {
    flowActive.value = (flowActive.value + 1) % flowSteps.length;
  }, 2200);
}

function stopFlow() {
  if (flowTimer) {
    clearInterval(flowTimer);
    flowTimer = null;
  }
}

/** 点击后直接定位到该步骤；悬停时已暂停轮播，所以这里不用重置计时器 */
function selectFlow(index) {
  flowActive.value = index;
}

/* ====== 登录弹窗 ====== */
const showLoginModal = ref(false);
// 打开登录弹窗时锁定 body 滚动，避免背后页面继续滚动
useBodyScrollLock(showLoginModal);
const loginLoading = ref(false);
const loginError = ref("");
const showPassword = ref(false);
const remember = ref(true);
const capsLockOn = ref(false);
const loginForm = reactive({ username: "admin", password: "admin123", role: "admin" });
const REMEMBER_KEY = "disasterguard_remembered_username";

watch(
  () => route.query,
  (query) => {
    if (query.login === "true") {
      showLoginModal.value = true;
    }
  },
  { immediate: true }
);

const openLogin = () => {
  showLoginModal.value = true;
  showMobileNav.value = false;
};

const closeLogin = () => {
  showLoginModal.value = false;
  router.replace({ path: "/", query: {} });
};

const validateLogin = () => {
  if (!loginForm.username.trim()) {
    loginError.value = "请输入账号";
    return false;
  }
  if (!loginForm.password) {
    loginError.value = "请输入密码";
    return false;
  }
  return true;
};

const onCapsLock = (event) => {
  capsLockOn.value = event.getModifierState?.("CapsLock") === true;
};

const submitLogin = async () => {
  if (loginLoading.value) return;
  loginError.value = "";
  if (!validateLogin()) return;

  loginLoading.value = true;
  try {
    const session = await loginRequest({
      username: loginForm.username.trim(),
      password: loginForm.password,
      role: loginForm.role,
    });
    setAuthSession(session);

    if (remember.value) {
      window.localStorage.setItem(REMEMBER_KEY, loginForm.username.trim());
    } else {
      window.localStorage.removeItem(REMEMBER_KEY);
    }

    // 关闭弹窗并清除 query
    closeLogin();

    // 如果有重定向地址，跳转到目标页面；否则跳转到角色首页
    const redirect = route.query.redirect;
    if (redirect && redirect.startsWith("/") && !redirect.startsWith("//") && !redirect.includes("\\")) {
      router.push(redirect);
    } else {
      router.push(getHomePathByRole(session.user.role));
    }
  } catch (err) {
    loginError.value = err instanceof Error ? err.message : "登录失败，请稍后重试";
  } finally {
    loginLoading.value = false;
  }
};

/* ====== 统计数字 ====== */
const stats = [
  { value: 128, suffix: "+", label: "灾害监测点", icon: MapPin },
  { value: 350, suffix: "+", label: "IoT 传感器", icon: Radio },
  { value: 24, suffix: "h", label: "全天候监控", icon: Radar },
  { value: 99.9, suffix: "%", label: "系统可用性", icon: ShieldCheck },
];
const animatedStats = reactive(stats.map((s) => ({ ...s, current: 0 })));

/* ====== 工作流行 ====== */
const workflows = [
  {
    num: "01",
    icon: MapPin,
    title: "灾害点管理",
    desc: "滑坡、崩塌、泥石流隐患点全生命周期追踪与空间可视化，多源图层叠加，风险一目了然。",
    link: "探索灾害点管理",
    type: "map",
  },
  {
    num: "02",
    icon: Radio,
    title: "设备监测",
    desc: "IoT 设备在线管理，位移、雨量、倾斜等多源传感数据经 MQTT 实时接入，秒级入库 TDengine。",
    link: "探索设备监测",
    type: "chart",
  },
  {
    num: "03",
    icon: Bell,
    title: "智能预警",
    desc: "规则引擎 + AI 辅助研判，按风险等级分级推送，从预警生成到处置关闭全程留痕。",
    link: "探索智能预警",
    type: "alerts",
  },
  {
    num: "04",
    icon: BarChart3,
    title: "数据统计",
    desc: "多维统计图表与 Excel 导出，为应急决策提供可靠数据支撑，让每一次研判都有据可依。",
    link: "探索数据统计",
    type: "stats",
  },
];

/* ====== 平台特色三列 ====== */
const tailored = [
  {
    icon: Layers,
    title: "全流程闭环",
    desc: "从灾害点管理、巡检任务、GPS 打卡到预警研判与处置闭环，建立统一业务模型，团队步调一致。",
  },
  {
    icon: Lock,
    title: "权限治理",
    desc: "多角色权限矩阵，管理员可精细控制各角色可访问的模块与操作，所有操作均有日志记录与审计追溯。",
  },
  {
    icon: ShieldCheck,
    title: "安全可靠",
    desc: "JWT 鉴权、设备级鉴权与 MQTT 安全预检，PostgreSQL 与 TDengine 双存储保障业务数据与传感数据可靠。",
  },
];

/* ====== 角色方案（借鉴 plans 卡片） ====== */
const roles = [
  {
    name: "系统管理员",
    en: "ADMIN",
    desc: "全面掌控平台配置与数据治理，适合平台运营与决策层。",
    featured: false,
    features: ["全部模块访问", "用户与角色管理", "系统参数配置", "数据导出与审计", "预警处置管理"],
    cta: "登录管理后台",
  },
  {
    name: "值班员",
    en: "ATTENDANT",
    desc: "专注实时预警处置、态势跟踪与任务协调，适合应急值守场景。",
    featured: true,
    features: ["实时预警处置", "巡检任务管理", "应急事件处理", "GPS 轨迹核查", "风险统计查看"],
    cta: "登录值班工作台",
  },
  {
    name: "运维员",
    en: "MAINTENANCE",
    desc: "负责设备运维与现场作业，适合一线巡检与设备管理。",
    featured: false,
    features: ["巡检任务打卡", "设备状态查看", "灾害点现场登记", "GPS 定位签到", "设备异常上报"],
    cta: "登录运维终端",
  },
];

/* ====== FAQ ====== */
const faqs = [
  {
    q: "平台支持哪些地质灾害类型？",
    a: "平台覆盖滑坡、崩塌、泥石流、地面塌陷等常见地质灾害类型，支持灾害点的空间可视化、风险分级与全生命周期追踪。",
  },
  {
    q: "预警是如何产生的？",
    a: "系统内置多级规则引擎，并叠加孤立森林、XGBoost 等 AI 模型对多源传感数据进行异常研判，两者融合后按风险等级分级生成预警事件。",
  },
  {
    q: "支持哪些终端访问？",
    a: "PC 浏览器可访问完整 Web 端与实时大屏，移动端支持 H5 页面，巡检人员可在野外完成打卡、登记与轨迹上报。",
  },
  {
    q: "监测数据如何存储？",
    a: "业务数据存储于 PostgreSQL（含 PostGIS 空间扩展），高频时序传感数据写入 TDengine，支持秒级写入与高效聚合查询。",
  },
  {
    q: "如何导出统计报表？",
    a: "概览、灾害点、预警、设备等模块均提供 Excel 导出能力，可将当前筛选结果一键导出，便于归档与上报。",
  },
  {
    q: "系统是否支持多角色登录？",
    a: "支持。平台内置多角色权限体系，管理员可统一配置各角色可访问的模块与操作权限，所有操作均有日志记录与审计追溯。",
  },
];

/* ====== 交互逻辑 ====== */
const scrollToContent = () =>
  document.getElementById("workflow")?.scrollIntoView({ behavior: "smooth" });
const scrollToTop = () => window.scrollTo({ top: 0, behavior: "smooth" });
const goLogin = () => {
  showMobileNav.value = false;
  showLoginModal.value = true;
};

function onScroll() {
  scrollY.value = window.scrollY;
}

function animateStats() {
  animatedStats.forEach((stat) => {
    const duration = 1600;
    const start = performance.now();
    const target = stat.value;
    function step(now) {
      const elapsed = now - start;
      const progress = Math.min(elapsed / duration, 1);
      const eased = 1 - Math.pow(1 - progress, 3);
      stat.current = +(target * eased).toFixed(target % 1 !== 0 ? 1 : 0);
      if (progress < 1) requestAnimationFrame(step);
    }
    requestAnimationFrame(step);
  });
}

/* ====== 顶部导航 Mega Menu ====== */
/** 悬停延迟（毫秒）：过滤鼠标快速划过导航时的误触发，避免面板反复闪烁 */
const MEGA_HOVER_DELAY = 150;
/** 与样式里 `@media (max-width: 980px)` 的移动端断点互补，保证两处判断一致 */
const DESKTOP_QUERY = "(min-width: 981px)";

/**
 * 菜单数据：每个一级菜单对应三列——左侧功能分组、中间图文说明、右侧推荐卡片。
 * 文案复用页面已有内容，target 指向页面现有锚点，避免新增页面外的承诺。
 * promo 使用 action: "login" 时会唤起登录弹窗，否则按 target 滚动。
 */
const megaMenus = [
  {
    key: "features",
    label: "核心能力",
    href: "#features",
    groups: [
      {
        heading: "AI 智能研判",
        items: [
          { icon: Bot, title: "多算法融合", desc: "孤立森林、XGBoost 与多级规则引擎组合，按数据特征选择最优模型", target: "#features" },
          { icon: Bell, title: "分级预警", desc: "按风险等级分级推送，从预警生成到处置关闭全程留痕", target: "#workflow" },
        ],
      },
      {
        heading: "全流程防治",
        items: [
          { icon: Radar, title: "全流程闭环", desc: "灾害点管理、巡检任务、GPS 打卡到预警研判与处置", target: "#features" },
          { icon: Globe, title: "多端协同", desc: "Web 大屏、移动端 H5 与 WebSocket 实时推送", target: "#features" },
        ],
      },
    ],
    story: {
      heading: "平台亮点",
      icon: Bot,
      title: "不止一种算法",
      desc: "孤立森林、XGBoost 与多级规则引擎组合，更快、更准地识别异常。",
      points: ["物联网 + AI 融合", "多级规则引擎", "企业级安全防护"],
    },
    promo: {
      badge: "推荐",
      icon: ShieldAlert,
      title: "体验实时监测总览",
      desc: "登录演示账号，查看灾害点、设备与预警的实时态势。",
      cta: "进入系统",
      action: "login",
    },
  },
  {
    key: "workflow",
    label: "工作流程",
    href: "#workflow",
    groups: [
      {
        heading: "监测与感知",
        items: [
          { icon: MapPin, title: "灾害点管理", desc: "滑坡、崩塌、泥石流隐患点全生命周期追踪与空间可视化", target: "#workflow" },
          { icon: Radio, title: "设备监测", desc: "位移、雨量、倾斜等传感数据经 MQTT 实时接入", target: "#workflow" },
        ],
      },
      {
        heading: "研判与决策",
        items: [
          { icon: Bell, title: "智能预警", desc: "规则引擎 + AI 辅助研判，按风险等级分级推送", target: "#workflow" },
          { icon: BarChart3, title: "数据统计", desc: "多维统计图表与 Excel 导出，为应急决策提供数据支撑", target: "#workflow" },
        ],
      },
    ],
    story: {
      heading: "从感知到处置",
      icon: Layers,
      title: "全链路智能化工作流",
      desc: "覆盖灾害点管理、巡检任务、GPS 打卡到预警研判、派发处置的完整防治链条。",
      points: ["秒级入库 TDengine", "分级预警推送", "处置全程留痕"],
    },
    promo: {
      badge: "演示",
      icon: Bell,
      title: "查看实时预警",
      desc: "了解预警如何从生成流转到处置关闭。",
      cta: "登录查看",
      action: "login",
    },
  },
  {
    key: "architecture",
    label: "系统架构",
    href: "#architecture",
    groups: [
      {
        heading: "三层架构",
        items: [
          { icon: Satellite, title: "感知层", desc: "IoT 传感器 · MQTT 接入", target: "#architecture" },
          { icon: Server, title: "平台层", desc: "规则引擎 · AI 模型 · 时序库", target: "#architecture" },
          { icon: Globe, title: "应用层", desc: "预警 · 巡检 · 应急 · 大屏", target: "#architecture" },
        ],
      },
      {
        heading: "数据存储",
        items: [
          { icon: Database, title: "双存储保障", desc: "PostgreSQL（含 PostGIS）存业务数据，TDengine 存时序数据", target: "#architecture" },
        ],
      },
    ],
    story: {
      heading: "数据贯通",
      icon: Server,
      title: "端到端监测架构",
      desc: "感知层 → 平台层 → 应用层，全链路数据贯通。",
      points: ["MQTT 实时接入", "PostgreSQL + TDengine 双存储", "JWT 与设备级鉴权"],
    },
    promo: {
      badge: "FAQ",
      icon: Database,
      title: "数据如何存储？",
      desc: "查看常见问题中关于存储与导出的解答。",
      cta: "查看常见问题",
      target: "#faq",
    },
  },
  {
    key: "roles",
    label: "角色方案",
    href: "#roles",
    groups: [
      {
        heading: "平台角色",
        items: [
          { icon: Users, title: "系统管理员", desc: "全面掌控平台配置与数据治理", target: "#roles" },
          { icon: Bell, title: "值班员", desc: "实时预警处置、态势跟踪与任务协调", target: "#roles" },
          { icon: Wrench, title: "运维员", desc: "设备运维、现场巡检与 GPS 签到", target: "#roles" },
        ],
      },
      {
        heading: "权限治理",
        items: [
          { icon: Lock, title: "多角色权限矩阵", desc: "精细控制各角色可访问的模块与操作，操作可审计", target: "#roles" },
        ],
      },
    ],
    story: {
      heading: "协同工作",
      icon: Users,
      title: "多角色协同工作",
      desc: "管理员、值班员、运维员各司其职，支撑地质灾害防治全团队协作。",
      points: ["管理员统一配置权限", "值班员实时处置预警", "运维员现场巡检打卡"],
    },
    promo: {
      badge: "推荐",
      icon: Zap,
      title: "进入值班工作台",
      desc: "使用演示账号体验值班员视角。",
      cta: "登录系统",
      action: "login",
    },
  },
];

const headerRef = ref(null);
const megaPanelRef = ref(null);
const isDesktop = ref(true);
/** 当前展开的菜单 key；null 表示收起 */
const activeMega = ref(null);
const megaVisible = computed(() => isDesktop.value && activeMega.value !== null);

let megaTimer = null;
let desktopMql = null;
/** Esc 后程序化把焦点还给触发器时，需要避免 focus 事件把面板重新打开 */
let suppressFocusOpen = false;

const navScrolled = computed(() => scrollY.value > 24);

/** 清除悬停计时器，保证同一时刻只有一个待执行的开/关动作 */
function clearMegaTimer() {
  if (megaTimer) {
    clearTimeout(megaTimer);
    megaTimer = null;
  }
}

/**
 * 延迟展开指定菜单。
 * 延迟用于过滤鼠标快速划过；每次调用都会取消上一个待执行动作，
 * 因此扫过多个触发器时只有最后停留的那个会展开。
 * @param {string} key 菜单 key
 */
function scheduleOpenMega(key) {
  if (!isDesktop.value) return;
  clearMegaTimer();
  megaTimer = setTimeout(() => {
    activeMega.value = key;
    megaTimer = null;
  }, MEGA_HOVER_DELAY);
}

/** 延迟收起；鼠标从触发器移入面板的间隙内会被 cancel 掉，不会闪退 */
function scheduleCloseMega() {
  clearMegaTimer();
  if (activeMega.value === null) return;
  megaTimer = setTimeout(() => {
    activeMega.value = null;
    megaTimer = null;
  }, MEGA_HOVER_DELAY);
}

/** 立即收起，用于点击菜单项、Esc、点击外部等明确的关闭意图 */
function closeMega() {
  clearMegaTimer();
  activeMega.value = null;
}

/**
 * 键盘聚焦触发器时立即展开。
 * 仅响应 :focus-visible，避免鼠标点击后获得的焦点绕过 150ms 延迟。
 * @param {FocusEvent} event 焦点事件
 * @param {string} key 菜单 key
 */
function onTriggerFocus(event, key) {
  if (suppressFocusOpen || !isDesktop.value) return;
  if (!event.target.matches?.(":focus-visible")) return;
  clearMegaTimer();
  activeMega.value = key;
}

/**
 * 触屏设备没有 hover，首次点击只展开面板，再次点击才跳转；
 * 鼠标点击则直接关闭面板并沿用锚点的默认跳转。
 * @param {PointerEvent} event 点击事件
 * @param {string} key 菜单 key
 */
function onTriggerClick(event, key) {
  showMobileNav.value = false;
  if (!isDesktop.value) return;
  if (event.pointerType === "touch" || event.pointerType === "pen") {
    if (activeMega.value !== key) {
      event.preventDefault();
      clearMegaTimer();
      activeMega.value = key;
      return;
    }
  }
  closeMega();
}

/** 触发器上按 ↓ 时展开并把焦点移到面板第一个链接，符合菜单的键盘习惯 */
async function onTriggerArrowDown(event, key) {
  if (!isDesktop.value) return;
  event.preventDefault();
  clearMegaTimer();
  activeMega.value = key;
  await nextTick();
  megaPanelRef.value
    ?.querySelector(".mega-grid.is-active a, .mega-grid.is-active button")
    ?.focus();
}

/**
 * 面板内菜单项/推荐卡片的统一点击处理。
 * @param {{ target?: string, action?: string }} item 菜单项或推荐卡片
 */
function onMegaItem(item) {
  closeMega();
  showMobileNav.value = false;
  if (item.action === "login") {
    showLoginModal.value = true;
    return;
  }
  if (item.target) {
    document.querySelector(item.target)?.scrollIntoView({ behavior: "smooth" });
  }
}

/** Esc 关闭面板并把焦点还给触发器，避免键盘用户丢失位置 */
function onGlobalKeydown(event) {
  if (event.key !== "Escape" || activeMega.value === null) return;
  const key = activeMega.value;
  closeMega();
  const trigger = headerRef.value?.querySelector(`[data-mega-key="${key}"]`);
  if (trigger && headerRef.value.contains(document.activeElement)) {
    suppressFocusOpen = true;
    trigger.focus();
    suppressFocusOpen = false;
  }
}

/** 点击导航之外的区域（含触屏）时收起面板 */
function onGlobalPointerdown(event) {
  if (activeMega.value === null) return;
  if (headerRef.value && !headerRef.value.contains(event.target)) closeMega();
}

/** Tab 键把焦点移出整个导航时收起；relatedTarget 为空（点击不可聚焦区域）时不处理 */
function onHeaderFocusout(event) {
  const next = event.relatedTarget;
  if (next && headerRef.value && !headerRef.value.contains(next)) closeMega();
}

/** 视口缩到移动端断点时同步状态并收起面板，移动端仍使用原有的抽屉菜单 */
function syncDesktop() {
  isDesktop.value = desktopMql.matches;
  if (!isDesktop.value) closeMega();
}

onMounted(() => {
  desktopMql = window.matchMedia(DESKTOP_QUERY);
  syncDesktop();
  desktopMql.addEventListener("change", syncDesktop);
  window.addEventListener("keydown", onGlobalKeydown);
  window.addEventListener("pointerdown", onGlobalPointerdown);

  observer.value = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add("is-visible");
          // 统计条数字动画（仅触发一次）
          if (
            entry.target.dataset.statSection === "true" &&
            !animatedStats[0].current
          ) {
            animateStats();
          }
          // KPI 数字动画（进入视口时启动）
          if (entry.target.dataset.kpiSection === "true") {
            startKpiAnimation();
          }
          // 深色区块终端日志流（进入视口时启动）
          if (entry.target.dataset.darkSection === "true") {
            startLogStream();
          }
          // 全流程状态机自动推进
          if (entry.target.dataset.flowSection === "true") {
            startFlow();
          }
        } else {
          // 离开视口时停止动画，避免后台空转
          if (entry.target.dataset.kpiSection === "true") {
            stopKpiAnimation();
          }
          if (entry.target.dataset.darkSection === "true") {
            stopLogStream();
          }
          if (entry.target.dataset.flowSection === "true") {
            stopFlow();
          }
        }
      });
    },
    { threshold: 0.15 },
  );
  sections.value = document.querySelectorAll(".reveal");
  sections.value.forEach((el) => observer.value.observe(el));
  window.addEventListener("scroll", onScroll, { passive: true });
  window.addEventListener("scroll", onParallaxScroll, { passive: true });
});

onBeforeUnmount(() => {
  if (observer.value) {
    sections.value.forEach((el) => observer.value.unobserve(el));
    observer.value.disconnect();
  }
  window.removeEventListener("scroll", onScroll);
  window.removeEventListener("scroll", onParallaxScroll);
  window.removeEventListener("keydown", onGlobalKeydown);
  window.removeEventListener("pointerdown", onGlobalPointerdown);
  stopKpiAnimation();
  stopLogStream();
  stopFlow();
  if (rafId) cancelAnimationFrame(rafId);
  desktopMql?.removeEventListener("change", syncDesktop);
  clearMegaTimer();
});
</script>

<template>
  <div class="welcome-page" :style="{ '--scroll-y': scrollY + 'px' }">
    <!-- 背景层（与登录页一致的粒子动画背景，固定铺满视口） -->
    <div class="bg" aria-hidden="true">
      <AnimatedLoginBackground />
    </div>

    <!-- 顶部导航 -->
    <header
      ref="headerRef"
      class="topnav"
      :class="{ scrolled: navScrolled, 'mega-open': megaVisible }"
      @mouseenter="clearMegaTimer"
      @mouseleave="scheduleCloseMega"
      @focusout="onHeaderFocusout"
    >
      <div class="topnav-inner">
        <button class="brand" @click="scrollToTop" @mouseenter.stop="scheduleCloseMega">
          <span class="brand-mark"><MountainSnow :size="20" /></span>
          <span class="brand-copy">
            <strong>御灾智巡</strong>
            <small>YuZaiGuard</small>
          </span>
        </button>

        <nav class="nav-links" :class="{ open: showMobileNav }">
          <a
            v-for="m in megaMenus"
            :key="m.key"
            :href="m.href"
            class="nav-trigger"
            :class="{ active: megaVisible && activeMega === m.key }"
            :data-mega-key="m.key"
            aria-haspopup="true"
            :aria-expanded="megaVisible && activeMega === m.key"
            @mouseenter="scheduleOpenMega(m.key)"
            @focus="onTriggerFocus($event, m.key)"
            @click="onTriggerClick($event, m.key)"
            @keydown.down="onTriggerArrowDown($event, m.key)"
          >
            {{ m.label }}
            <ChevronDown class="nav-chevron" :size="14" aria-hidden="true" />
          </a>
          <a href="#faq" @mouseenter="scheduleCloseMega" @click="showMobileNav = false">常见问题</a>
        </nav>

        <div class="topnav-actions" @mouseenter.stop="scheduleCloseMega">
          <button class="nav-cta" @click="goLogin()">
            进入系统 <ArrowRight :size="14" />
          </button>
          <button
            class="nav-burger"
            :class="{ open: showMobileNav }"
            @click="showMobileNav = !showMobileNav"
          >
            <span />
            <span />
            <span />
          </button>
        </div>
      </div>

      <!-- Mega Menu 下拉面板：wrap 常驻并忽略指针事件，只有面板本身响应鼠标 -->
      <div class="mega-wrap">
        <Transition name="mega">
          <div
            v-if="megaVisible"
            ref="megaPanelRef"
            class="mega-panel"
            role="region"
            aria-label="导航菜单"
          >
            <div
              v-for="m in megaMenus"
              :key="m.key"
              class="mega-grid"
              :class="{ 'is-active': activeMega === m.key }"
              :aria-hidden="activeMega !== m.key"
            >
              <!-- 左列：功能列表 -->
              <div class="mega-col mega-col-list">
                <section v-for="g in m.groups" :key="g.heading" class="mega-group">
                  <h4 class="mega-heading">{{ g.heading }}</h4>
                  <ul>
                    <li v-for="it in g.items" :key="it.title">
                      <a
                        class="mega-item"
                        :href="it.target"
                        :tabindex="activeMega === m.key ? 0 : -1"
                        @click.prevent="onMegaItem(it)"
                      >
                        <span class="mega-item-icon"><component :is="it.icon" :size="18" /></span>
                        <span class="mega-item-body">
                          <strong>{{ it.title }}</strong>
                          <small>{{ it.desc }}</small>
                        </span>
                      </a>
                    </li>
                  </ul>
                </section>
              </div>

              <!-- 中列：图文说明 -->
              <div class="mega-col mega-col-story">
                <h4 class="mega-heading">{{ m.story.heading }}</h4>
                <div class="mega-story-visual" aria-hidden="true">
                  <component :is="m.story.icon" :size="34" />
                </div>
                <h5 class="mega-story-title">{{ m.story.title }}</h5>
                <p class="mega-story-desc">{{ m.story.desc }}</p>
                <ul class="mega-story-points">
                  <li v-for="p in m.story.points" :key="p"><Zap :size="12" />{{ p }}</li>
                </ul>
              </div>

              <!-- 右列：推荐卡片 -->
              <div class="mega-col mega-col-promo">
                <button
                  type="button"
                  class="mega-promo"
                  :tabindex="activeMega === m.key ? 0 : -1"
                  @click="onMegaItem(m.promo)"
                >
                  <span class="mega-promo-badge">{{ m.promo.badge }}</span>
                  <span class="mega-promo-icon"><component :is="m.promo.icon" :size="22" /></span>
                  <strong>{{ m.promo.title }}</strong>
                  <small>{{ m.promo.desc }}</small>
                  <span class="mega-promo-cta">{{ m.promo.cta }} <ArrowRight :size="14" /></span>
                </button>
              </div>
            </div>
          </div>
        </Transition>
      </div>
    </header>

    <!-- Hero -->
    <section class="hero">
      <!-- 动效背景层：雷达波纹 / 扫描光束 / 漂浮光点。装饰性，不参与交互 -->
      <div ref="heroFxRef" class="hero-fx" aria-hidden="true">
        <div class="hero-radar">
          <span class="radar-wave" />
          <span class="radar-wave" style="animation-delay: 1.6s" />
          <span class="radar-wave" style="animation-delay: 3.2s" />
          <span class="radar-core" />
        </div>
        <span class="scan-beam beam-1" />
        <span class="scan-beam beam-2" />
        <div class="hero-dots">
          <i v-for="n in 14" :key="n" :class="'hd hd-' + n" />
        </div>
      </div>

      <div class="hero-inner">
        <span class="hero-badge reveal">
          <ShieldAlert :size="12" />
          地质灾害全流程智能监测预警平台
        </span>

        <h1 class="hero-title reveal" style="--delay: 0.1s">
          智能感知 · <span class="gradient-text shine-text">精准预警</span> · 高效应急
        </h1>

        <p class="hero-desc reveal" style="--delay: 0.2s">
          从物理世界的每一次微震，到云端算力的毫秒级研判。一站式地质灾害防治平台，为生命安全争取黄金时间。
        </p>

        <div class="hero-cta reveal" style="--delay: 0.3s">
          <button class="btn-primary" @click="goLogin()">
            <span class="btn-label">立即进入系统</span>
            <span class="btn-arrow-pulse"><ArrowRight :size="16" /></span>
          </button>
          <button class="btn-ghost" @click="scrollToContent">
            了解平台功能 <ChevronDown :size="15" />
          </button>
        </div>

        <div class="hero-trust reveal" style="--delay: 0.4s">
          <span class="hero-trust-item">物联网 + AI 融合</span>
          <span class="hero-trust-item">多级规则引擎</span>
          <span class="hero-trust-item">企业级安全防护</span>
        </div>
      </div>

      <!-- 终端样式的产品预览 -->
      <div class="hero-preview reveal" style="--delay: 0.2s" data-kpi-section="true">
        <div class="preview-frame">
          <div class="frame-bar">
            <span class="dot dot-r" /><span class="dot dot-y" /><span class="dot dot-g" />
            <span class="frame-url">disasterguard · 实时监测总览</span>
            <span class="frame-live"><i /> LIVE</span>
          </div>

          <div class="frame-body">
            <div class="frame-side">
              <div class="side-item active">
                <MapPin :size="13" /><span>灾害点</span>
              </div>
              <div class="side-item">
                <Radio :size="13" /><span>设备</span>
              </div>
              <div class="side-item">
                <Bell :size="13" /><span>预警</span>
              </div>
              <div class="side-item">
                <BarChart3 :size="13" /><span>统计</span>
              </div>
            </div>

            <div class="frame-main">
              <!-- 落地页匿名可达，下面的数字是示意基线而非实时业务指标，必须显式标注 -->
              <div style="padding:4px 10px 0;text-align:right;font-size:10px;letter-spacing:.04em;color:var(--c-text-tertiary,#8a9a90)">落地页示意数据 · 非实时业务指标</div>
              <div class="preview-top">
                <div class="preview-kpi">
                  <small>监测点</small>
                  <strong>{{ kpiNumbers.monitors }}<em class="trend up">↑</em></strong>
                </div>
                <div class="preview-kpi">
                  <small>在线设备</small>
                  <strong :key="'d' + kpiPulse" class="flash">{{ kpiNumbers.devices }}<em class="trend" :class="kpiTrend.devices">{{ kpiTrend.devices === 'up' ? '↑' : '↓' }}</em></strong>
                </div>
                <div class="preview-kpi">
                  <small>今日预警</small>
                  <strong :key="'a' + kpiPulse" class="warn flash">{{ kpiNumbers.alerts }}<em class="trend" :class="kpiTrend.alerts">{{ kpiTrend.alerts === 'up' ? '↑' : '↓' }}</em></strong>
                </div>
                <div class="preview-kpi">
                  <small>处置率</small>
                  <strong class="good">{{ kpiNumbers.rate }}%<em class="trend up">↑</em></strong>
                </div>
              </div>

              <div class="preview-map">
                <div class="map-grid" />
                <span class="map-contour c1" />
                <span class="map-contour c2" />
                <span class="map-river" />
                <span class="map-marker m1"><i /></span>
                <span class="map-marker m2"><i /></span>
                <span class="map-marker m3"><i /></span>
                <div class="map-tag t1">牛栏湾滑坡 · 高风险</div>
                <div class="map-tag t2">大坪坡崩塌 · 中风险</div>
                <div class="radar-sweep" />
              </div>

              <div class="preview-chart">
                <div class="chart-bars">
                  <span v-for="h in [34, 55, 42, 70, 52, 84, 63, 46, 76, 58]" :key="h" :style="{ height: h + '%' }" />
                </div>
                <div class="chart-meta">
                  <span>位移速率 mm/d</span>
                  <em>近 30 日 · 自动聚合</em>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <section class="stats-strip reveal" data-stat-section="true">
      <div class="stats-strip-inner">
        <div
          v-for="(s, i) in stats"
          :key="i"
          class="strip-item"
          :style="{ '--delay': i * 0.08 + 's' }"
        >
          <span class="strip-icon"><component :is="s.icon" :size="18" /></span>
          <div class="strip-num">
            <strong>{{ animatedStats[i].current }}<small>{{ s.suffix }}</small></strong>
            <span>{{ s.label }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- 核心能力（左右交替图文） -->
    <section id="features" class="section features-alt-section">
      <div class="section-head reveal">
        <span class="eyebrow">CAPABILITIES</span>
        <h2>超越一揽子方案</h2>
        <p>融合 AI、物联网与工程防治实践,为地质风险场景量身打造</p>
      </div>

      <!-- 模块一：左文右图 —— AI 融合 -->
      <div class="feature-alt-row reveal">
        <div class="feature-alt-copy">
          <span class="eyebrow">AI FUSION</span>
          <h3>多源数据融合，不止是算法组合</h3>
          <p>
            孤立森林、XGBoost 与多级规则引擎组合，按数据特征动态选择最优模型。
            不只是把模型堆在一起，而是让每一个模型都在最擅长的场景发挥作用。
          </p>
        </div>
        <div class="feature-alt-visual">
          <div class="aurora-chart" role="img" aria-label="多模型融合研判示意图：孤立森林、XGBoost、规则引擎三条曲线汇聚为一条融合研判曲线">
            <div class="aurora-wave wave-1" />
            <div class="aurora-wave wave-2" />
            <div class="aurora-wave wave-3" />
            <div class="aurora-grid" />
            <svg class="aurora-svg" viewBox="0 0 400 220" preserveAspectRatio="none" aria-hidden="true">
              <path class="ln ln-a" d="M0 150 C40 130 70 170 110 140 S180 90 230 120 S320 150 400 100" />
              <path class="ln ln-b" d="M0 170 C50 160 90 120 140 135 S210 170 260 130 S340 90 400 120" />
              <path class="ln ln-c" d="M0 130 C60 150 100 100 150 110 S230 140 280 95 S350 80 400 90" />
              <path class="ln ln-fuse" d="M0 145 C60 138 100 118 150 118 S230 128 280 104 S350 88 400 96" />
            </svg>
            <div class="aurora-legend">
              <span><i class="lg lg-a" />孤立森林</span>
              <span><i class="lg lg-b" />XGBoost</span>
              <span><i class="lg lg-c" />规则引擎</span>
              <span class="lg-fuse"><i class="lg lg-f" />融合研判</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 模块二：左图右文 —— 全流程闭环 -->
      <!-- DOM 里文字在前、图在后（移动端堆叠顺序一致，读屏顺序也自然），桌面端用 row-reverse 把图换到左侧 -->
      <div class="feature-alt-row reversed reveal" data-flow-section="true">
        <div class="feature-alt-copy">
          <span class="eyebrow">CLOSED LOOP</span>
          <h3>从隐患发现，到解除警报，一切尽在掌控</h3>
          <p>
            覆盖灾害点管理、巡检任务、GPS 打卡到预警研判、派发处置的完整防治链条。
            每一个状态流转都有据可查，让应急决策有据可依。
          </p>
        </div>
        <div class="feature-alt-visual">
          <div
            class="state-machine"
            role="group"
            aria-label="处置流程状态机"
            @mouseenter="stopFlow"
            @mouseleave="startFlow"
          >
            <div v-for="(s, i) in flowSteps" :key="s.label" class="state-step">
              <div v-if="i > 0" class="state-link" :class="{ filled: i <= flowActive }" />
              <button
                type="button"
                class="state-node"
                :class="{ active: i === flowActive, done: i < flowActive }"
                :aria-current="i === flowActive ? 'step' : undefined"
                @click="selectFlow(i)"
              >
                <span class="state-index">{{ String(i + 1).padStart(2, '0') }}</span>
                <span class="state-text">
                  <strong>{{ s.label }}</strong>
                  <small>{{ s.desc }}</small>
                </span>
                <i v-if="i < flowActive" class="state-check" aria-hidden="true">✓</i>
                <i v-else-if="i === flowActive" class="state-spinner" aria-hidden="true" />
              </button>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- 深色指挥中心区块（整屏宽，深莫兰迪绿背景） -->
    <section class="section dark-command-section reveal" data-dark-section="true">
      <div class="dark-inner">
        <div class="dark-copy">
          <span class="eyebrow eyebrow-light">COMMAND CENTER</span>
          <h2 class="dark-h2">指挥中枢 · 实时接收 · 全程追踪</h2>
          <p class="dark-desc">
            从隐患发现到预警研判、从任务派发到解除警报，每一步都在掌控之中。
            深度集成 AI 模型与多级规则引擎，让研判更智能、决策更及时。
          </p>
          <ul class="dark-list">
            <li><span class="glow-dot" />秒级入库 TDengine</li>
            <li><span class="glow-dot" />分级预警推送</li>
            <li><span class="glow-dot" />处置全程留痕</li>
          </ul>
        </div>
        <div class="dark-visual">
          <div class="terminal-dark">
            <div class="terminal-bar-dark">
              <span class="dot dot-r" />
              <span class="dot dot-y" />
              <span class="dot dot-g" />
              <span>command · 实时指挥</span>
            </div>
            <div class="terminal-body-dark">
              <div v-for="log in terminalLogs" :key="log.id" class="code-line">
                <span v-if="log.type === 'prompt'" class="prompt">{{ log.text }}</span>
                <span v-else-if="log.type === 'comment'" class="comment">{{ log.text }}</span>
                <template v-else-if="log.type === 'warn'">
                  <span class="keyword">{{ log.tag }}</span> {{ log.text }}
                  <span v-if="log.badge" class="badge-high" :class="{ mid: log.badge === '中' }">{{ log.badge }}</span>
                </template>
                <template v-else>
                  <span class="info-tag">INFO</span> {{ log.text }}
                  <span v-if="log.value" class="num">{{ log.value }}</span>
                </template>
              </div>
              <div class="code-line anim-blink"><span class="prompt">█</span></div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- 工作流程 -->
    <section id="workflow" class="section workflow-section">
      <div class="section-head reveal">
        <span class="eyebrow">WORKFLOW</span>
        <h2>编码 · 指挥 · 协同</h2>
        <p>从感知到处置，全链路智能化工作流</p>
      </div>

      <div class="workflow-list">
        <article
          v-for="(w, i) in workflows"
          :key="i"
          class="workflow-row reveal"
          :class="{ reversed: i % 2 === 1 }"
        >
          <div class="workflow-copy">
            <span class="workflow-num">{{ w.num }}</span>
            <span class="workflow-icon"><component :is="w.icon" :size="18" /></span>
            <h3>{{ w.title }}</h3>
            <p>{{ w.desc }}</p>
            <button class="explore-link" @click="goLogin()">
              {{ w.link }} <ArrowUpRight :size="14" />
            </button>
          </div>

          <div class="workflow-visual">
            <!-- 灾害点管理 -->
            <div v-if="w.type === 'map'" class="terminal-card">
              <div class="terminal-bar"><span>hazards · 灾害点分布</span></div>
              <div class="terminal-body map-visual">
                <div class="mini-map">
                  <span class="m-marker mm1" />
                  <span class="m-marker mm2" />
                  <span class="m-marker mm3" />
                  <span class="m-marker mm4" />
                  <div class="mini-map-info">
                    <div><small>高风险</small><strong>8</strong></div>
                    <div><small>中风险</small><strong>21</strong></div>
                    <div><small>低风险</small><strong>13</strong></div>
                  </div>
                </div>
              </div>
            </div>

            <!-- 设备监测 -->
            <div v-else-if="w.type === 'chart'" class="terminal-card">
              <div class="terminal-bar"><span>devices · 实时传感数据</span></div>
              <div class="terminal-body chart-visual">
                <div class="big-chart">
                  <span v-for="(h, idx) in [30, 52, 44, 68, 58, 80, 64, 72, 48, 60, 88, 70]" :key="idx" :style="{ height: h + '%', '--i': idx }" />
                </div>
                <div class="device-chips">
                  <span class="chip on"><i /> 位移传感器 · 在线</span>
                  <span class="chip on"><i /> 雨量计 · 在线</span>
                  <span class="chip off"><i /> 倾斜仪 · 离线</span>
                </div>
              </div>
            </div>

            <!-- 智能预警 -->
            <div v-else-if="w.type === 'alerts'" class="terminal-card">
              <div class="terminal-bar"><span>warnings · 智能预警</span></div>
              <div class="terminal-body alerts-visual">
                <div class="alert-row ar-high"><i /><div><strong>牛栏湾滑坡 · 位移超阈值</strong><small>触发 AI 异常检测 · 建议立即核查</small></div><em>高</em></div>
                <div class="alert-row ar-mid"><i /><div><strong>大坪坡崩塌 · 雨量激增</strong><small>规则引擎命中 · 黄色预警</small></div><em>中</em></div>
                <div class="alert-row ar-low"><i /><div><strong>石门坎泥石流 · 波动</strong><small>模型置信度 72%</small></div><em>低</em></div>
                <div class="alert-footer"><span>今日预警 12</span><span>处置中 3</span><span>已闭环 9</span></div>
              </div>
            </div>

            <!-- 数据统计 -->
            <div v-else class="terminal-card">
              <div class="terminal-bar"><span>analytics · 多维统计</span></div>
              <div class="terminal-body stats-visual">
                <div class="stat-donut">
                  <div class="donut"><span>86%</span></div>
                  <div class="donut-legend">
                    <span><i class="dot-done" />已完成 86%</span>
                    <span><i class="dot-doing" />进行中 9%</span>
                    <span><i class="dot-overdue" />逾期 5%</span>
                  </div>
                </div>
                <div class="stat-bars">
                  <div><span>1月</span><i style="--w:62%" /></div>
                  <div><span>2月</span><i style="--w:78%" /></div>
                  <div><span>3月</span><i style="--w:55%" /></div>
                  <div><span>4月</span><i style="--w:92%" /></div>
                </div>
              </div>
            </div>
          </div>
        </article>
      </div>
    </section>

    <!-- 系统架构 -->
    <section id="architecture" class="section architecture-section">
      <div class="section-head reveal">
        <span class="eyebrow">ARCHITECTURE</span>
        <h2>端到端监测架构</h2>
        <p>感知层 → 平台层 → 应用层，全链路数据贯通</p>
      </div>

      <div class="arch-diagram reveal">
        <div class="arch-node">
          <span class="arch-icon"><Satellite :size="22" /></span>
          <strong>感知层</strong>
          <small>IoT 传感器 · MQTT 接入</small>
        </div>
        <span class="arch-link"><ChevronRight :size="16" /></span>
        <div class="arch-node">
          <span class="arch-icon"><Server :size="22" /></span>
          <strong>平台层</strong>
          <small>规则引擎 · AI 模型 · 时序库</small>
        </div>
        <span class="arch-link"><ChevronRight :size="16" /></span>
        <div class="arch-node">
          <span class="arch-icon"><Globe :size="22" /></span>
          <strong>应用层</strong>
          <small>预警 · 巡检 · 应急 · 大屏</small>
        </div>
      </div>
    </section>

    <section class="section tailored-section">
      <div class="section-head reveal">
        <span class="eyebrow">KEY FEATURES</span>
        <h2>为什么选择御灾智巡</h2>
        <p>覆盖地质灾害防治全链路，让感知、研判、决策、处置高效协同</p>
      </div>

      <div class="cards-grid">
        <article
          v-for="(t, i) in tailored"
          :key="i"
          class="card reveal"
          :class="{ green: i === 0, purple: i === 1 }"
          :style="{ '--delay': i * 0.1 + 's' }"
        >
          <span class="card-icon" :class="{ green: i === 0, purple: i === 1 }"><component :is="t.icon" :size="22" /></span>
          <h3>{{ t.title }}</h3>
          <p>{{ t.desc }}</p>
        </article>
      </div>
    </section>

    <!-- 角色方案 -->
    <section id="roles" class="section roles-section">
      <div class="section-head reveal">
        <span class="eyebrow">PLATFORM ROLES</span>
        <h2>多角色协同工作</h2>
        <p>管理员、值班员、运维员各司其职，平台支撑地质灾害防治全团队协作</p>
      </div>

      <div class="roles-grid">
        <article
          v-for="(r, i) in roles"
          :key="i"
          class="role-card reveal"
          :class="{ featured: r.featured }"
          :style="{ '--delay': i * 0.1 + 's' }"
        >
          <span v-if="r.featured" class="role-badge">推荐</span>
          <span class="role-name">{{ r.name }}</span>
          <span class="role-en">{{ r.en }}</span>
          <p class="role-desc">{{ r.desc }}</p>
          <ul class="role-features">
            <li v-for="(f, fi) in r.features" :key="fi"><Zap :size="12" />{{ f }}</li>
          </ul>
          <button class="role-cta" @click="goLogin()">
            {{ r.cta }} <ArrowRight :size="14" />
          </button>
        </article>
      </div>
    </section>

    <!-- FAQ -->
    <section id="faq" class="section faq-section">
      <div class="section-head reveal">
        <span class="eyebrow">FAQ</span>
        <h2>常见问题</h2>
        <p>快速了解平台能力与使用方式</p>
      </div>

      <div class="faq-list reveal">
        <div v-for="(f, i) in faqs" :key="i" class="faq-item" :class="{ open: openFaq === i }">
          <button class="faq-q" @click="openFaq = openFaq === i ? -1 : i">
            <span>{{ f.q }}</span>
            <span class="faq-toggle"><Plus v-if="openFaq !== i" :size="16" /><Minus v-else :size="16" /></span>
          </button>
          <transition name="faq">
            <div v-if="openFaq === i" class="faq-a"><p>{{ f.a }}</p></div>
          </transition>
        </div>
      </div>
    </section>

    <!-- CTA -->
    <section class="section cta-section">
      <div class="cta-card reveal">
        <div class="cta-glow" />
        <div class="cta-content">
          <div class="cta-mascot"><BrandMascot :size="88" /></div>
          <h2>即刻开启 <span>智能巡查</span></h2>
          <p>登录平台，体验一体化的地质灾害巡检与预警管理</p>
          <button class="btn-primary btn-light" @click="goLogin()">
            登录系统 <ArrowRight :size="16" />
          </button>
          <small class="cta-hint">默认管理员：admin / admin123（若未连接数据库，内存兜底密码为 123456）</small>
        </div>
      </div>
    </section>

    <footer class="footer">
      <div class="footer-inner">
        <div class="footer-brand">
          <span class="brand-mark"><MountainSnow :size="18" /></span>
          <div>
            <strong>御灾智巡 YuZaiGuard</strong>
            <small>地质灾害全流程智能监测预警平台</small>
          </div>
        </div>
        <div class="footer-links">
          <a href="#features">核心能力</a>
          <a href="#workflow">工作流程</a>
          <a href="#architecture">系统架构</a>
          <a href="#faq">常见问题</a>
        </div>
        <p class="footer-copy">© 2026 御灾智巡 YuZaiGuard · All rights reserved</p>
      </div>
    </footer>

    <!-- 登录弹窗 -->
    <Teleport to="body">
      <Transition name="modal">
        <div v-if="showLoginModal" class="login-modal-overlay" @click.self="closeLogin">
          <div class="login-modal">
            <button class="modal-close" @click="closeLogin">
              <X :size="20" />
            </button>
            <div class="login-modal-head">
              <span class="eyebrow">SIGN IN</span>
              <h2>登录系统</h2>
              <p class="login-subtitle">御灾智巡 · 地质灾害智能监测预警平台</p>
            </div>

            <form novalidate @submit.prevent="submitLogin">
              <label>
                账号
                <div class="login-input">
                  <User :size="16" />
                  <input v-model="loginForm.username" autocomplete="username" placeholder="请输入账号" />
                </div>
              </label>

              <label>
                密码
                <div class="login-input">
                  <Lock :size="16" />
                  <input
                    v-model="loginForm.password"
                    :type="showPassword ? 'text' : 'password'"
                    autocomplete="current-password"
                    placeholder="请输入密码"
                    @keyup="onCapsLock"
                  />
                  <button type="button" :aria-label="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword">
                    <EyeOff v-if="showPassword" :size="15" />
                    <Eye v-else :size="15" />
                  </button>
                </div>
              </label>

              <p v-if="capsLockOn" class="login-caps">大写锁定已开启，注意密码大小写</p>

              <div class="login-options">
                <label class="login-remember">
                  <input v-model="remember" type="checkbox" />
                  <span>记住账号</span>
                </label>
              </div>

              <p v-if="loginError" class="login-error" role="alert">{{ loginError }}</p>

              <button class="primary-btn login-btn" type="submit" :disabled="loginLoading">
                <LogIn :size="16" />{{ loginLoading ? "登录中..." : "进入系统" }}
              </button>

              <!-- 注册入口：本系统不开放自助注册，跳到申请页走审核流程 -->
              <p class="login-register">
                还没有账号？<RouterLink to="/register">申请注册</RouterLink>
              </p>
            </form>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
:root {
  --shadow-sm: 0 1px 2px rgba(15, 34, 24, .04), 0 1px 3px rgba(15, 34, 24, .06);
  --shadow-md: 0 4px 6px -1px rgba(15, 34, 24, .06), 0 12px 24px -8px rgba(15, 34, 24, .12);
  --shadow-lg: 0 10px 25px -5px rgba(15, 34, 24, .12), 0 24px 50px -12px rgba(15, 34, 24, .18);
  --shadow-xl: 0 25px 60px -12px rgba(15, 34, 24, .25), 0 12px 24px -8px rgba(15, 34, 24, .15);
  --shadow-glow: 0 20px 50px -8px color-mix(in srgb, var(--c-brand) 35%, transparent)
    /* = rgba 111,158,127/.35 */;
}

.welcome-page {
  position: relative;
  min-height: 100vh;
  scroll-behavior: smooth;
  background: var(--c-surface-3);
  color: var(--c-text-2);
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "SF Pro Text", "PingFang SC", "Helvetica Neue", sans-serif;
  overflow-x: hidden;
  letter-spacing: -0.01em;
}

/* ====== 背景层 ====== */
.bg {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background:
    radial-gradient(ellipse 80% 50% at 20% 0%, var(--c-brand-light), transparent 60%),
    radial-gradient(ellipse 60% 50% at 80% 20%, color-mix(in srgb, var(--c-brand) 10%, transparent)
    /* = rgba 111,158,127/.10 */, transparent 60%),
    radial-gradient(ellipse 70% 50% at 50% 50%, color-mix(in srgb, var(--c-brand) 8%, transparent)
    /* = rgba 111,158,127/.08 */, transparent 60%),
    linear-gradient(180deg, var(--c-surface-3) 0%, var(--c-surface) 50%, var(--c-surface-3) 100%);
}

/* ====== 顶部导航 ====== */
.topnav {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 50;
  transition: background 0.3s, border-color 0.3s, backdrop-filter 0.3s;
  border-bottom: 1px solid transparent;
}
.topnav.scrolled {
  background: color-mix(in srgb, var(--c-surface) 82%, transparent)
    /* = rgba 255,255,255/0.82 */;
  backdrop-filter: blur(24px) saturate(180%);
  -webkit-backdrop-filter: blur(24px) saturate(180%);
  border-bottom-color: color-mix(in srgb, var(--c-ink-1) 6%, transparent)
    /* = rgba 15,34,24/.06 */;
  box-shadow: 0 1px 0 rgba(15, 34, 24, .02), 0 8px 24px -8px rgba(15, 34, 24, .04);
}
.topnav-inner {
  max-width: 1280px;
  margin: 0 auto;
  padding: 16px 28px;
  display: flex;
  align-items: center;
  gap: 28px;
}
.brand {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  color: inherit;
  transition: transform .2s;
}
.brand:hover { transform: scale(1.02); }
.brand-mark {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  color: var(--c-on-brand);
  background: linear-gradient(135deg, var(--c-brand-deep) 0%, var(--c-brand) 50%, var(--c-brand-hover) 100%);
  border-radius: var(--r-lg);
  box-shadow: 0 8px 24px color-mix(in srgb, var(--c-brand) 35%, transparent),
    inset 0 1px 0 rgba(255, 255, 255, .3); /* = 原 rgba 111,158,127/.35 */
  position: relative;
}
.brand-mark::after {
  content: "";
  position: absolute;
  inset: 0;
  border-radius: var(--r-lg);
  background: linear-gradient(135deg, transparent 50%, rgba(255, 255, 255, .15) 100%);
  pointer-events: none;
}
.brand-copy {
  text-align: left;
  line-height: 1;
}
.brand-copy strong {
  display: block;
  font-size: 17px;
  color: var(--c-text);
  letter-spacing: -0.01em;
  font-weight: 700;
  background: linear-gradient(135deg, var(--c-brand-deep), var(--c-brand));
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}
.brand-copy small {
  display: block;
  margin-top: 4px;
  color: var(--c-text-3);
  font-size: 10px;
  letter-spacing: 0.22em;
  text-transform: uppercase;
  font-weight: 600;
}
.nav-links {
  display: flex;
  gap: 4px;
  margin-left: 16px;
}
.nav-links a {
  padding: 9px 14px;
  color: var(--c-ink-1);
  border-radius: var(--r-md);
  font-size: 14px;
  font-weight: 500;
  transition: color 0.2s, background 0.2s;
}
.nav-links a:hover {
  color: var(--c-brand-deep);
  background: color-mix(in srgb, var(--c-brand) 8%, transparent)
    /* = rgba 111,158,127/.08 */;
}
.topnav-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 10px;
}
.nav-cta {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 20px;
  color: var(--c-on-brand);
  border: 0;
  border-radius: var(--r-md);
  background: linear-gradient(135deg, var(--c-brand-deep) 0%, var(--c-brand-deeper) 100%);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.15s ease, background 0.15s ease;
  box-shadow: 0 4px 14px color-mix(in srgb, var(--c-brand) 35%, transparent)
    /* = rgba 111,158,127/.35 */;
}
.nav-cta:hover {
  transform: translateY(-1px);
  background: linear-gradient(135deg, var(--c-brand) 0%, var(--c-brand-deep) 100%);
  box-shadow: 0 8px 24px color-mix(in srgb, var(--c-brand) 45%, transparent)
    /* = rgba 111,158,127/.45 */;
}
.nav-cta:active {
  transform: scale(.98);
}
.nav-burger {
  display: none;
  width: 40px;
  height: 40px;
  border: 1px solid color-mix(in srgb, var(--c-brand) 25%, transparent)
    /* = rgba 111,158,127/.25 */;
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--c-surface) 70%, transparent)
    /* = rgba 255,255,255/0.7 */;
  cursor: pointer;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 5px;
}
.nav-burger span {
  width: 18px;
  height: 2px;
  background: var(--c-ink-1);
  border-radius: 2px;
  transition: transform 0.25s, opacity 0.25s;
}
.nav-burger.open span:nth-child(1) { transform: translateY(7px) rotate(45deg); }
.nav-burger.open span:nth-child(2) { opacity: 0; }
.nav-burger.open span:nth-child(3) { transform: translateY(-7px) rotate(-45deg); }

/* ====== Mega Menu ====== */
/* 展开时导航条与面板共用同一套磨砂底，视觉上连成一体 */
.topnav.mega-open {
  background: color-mix(in srgb, var(--c-surface) 82%, transparent);
  backdrop-filter: blur(24px) saturate(180%);
  -webkit-backdrop-filter: blur(24px) saturate(180%);
  border-bottom-color: var(--border-color);
}
.nav-trigger {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.nav-chevron {
  opacity: .55;
  transition: transform .3s cubic-bezier(0.32, 0.72, 0, 1), opacity .2s;
}
.nav-trigger.active {
  color: var(--c-brand-deep);
  background: color-mix(in srgb, var(--c-brand) 8%, transparent);
}
.nav-trigger.active .nav-chevron {
  transform: rotate(180deg);
  opacity: 1;
}
.nav-trigger:focus-visible,
.mega-item:focus-visible,
.mega-promo:focus-visible {
  outline: 2px solid var(--c-brand);
  outline-offset: 2px;
}

/* wrap 忽略指针事件：面板两侧的空白不会拦截下方 Hero 的点击 */
.mega-wrap {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  display: flex;
  justify-content: center;
  padding: 0 28px;
  pointer-events: none;
}
.mega-panel {
  --mega-ease: cubic-bezier(0.32, 0.72, 0, 1);
  --mega-dur: 300ms;
  /* 面板内部自成一套浅色令牌并带写死的兜底值：
     不依赖全局未定义变量（--c-ink-1 / --c-on-brand），也不受外层继承色或深色主题影响 */
  --mega-bg: var(--bg-card, #ffffff);
  --mega-border: var(--border-color, #d1dcd5);
  --mega-text: var(--text-main, #2c3b34);
  --mega-text-muted: #55655d;   /* 白底对比度 ≥ 5.5:1 */
  --mega-heading: #5b6a63;      /* 实色代替 opacity，保证小号大写标题可读 */
  --mega-brand: var(--primary, #5f7a6b);
  --mega-brand-deep: var(--primary-deep, #385f49);
  --mega-brand-soft: rgba(95, 122, 107, 0.12);
  --mega-on-brand: #ffffff;
  color-scheme: light;
  color: var(--mega-text);
  position: relative;
  width: min(1120px, 100%);
  margin-top: 8px;
  padding: 28px 32px;
  background: #ffffff;
  background-color: var(--mega-bg);
  border: 1px solid var(--mega-border);
  border-radius: var(--r-xl);
  box-shadow: 0 1px 2px rgba(44, 59, 52, .04), 0 16px 40px -12px rgba(44, 59, 52, .14);
  pointer-events: auto;
  /* 所有菜单叠在同一网格单元里，面板高度取最高者，切换菜单时不会跳动 */
  display: grid;
}
/* 透明桥接区：覆盖导航条与面板之间的 8px 缝隙，鼠标斜向移入时不会触发收起 */
.mega-panel::before {
  content: "";
  position: absolute;
  left: 0;
  right: 0;
  top: -12px;
  height: 12px;
}
.mega-grid {
  grid-area: 1 / 1;
  display: grid;
  grid-template-columns: 1.35fr 1fr .9fr;
  gap: 40px;
  opacity: 0;
  visibility: hidden;
  transform: translateY(-6px);
  transition:
    opacity 200ms var(--mega-ease),
    transform 200ms var(--mega-ease),
    visibility 0s linear 200ms;
}
.mega-grid.is-active {
  opacity: 1;
  visibility: visible;
  transform: none;
  transition:
    opacity 260ms var(--mega-ease) 60ms,
    transform 260ms var(--mega-ease) 60ms,
    visibility 0s;
}
.mega-col { min-width: 0; }

.mega-heading {
  margin: 0 0 4px;
  font-size: 12px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: .14em;
  color: var(--mega-heading);
}
.mega-group + .mega-group { margin-top: 16px; }
.mega-group ul,
.mega-story-points {
  margin: 0;
  padding: 0;
  list-style: none;
}
.mega-item {
  display: grid;
  grid-template-columns: 36px 1fr;
  gap: 14px;
  padding: 12px 0;
  color: inherit;
  text-decoration: none;
  border-radius: var(--r-md);
}
.mega-item-icon {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  color: var(--mega-brand-deep);
  border-radius: var(--r-md);
  background: var(--mega-brand-soft);
  transition: color .2s, background .2s;
}
.mega-item-body strong {
  display: block;
  color: var(--mega-text);
  font-size: 14px;
  font-weight: 700;
  transition: color .2s;
}
.mega-item-body small {
  display: block;
  margin-top: 3px;
  color: var(--mega-text-muted);
  font-size: 12.5px;
  line-height: 1.55;
}
.mega-item:hover .mega-item-body strong { color: var(--mega-brand-deep); }
.mega-item:hover .mega-item-icon {
  color: var(--mega-on-brand);
  background: var(--mega-brand);
}

/* 中列：图文说明 */
.mega-col-story {
  padding: 0 32px;
  border-left: 1px solid var(--mega-border);
  border-right: 1px solid var(--mega-border);
}
.mega-story-visual {
  height: 108px;
  margin: 12px 0 16px;
  display: grid;
  place-items: center;
  color: var(--mega-brand-deep);
  border-radius: var(--r-lg);
  background:
    linear-gradient(var(--c-brand-mist) 1px, transparent 1px) 0 0 / 22px 22px,
    linear-gradient(90deg, var(--c-brand-mist) 1px, transparent 1px) 0 0 / 22px 22px,
    linear-gradient(135deg, var(--c-brand-soft), var(--c-brand-mist));
}
.mega-story-title {
  margin: 0;
  color: var(--mega-text);
  font-size: 16px;
  font-weight: 700;
}
.mega-story-desc {
  margin: 8px 0 0;
  color: var(--mega-text-muted);
  font-size: 13px;
  line-height: 1.65;
}
.mega-story-points { margin-top: 14px; }
.mega-story-points li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  color: var(--mega-text);
  font-size: 12.5px;
}
.mega-story-points svg { color: var(--mega-brand); flex: none; }

/* 右列：推荐卡片，深莫兰迪绿底形成视觉焦点 */
.mega-col-promo { display: flex; }
.mega-promo {
  position: relative;
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding: 22px;
  color: #ffffff;
  text-align: left;
  border: 0;
  border-radius: var(--r-lg);
  background-color: #385f49;
  background-image: linear-gradient(145deg, var(--mega-brand-deep), var(--mega-brand));
  cursor: pointer;
  overflow: hidden;
  transition: transform .25s cubic-bezier(0.32, 0.72, 0, 1), box-shadow .25s;
}
.mega-promo::after {
  content: "";
  position: absolute;
  right: -40px;
  bottom: -40px;
  width: 140px;
  height: 140px;
  border-radius: 50%;
  background: rgba(255, 255, 255, .08);
}
.mega-promo:hover {
  transform: translateY(-2px);
  box-shadow: 0 12px 28px -8px color-mix(in srgb, var(--c-brand-deep) 55%, transparent);
}
.mega-promo-badge {
  padding: 2px 10px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .08em;
  border-radius: var(--r-pill);
  background: rgba(255, 255, 255, .18);
}
.mega-promo-icon {
  margin-top: 6px;
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: var(--r-md);
  background: rgba(255, 255, 255, .16);
}
.mega-promo strong { color: #ffffff; font-size: 16px; font-weight: 700; }
.mega-promo small {
  color: rgba(255, 255, 255, .86);
  font-size: 12.5px;
  line-height: 1.6;
}
.mega-promo-badge,
.mega-promo-icon { color: #ffffff; }
.mega-promo-cta {
  margin-top: auto;
  padding-top: 12px;
  color: #ffffff;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
}

/* 展开/收起：自上向下展开 + 透明度，300ms，cubic-bezier(0.32, 0.72, 0, 1) */
.mega-enter-active,
.mega-leave-active {
  transition:
    opacity 300ms cubic-bezier(0.32, 0.72, 0, 1),
    transform 300ms cubic-bezier(0.32, 0.72, 0, 1),
    clip-path 300ms cubic-bezier(0.32, 0.72, 0, 1);
  transform-origin: top center;
}
.mega-enter-from,
.mega-leave-to {
  opacity: 0;
  transform: translateY(-10px);
  /* 底部收起、两侧与顶部留出余量，避免裁掉阴影与桥接区 */
  clip-path: inset(-40px -40px 100% -40px);
}
.mega-enter-to,
.mega-leave-from {
  opacity: 1;
  transform: none;
  clip-path: inset(-40px -40px -40px -40px);
}

/* ====== Hero ====== */
.hero {
  position: relative;
  z-index: 1;
  padding: 140px 28px 60px;
  text-align: center;
}
.hero-inner {
  max-width: 920px;
  margin: 0 auto;
}
.hero-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 7px 16px;
  color: var(--c-on-brand);
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", sans-serif;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.02em;
  border: 0;
  border-radius: var(--r-pill);
  background: linear-gradient(135deg, var(--c-brand), var(--c-brand)); /* = 原 rgba 111,158,127,1) ×2 */
  box-shadow: 0 8px 24px color-mix(in srgb, var(--c-brand) 35%, transparent)
    /* = rgba 111,158,127/.35 */;
}
.hero-title {
  margin: 22px 0 0;
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "PingFang SC", sans-serif;
  font-size: clamp(40px, 5.8vw, 68px);
  line-height: 1.05;
  letter-spacing: -0.035em;
  font-weight: 800;
  color: var(--c-text);
}
.gradient-text {
  background: linear-gradient(135deg, var(--c-brand-deep) 0%, var(--c-brand-deeper) 35%, var(--c-brand) 70%, var(--c-brand-hover) 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
  position: relative;
  display: inline-block;
}
.gradient-text::after {
  content: "";
  position: absolute;
  left: 0;
  right: 0;
  bottom: -8px;
  height: 4px;
  background: linear-gradient(90deg, var(--c-brand-deep), var(--c-brand-deeper), var(--c-brand), var(--c-brand-hover));
  border-radius: var(--r-pill);
  opacity: .25;
  filter: blur(2px);
}
.hero-desc {
  margin: 28px auto 0;
  max-width: 660px;
  color: var(--c-ink-1);
  font-size: 18px;
  line-height: 1.6;
  letter-spacing: -0.005em;
}
.hero-desc strong {
  color: var(--c-text);
  font-weight: 600;
}
.hero-cta {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  margin-top: 36px;
  flex-wrap: wrap;
}
.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 14px 28px;
  color: var(--c-on-brand);
  border: 0;
  border-radius: var(--r-pill);
  background: linear-gradient(135deg, var(--c-brand-deep) 0%, var(--c-brand-deeper) 50%, var(--c-brand) 100%);
  background-size: 200% 200%;
  background-position: 0% 50%;
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", sans-serif;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -0.01em;
  cursor: pointer;
  transition: transform .2s var(--ease-ios, cubic-bezier(.4,.0,.2,1)),
              box-shadow .2s var(--ease-ios, cubic-bezier(.4,.0,.2,1)),
              background-position .4s;
  box-shadow:
    0 10px 30px color-mix(in srgb, var(--c-brand) 35%, transparent),
    inset 0 1px 0 rgba(255, 255, 255, 0.25); /* = 原 rgba 111,158,127/.35 */
  position: relative;
  overflow: hidden;
}
.btn-primary::before {
  content: "";
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, transparent 30%, rgba(255, 255, 255, .25) 50%, transparent 70%);
  transform: translateX(-100%);
  transition: transform .6s;
}
.btn-primary:hover {
  transform: translateY(-2px);
  background-position: 100% 50%;
  box-shadow:
    0 16px 40px color-mix(in srgb, var(--c-brand) 45%, transparent),
    inset 0 1px 0 rgba(255, 255, 255, .3); /* = 原 rgba 111,158,127/.45 */
}
.btn-primary:hover::before { transform: translateX(100%); }
.btn-primary:active { transform: scale(.97); }
.btn-ghost {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 13px 26px;
  color: var(--c-text-2);
  border: 1px solid color-mix(in srgb, var(--c-ink-1) 12%, transparent)
    /* = rgba 15,34,24/.12 */;
  border-radius: var(--r-pill);
  background: color-mix(in srgb, var(--c-surface) 82%, transparent)
    /* = rgba 255,255,255/.82 */;
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", sans-serif;
  font-size: 16px;
  font-weight: 500;
  letter-spacing: -0.01em;
  cursor: pointer;
  -webkit-backdrop-filter: blur(20px) saturate(180%);
  backdrop-filter: blur(20px) saturate(180%);
  transition: border-color .2s, background .2s, color .2s, transform .2s, box-shadow .2s;
  box-shadow: 0 2px 4px rgba(15, 34, 24, .04);
}
.btn-ghost:hover {
  border-color: color-mix(in srgb, var(--c-brand) 40%, transparent)
    /* = rgba 111,158,127/.4 */;
  color: var(--c-brand-deep);
  background: color-mix(in srgb, var(--c-brand) 6%, transparent)
    /* = rgba 111,158,127/.06 */;
  box-shadow: 0 8px 20px var(--c-brand-light);
}
.btn-ghost:active { transform: scale(.97); }

.hero-trust {
  margin-top: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 32px;
  flex-wrap: wrap;
  color: var(--c-text-3);
  font-size: 13px;
}
.hero-trust-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.hero-trust-item::before {
  content: "";
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--c-brand), var(--c-brand));
}

/* ====== 产品预览 ====== */
.hero-preview {
  max-width: 1080px;
  margin: 80px auto 0;
  text-align: left;
  position: relative;
}
.hero-preview::before {
  content: "";
  position: absolute;
  inset: -40px -40px 0;
  background: radial-gradient(ellipse at center, color-mix(in srgb, var(--c-brand) 15%, transparent)
    /* = rgba 111,158,127/.15 */, transparent 70%);
  filter: blur(40px);
  z-index: -1;
}
.preview-frame {
  border: 1px solid color-mix(in srgb, var(--c-ink-1) 8%, transparent)
    /* = rgba 15,34,24/.08 */;
  border-radius: var(--r-xl);
  background: color-mix(in srgb, var(--c-surface) 92%, transparent)
    /* = rgba 255,255,255/.92 */;
  box-shadow:
    0 40px 100px -20px rgba(15, 34, 24, .25),
    0 12px 32px -8px color-mix(in srgb, var(--c-brand) 15%, transparent),
    0 0 0 1px rgba(255, 255, 255, .5) inset; /* = 原 rgba 111,158,127/.15 */
  overflow: hidden;
  -webkit-backdrop-filter: blur(20px) saturate(180%);
  backdrop-filter: blur(20px) saturate(180%);
}
.frame-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 18px;
  border-bottom: 1px solid color-mix(in srgb, var(--c-ink-1) 6%, transparent)
    /* = rgba 15,34,24/.06 */;
  background: linear-gradient(180deg, color-mix(in srgb, var(--c-surface-2) 90%, transparent), color-mix(in srgb, var(--c-bg) 70%, transparent)); /* = 原 rgba 248,250,252,.9) + rgba 241,245,249,.7) */
}
.frame-bar .dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .4);
}
/* 图表/图例序列色（有意区分，不随主题翻转）：macOS 窗口点三色 */
.dot-r { background: linear-gradient(135deg, #ff5f57, #e0443e); }
/* donut 图例点：已完成 / 进行中 / 逾期（由全局令牌派生） */
.donut-legend .dot-done { background: var(--c-brand); }
.donut-legend .dot-doing { background: var(--c-brand-tint); }
.donut-legend .dot-overdue { background: var(--c-warning); }
.dot-y { background: linear-gradient(135deg, #febc2e, #dea123); }
.dot-g { background: linear-gradient(135deg, #28c840, #1aab29); }
.frame-url {
  margin-left: 14px;
  padding: 4px 12px;
  color: var(--c-ink-1);
  font-size: 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  background: color-mix(in srgb, var(--c-surface) 70%, transparent)
    /* = rgba 255,255,255/.7 */;
  border-radius: var(--r-sm);
}
.frame-live {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  color: var(--c-success-strong);
  font-size: 11px;
  letter-spacing: 0.1em;
  font-weight: 700;
  background: color-mix(in srgb, var(--c-success) 12%, transparent)
    /* = rgba 16,185,129/.12 */;
  border-radius: var(--r-pill);
}
.frame-live i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--c-success);
  animation: live-pulse 1.8s ease-in-out infinite;
}
@keyframes live-pulse {
  0%, 100% { opacity: 1; box-shadow: 0 0 0 0 color-mix(in srgb, var(--c-success) 50%, transparent)
    /* = rgba 16,185,129/.5 */; }
  50% { opacity: .7; box-shadow: 0 0 0 6px transparent; /* = rgba 16,185,129/0 */; }
}
.frame-body {
  display: grid;
  grid-template-columns: 168px 1fr;
  min-height: 340px;
}
.frame-side {
  padding: 18px 14px;
  border-right: 1px solid color-mix(in srgb, var(--c-ink-1) 6%, transparent)
    /* = rgba 15,34,24/.06 */;
  background: linear-gradient(180deg, color-mix(in srgb, var(--c-surface-2) 80%, transparent), color-mix(in srgb, var(--c-bg) 50%, transparent)); /* = 原 rgba 248,250,252,.8) + rgba 241,245,249,.5) */
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.side-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  color: var(--c-ink-1);
  border-radius: var(--r-sm);
  font-size: 13px;
  font-weight: 500;
  transition: background .2s, color .2s;
}
.side-item.active {
  color: var(--c-brand-deep);
  background: linear-gradient(135deg, var(--c-brand-light), color-mix(in srgb, var(--c-brand) 8%, transparent)
    /* = rgba 111,158,127/.08 */);
}
.frame-main {
  padding: 20px;
  background:
    radial-gradient(ellipse at top right, color-mix(in srgb, var(--c-brand) 4%, transparent)
    /* = rgba 111,158,127/.04 */, transparent 50%),
    linear-gradient(180deg, var(--c-surface) 0%, var(--c-brand-mist) 100%);
  display: grid;
  gap: 18px;
  align-content: start;
}
.preview-top {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
.preview-kpi {
  padding: 14px 16px;
  border: 1px solid color-mix(in srgb, var(--c-ink-1) 6%, transparent)
    /* = rgba 15,34,24/.06 */;
  border-radius: var(--r-lg);
  background: color-mix(in srgb, var(--c-surface) 85%, transparent)
    /* = rgba 255,255,255/0.85 */;
  box-shadow: 0 1px 3px rgba(15, 34, 24, .04);
}
.preview-kpi small {
  display: block;
  color: var(--c-text-3);
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.02em;
}
.preview-kpi strong {
  display: block;
  margin-top: 6px;
  color: var(--c-text);
  font-size: 24px;
  letter-spacing: -0.025em;
  font-weight: 800;
}
.preview-kpi strong.warn { color: var(--c-brand); }
.preview-kpi strong.good { color: var(--c-success); }
.preview-map {
  position: relative;
  height: 240px;
  border: 1px solid color-mix(in srgb, var(--c-ink-1) 6%, transparent)
    /* = rgba 15,34,24/.06 */;
  border-radius: var(--r-lg);
  overflow: hidden;
  background:
    radial-gradient(ellipse at 30% 40%, var(--c-brand-light), transparent 50%),
    radial-gradient(ellipse at 70% 60%, color-mix(in srgb, var(--c-brand) 10%, transparent)
    /* = rgba 111,158,127/.10 */, transparent 50%),
    linear-gradient(180deg, var(--c-brand-mist), var(--c-brand-mist));
}
.map-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(color-mix(in srgb, var(--c-brand) 8%, transparent)
    /* = rgba 111,158,127/.08 */ 1px, transparent 1px),
    linear-gradient(90deg, color-mix(in srgb, var(--c-brand) 8%, transparent)
    /* = rgba 111,158,127/.08 */ 1px, transparent 1px);
  background-size: 36px 36px;
}
.map-contour {
  position: absolute;
  border: 1.5px solid color-mix(in srgb, var(--c-brand) 25%, transparent)
    /* = rgba 111,158,127/.25 */;
  border-radius: 47% 53% 49% 51%;
}
.map-contour.c1 { width: 72%; height: 60%; left: -10%; top: 14%; transform: rotate(-14deg); }
.map-contour.c2 { width: 56%; height: 46%; right: -6%; bottom: -8%; transform: rotate(24deg); }
.map-river {
  position: absolute;
  width: 30%;
  height: 130%;
  left: 46%;
  top: -16%;
  border-left: 3px solid color-mix(in srgb, var(--c-brand) 50%, transparent)
    /* = rgba 111,158,127/.5 */;
  border-radius: 50%;
  transform: rotate(22deg);
}
.map-marker {
  position: absolute;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  transform: translate(-50%, -50%);
  box-shadow: 0 0 0 3px rgba(255, 255, 255, .8);
}
.map-marker i {
  position: absolute;
  inset: -6px;
  border-radius: 50%;
  border: 2px solid currentColor;
  animation: marker-ping 2.2s ease-out infinite;
}
.map-marker.m1 { left: 32%; top: 38%; background: var(--c-danger-strong); color: var(--c-danger-strong); }
.map-marker.m2 { left: 58%; top: 60%; background: var(--c-brand); color: var(--c-brand); }
.map-marker.m3 { left: 74%; top: 30%; background: var(--c-brand); color: var(--c-brand); }
@keyframes marker-ping {
  0% { transform: scale(0.6); opacity: .9; }
  100% { transform: scale(2.2); opacity: 0; }
}
.map-tag {
  position: absolute;
  padding: 5px 10px;
  color: var(--c-text-2);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--c-surface) 95%, transparent)
    /* = rgba 255,255,255/0.95 */;
  font-size: 10px;
  font-weight: 600;
  border: 1px solid color-mix(in srgb, var(--c-ink-1) 6%, transparent)
    /* = rgba 15,34,24/.06 */;
  box-shadow: var(--shadow-md);
}
.map-tag.t1 { left: 26%; top: 20%; }
.map-tag.t2 { left: 52%; top: 76%; }
.radar-sweep {
  position: absolute;
  right: -70px;
  top: -70px;
  width: 200px;
  height: 200px;
  background: conic-gradient(from 0deg, color-mix(in srgb, var(--c-brand) 25%, transparent)
    /* = rgba 111,158,127/.25 */, transparent 60deg);
  border-radius: 50%;
  animation: sweep 6s linear infinite;
}
@keyframes sweep { to { transform: rotate(360deg); } }
.preview-chart {
  padding: 16px 18px;
  border: 1px solid color-mix(in srgb, var(--c-ink-1) 6%, transparent)
    /* = rgba 15,34,24/.06 */;
  border-radius: var(--r-lg);
  background: color-mix(in srgb, var(--c-surface) 85%, transparent)
    /* = rgba 255,255,255/0.85 */;
}
.chart-bars {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  height: 70px;
}
.chart-bars span {
  flex: 1;
  border-radius: var(--r-xs) 4px 0 0;
  background: linear-gradient(180deg, var(--c-brand), color-mix(in srgb, var(--c-brand) 30%, transparent)
    /* = rgba 111,158,127/.3 */);
  animation: bar-rise 1s ease-out backwards;
}
@keyframes bar-rise {
  from { height: 0; }
}
.chart-meta {
  display: flex;
  justify-content: space-between;
  margin-top: 12px;
  color: var(--c-text-3);
  font-size: 11px;
  font-weight: 500;
}
.chart-meta em { font-style: normal; }

/* ====== 数据条 ====== */
.stats-strip {
  position: relative;
  z-index: 1;
  max-width: 1080px;
  margin: 0 auto;
  padding: 64px 28px;
}
.stats-strip-inner {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  padding: 32px;
  border: 1px solid color-mix(in srgb, var(--c-ink-1) 6%, transparent)
    /* = rgba 15,34,24/.06 */;
  border-radius: var(--r-2xl);
  background: linear-gradient(135deg, color-mix(in srgb, var(--c-surface) 90%, transparent), color-mix(in srgb, var(--c-surface-2) 70%, transparent)); /* = 原 rgba 255,255,255,.9) + rgba 248,250,252,.7) */
  box-shadow: var(--shadow-lg);
  -webkit-backdrop-filter: blur(20px);
  backdrop-filter: blur(20px);
}
.strip-item {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px;
  border-radius: var(--r-lg);
  transition: background .2s, transform .2s;
}
.strip-item:hover {
  background: linear-gradient(135deg, color-mix(in srgb, var(--c-brand) 4%, transparent), color-mix(in srgb, var(--c-brand) 4%, transparent)); /* = 原 rgba 111,158,127,.04) ×2 */
  transform: translateY(-2px);
}
.strip-icon {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  color: var(--c-on-brand);
  border-radius: var(--r-lg);
  background: linear-gradient(135deg, var(--c-brand-deep), var(--c-brand));
  box-shadow: 0 6px 16px color-mix(in srgb, var(--c-brand) 30%, transparent)
    /* = rgba 111,158,127/.3 */;
  position: relative;
  overflow: hidden;
}
.strip-icon::after {
  content: "";
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, rgba(255, 255, 255, .2) 0%, transparent 60%);
}
.strip-num strong {
  display: block;
  color: var(--c-text);
  font-size: 28px;
  letter-spacing: -0.03em;
  font-weight: 800;
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", sans-serif;
}
.strip-num strong small {
  font-size: 16px;
  color: var(--c-brand-deep);
  font-weight: 700;
}
.strip-num span {
  display: block;
  margin-top: 4px;
  color: var(--c-text-3);
  font-size: 12px;
  font-weight: 500;
}

/* ====== 通用 Section ====== */
.section {
  position: relative;
  z-index: 1;
  max-width: 1280px;
  margin: 0 auto;
  padding: 100px 28px 0;
}
.section-head {
  text-align: center;
  max-width: 720px;
  margin: 0 auto 60px;
}
.section-head .eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  color: var(--c-brand-deep);
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", sans-serif;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.2em;
  background: linear-gradient(135deg, color-mix(in srgb, var(--c-brand) 10%, transparent), color-mix(in srgb, var(--c-brand) 8%, transparent)); /* = 原 rgba 111,158,127,.10) + rgba 111,158,127,.08) */
  border: 1px solid color-mix(in srgb, var(--c-brand) 15%, transparent)
    /* = rgba 111,158,127/.15 */;
  border-radius: var(--r-pill);
  margin-bottom: 18px;
}
.section-head .eyebrow::before {
  content: "";
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--c-brand-deep), var(--c-brand));
}
.section-head h2 {
  margin: 0;
  color: var(--c-text);
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "PingFang SC", sans-serif;
  font-size: clamp(32px, 4vw, 46px);
  letter-spacing: -0.03em;
  line-height: 1.1;
  font-weight: 800;
}
.section-head p {
  margin: 18px 0 0;
  color: var(--c-ink-1);
  font-size: 17px;
  line-height: 1.6;
  letter-spacing: -0.005em;
}

/* ====== 卡片网格 ====== */
.cards-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
}
.card {
  position: relative;
  padding: 32px 28px;
  border: 1px solid color-mix(in srgb, var(--c-ink-1) 6%, transparent)
    /* = rgba 15,34,24/.06 */;
  border-radius: var(--r-xl);
  background: linear-gradient(180deg, color-mix(in srgb, var(--c-surface) 95%, transparent)
    /* = rgba 255,255,255/.95 */, rgba(255, 255, 255, .85));
  box-shadow: var(--shadow-md);
  transition: border-color .25s, transform .25s, box-shadow .25s;
  overflow: hidden;
  -webkit-backdrop-filter: blur(20px) saturate(180%);
  backdrop-filter: blur(20px) saturate(180%);
}
.card::before {
  content: "";
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 4px;
  background: linear-gradient(90deg, var(--c-brand-deep), var(--c-brand-deeper), var(--c-brand));
  opacity: 0;
  transition: opacity .25s;
}
.card:hover {
  border-color: color-mix(in srgb, var(--c-brand) 25%, transparent)
    /* = rgba 111,158,127/.25 */;
  transform: translateY(-6px);
  box-shadow: var(--shadow-lg);
}
.card:hover::before { opacity: 1; }
.card-icon {
  display: inline-grid;
  place-items: center;
  width: 48px;
  height: 48px;
  color: var(--c-on-brand);
  border-radius: var(--r-lg);
  background: linear-gradient(135deg, var(--c-brand-deep) 0%, var(--c-brand) 100%);
  margin-bottom: 20px;
  box-shadow: 0 8px 20px color-mix(in srgb, var(--c-brand) 30%, transparent)
    /* = rgba 111,158,127/.3 */;
  position: relative;
  overflow: hidden;
}
.card-icon::after {
  content: "";
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, rgba(255, 255, 255, .25) 0%, transparent 50%);
}
.card-icon.green {
  background: linear-gradient(135deg, var(--c-brand) 0%, var(--c-brand) 100%);
  box-shadow: 0 8px 20px color-mix(in srgb, var(--c-brand) 30%, transparent)
    /* = rgba 111,158,127/.3 */;
}
.card-icon.purple {
  background: linear-gradient(135deg, var(--c-brand-deeper) 0%, var(--c-brand) 100%);
  box-shadow: 0 8px 20px rgba(56, 95, 73, .3);
}
.card-tag {
  position: absolute;
  top: 28px;
  right: 24px;
  padding: 4px 10px;
  color: var(--c-brand-deep);
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", sans-serif;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
  border-radius: var(--r-pill);
  background: color-mix(in srgb, var(--c-brand) 10%, transparent)
    /* = rgba 111,158,127/.1 */;
}
.card h3 {
  margin: 0;
  color: var(--c-text);
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "PingFang SC", sans-serif;
  font-size: 21px;
  font-weight: 700;
  letter-spacing: -0.025em;
}
.card p {
  margin: 10px 0 0;
  color: var(--c-ink-1);
  font-size: 14px;
  line-height: 1.65;
  letter-spacing: -0.005em;
}

/* ====== 工作流程 ====== */
.workflow-section {
  padding-top: 100px;
}
.workflow-list {
  display: flex;
  flex-direction: column;
  gap: 80px;
}
.workflow-row {
  display: grid;
  grid-template-columns: 1fr 1.15fr;
  gap: 64px;
  align-items: center;
}
.workflow-row.reversed {
  direction: rtl;
}
.workflow-row.reversed > * {
  direction: ltr;
}
.workflow-copy {
  position: relative;
  padding-left: 0;
}
.workflow-num {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--c-brand-deep);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.08em;
  margin-bottom: 18px;
  padding: 5px 12px;
  border-radius: var(--r-sm);
  background: linear-gradient(135deg, color-mix(in srgb, var(--c-brand) 10%, transparent), color-mix(in srgb, var(--c-brand) 8%, transparent)); /* = 原 rgba 111,158,127,.10) + rgba 111,158,127,.08) */
}
.workflow-num::before {
  content: "";
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--c-brand-deep), var(--c-brand));
}
.workflow-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  color: var(--c-on-brand);
  border-radius: var(--r-lg);
  background: linear-gradient(135deg, var(--c-brand-deep) 0%, var(--c-brand) 100%);
  margin-bottom: 20px;
  box-shadow: 0 10px 24px color-mix(in srgb, var(--c-brand) 30%, transparent)
    /* = rgba 111,158,127/.3 */;
}
.workflow-copy h3 {
  margin: 0;
  color: var(--c-text);
  font-size: 30px;
  letter-spacing: -0.03em;
  font-weight: 800;
  line-height: 1.2;
}
.workflow-copy p {
  margin: 16px 0 0;
  max-width: 460px;
  color: var(--c-ink-1);
  font-size: 16px;
  line-height: 1.7;
}
.explore-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 24px;
  padding: 8px 14px;
  color: var(--c-brand-deep);
  border: 1px solid color-mix(in srgb, var(--c-brand) 20%, transparent)
    /* = rgba 111,158,127/.2 */;
  border-radius: var(--r-pill);
  background: color-mix(in srgb, var(--c-surface) 70%, transparent)
    /* = rgba 255,255,255/.7 */;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: gap 0.2s, color 0.2s, border-color 0.2s, background 0.2s, transform 0.2s;
}
.explore-link:hover {
  gap: 10px;
  color: var(--c-on-brand);
  border-color: var(--c-brand-deep);
  background: linear-gradient(135deg, var(--c-brand-deep), var(--c-brand));
  transform: translateX(2px);
}

/* 终端卡片 */
.terminal-card {
  border: 1px solid color-mix(in srgb, var(--c-ink-1) 6%, transparent)
    /* = rgba 15,34,24/.06 */;
  border-radius: var(--r-xl);
  background: color-mix(in srgb, var(--c-surface) 95%, transparent)
    /* = rgba 255,255,255/.95 */;
  box-shadow: var(--shadow-xl);
  overflow: hidden;
  -webkit-backdrop-filter: blur(20px);
  backdrop-filter: blur(20px);
  position: relative;
}
.terminal-card::before {
  content: "";
  position: absolute;
  inset: -1px;
  border-radius: var(--r-xl);
  padding: 1px;
  background: linear-gradient(135deg, color-mix(in srgb, var(--c-brand) 30%, transparent), color-mix(in srgb, var(--c-brand) 30%, transparent), color-mix(in srgb, var(--c-success) 20%, transparent)); /* = 原 rgba 111,158,127,.3) ×2 + rgba 76,189,106,.2) */
  -webkit-mask: linear-gradient(var(--c-overlay-ink) 0 0) content-box, linear-gradient(var(--c-overlay-ink) 0 0);
  mask: linear-gradient(var(--c-overlay-ink) 0 0) content-box, linear-gradient(var(--c-overlay-ink) 0 0);
  -webkit-mask-composite: xor;
  mask-composite: exclude;
  pointer-events: none;
}
.terminal-bar {
  padding: 12px 18px;
  color: var(--c-ink-1);
  border-bottom: 1px solid color-mix(in srgb, var(--c-ink-1) 6%, transparent)
    /* = rgba 15,34,24/.06 */;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  font-weight: 600;
  background: linear-gradient(180deg, color-mix(in srgb, var(--c-surface-2) 90%, transparent), color-mix(in srgb, var(--c-bg) 60%, transparent)); /* = 原 rgba 248,250,252,.9) + rgba 241,245,249,.6) */
  display: flex;
  align-items: center;
  gap: 8px;
}
.terminal-bar::before {
  content: "▶";
  color: var(--c-brand-deep);
  font-size: 10px;
}
.terminal-body {
  padding: 20px;
}
.map-visual .mini-map {
  position: relative;
  height: 320px;
  border: 1px solid color-mix(in srgb, var(--c-ink-1) 6%, transparent)
    /* = rgba 15,34,24/.06 */;
  border-radius: var(--r-lg);
  overflow: hidden;
  background:
    radial-gradient(ellipse at 30% 40%, color-mix(in srgb, var(--c-brand) 10%, transparent)
    /* = rgba 111,158,127/.10 */, transparent 50%),
    radial-gradient(ellipse at 70% 60%, color-mix(in srgb, var(--c-brand) 8%, transparent)
    /* = rgba 111,158,127/.08 */, transparent 50%),
    linear-gradient(color-mix(in srgb, var(--c-brand) 10%, transparent)
    /* = rgba 111,158,127/0.10 */ 1px, transparent 1px),
    linear-gradient(90deg, color-mix(in srgb, var(--c-brand) 10%, transparent)
    /* = rgba 111,158,127/0.10 */ 1px, transparent 1px),
    linear-gradient(180deg, var(--c-brand-mist), var(--c-brand-mist));
  background-size: 34px 34px, 34px 34px, 100% 100%;
}
.m-marker {
  position: absolute;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  transform: translate(-50%, -50%);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--c-surface) 90%, transparent), 0 0 14px currentColor; /* = 原 rgba 255,255,255/.9 */
  animation: marker-bob 2.6s ease-in-out infinite;
}
.m-marker.mm1 { left: 30%; top: 34%; background: var(--c-danger-strong); color: var(--c-danger-strong); }
.m-marker.mm2 { left: 52%; top: 62%; background: var(--c-brand); color: var(--c-brand); }
.m-marker.mm3 { left: 72%; top: 28%; background: var(--c-brand); color: var(--c-brand); }
.m-marker.mm4 { left: 63%; top: 44%; background: var(--c-brand); color: var(--c-brand); }
@keyframes marker-bob {
  0%, 100% { transform: translate(-50%, -50%) scale(1); }
  50% { transform: translate(-50%, -50%) scale(1.35); }
}
.mini-map-info {
  position: absolute;
  left: 14px;
  bottom: 14px;
  display: flex;
  gap: 10px;
}
.mini-map-info div {
  padding: 10px 16px;
  border: 1px solid color-mix(in srgb, var(--c-ink-1) 6%, transparent)
    /* = rgba 15,34,24/.06 */;
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--c-surface) 95%, transparent)
    /* = rgba 255,255,255/0.95 */;
  box-shadow: 0 8px 20px rgba(15, 34, 24, .08);
  text-align: center;
}
.mini-map-info small {
  display: block;
  color: var(--c-text-3);
  font-size: 10px;
  font-weight: 500;
}
.mini-map-info strong {
  display: block;
  margin-top: 4px;
  color: var(--c-text);
  font-size: 18px;
  font-weight: 800;
}
.chart-visual {
  display: grid;
  gap: 18px;
}
.big-chart {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  height: 220px;
  padding: 10px;
  border: 1px solid color-mix(in srgb, var(--c-ink-1) 6%, transparent)
    /* = rgba 15,34,24/.06 */;
  border-radius: var(--r-lg);
  background: linear-gradient(180deg, color-mix(in srgb, var(--c-surface-2) 80%, transparent), color-mix(in srgb, var(--c-bg) 50%, transparent)); /* = 原 rgba 248,250,252,.8) + rgba 241,245,249,.5) */
}
.big-chart span {
  flex: 1;
  border-radius: var(--r-sm) 6px 0 0;
  background: linear-gradient(180deg, var(--c-brand), color-mix(in srgb, var(--c-brand) 25%, transparent)
    /* = rgba 111,158,127/.25 */);
  animation: bar-rise 0.8s ease-out backwards;
  animation-delay: calc(var(--i) * 60ms);
  box-shadow: 0 -2px 8px color-mix(in srgb, var(--c-brand) 20%, transparent)
    /* = rgba 111,158,127/.2 */;
}
.device-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 7px 14px;
  border-radius: var(--r-pill);
  font-size: 12px;
  font-weight: 600;
}
.chip i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  box-shadow: 0 0 6px currentColor;
}
.chip.on {
  color: var(--c-success-strong);
  border: 1px solid color-mix(in srgb, var(--c-success) 30%, transparent)
    /* = rgba 16,185,129/.3 */;
  background: color-mix(in srgb, var(--c-success) 8%, transparent)
    /* = rgba 16,185,129/.08 */;
}
.chip.on i { background: var(--c-success); }
.chip.off {
  color: var(--c-danger-strong);
  border: 1px solid color-mix(in srgb, var(--c-danger) 30%, transparent)
    /* = rgba 239,68,68/.3 */;
  background: var(--c-danger-bg)
    /* = rgba 239,68,68/.08 */;
}
.chip.off i { background: var(--c-danger); }
.alerts-visual {
  display: grid;
  gap: 10px;
}
.alert-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border: 1px solid color-mix(in srgb, var(--c-ink-1) 6%, transparent)
    /* = rgba 15,34,24/.06 */;
  border-radius: var(--r-lg);
  background: color-mix(in srgb, var(--c-surface) 92%, transparent)
    /* = rgba 255,255,255/.92 */;
  transition: border-color .2s, transform .2s;
}
.alert-row:hover {
  border-color: color-mix(in srgb, var(--c-brand) 25%, transparent)
    /* = rgba 111,158,127/.25 */;
  transform: translateX(4px);
}
.alert-row > i {
  width: 10px;
  height: 10px;
  flex: 0 0 auto;
  border-radius: 50%;
  box-shadow: 0 0 12px currentColor;
}
.alert-row > div { min-width: 0; flex: 1; }
.alert-row strong {
  display: block;
  color: var(--c-text);
  font-size: 14px;
  font-weight: 700;
}
.alert-row small {
  display: block;
  margin-top: 4px;
  color: var(--c-text-3);
  font-size: 12px;
}
.alert-row em {
  font-style: normal;
  font-size: 11px;
  font-weight: 700;
  padding: 4px 10px;
  border-radius: var(--r-pill);
  letter-spacing: 0.04em;
}
.ar-high > i { background: var(--c-danger); color: var(--c-danger); }
.ar-high em { color: var(--c-danger-strong); background: var(--c-danger-bg)
    /* = rgba 239,68,68/.12 */; }
.ar-mid > i { background: var(--c-brand-hover); color: var(--c-brand-hover); }
.ar-mid em { color: var(--c-brand-deep); background: color-mix(in srgb, var(--c-success) 14%, transparent)
    /* = rgba 76,189,106/.14 */; }
.ar-low > i { background: var(--c-brand); color: var(--c-brand); }
.ar-low em { color: var(--sys-blue-deep); background: var(--c-brand-light); }
.alert-footer {
  display: flex;
  justify-content: space-between;
  padding: 14px 4px 0;
  color: var(--c-ink-1);
  font-size: 12px;
  font-weight: 600;
  border-top: 1px dashed color-mix(in srgb, var(--c-ink-1) 8%, transparent)
    /* = rgba 15,34,24/.08 */;
  margin-top: 6px;
}
.stats-visual {
  display: grid;
  grid-template-columns: 1fr 1.2fr;
  gap: 22px;
  align-items: center;
}
.stat-donut {
  display: grid;
  gap: 16px;
  justify-items: center;
}
.donut {
  position: relative;
  width: 140px;
  height: 140px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: conic-gradient(var(--c-success) 0 86%, var(--c-brand-hover) 86% 95%, var(--c-danger) 0 95%, var(--c-danger) 95% 100%);
  box-shadow: 0 10px 30px rgba(16, 185, 129, .2);
}
.donut::before {
  content: "";
  position: absolute;
  inset: 8px;
  border-radius: 50%;
  background: var(--c-surface);
}
.donut span {
  position: relative;
  z-index: 1;
  color: var(--c-text);
  font-size: 22px;
  font-weight: 800;
  letter-spacing: -0.025em;
}
.donut-legend {
  display: grid;
  gap: 8px;
}
.donut-legend span {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--c-ink-1);
  font-size: 12px;
  font-weight: 600;
}
.donut-legend i {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.stat-bars {
  display: grid;
  gap: 14px;
}
.stat-bars div {
  display: grid;
  grid-template-columns: 36px 1fr;
  align-items: center;
  gap: 12px;
}
.stat-bars span {
  color: var(--c-ink-1);
  font-size: 12px;
  font-weight: 600;
}
.stat-bars i {
  height: 10px;
  border-radius: var(--r-pill);
  background: linear-gradient(90deg, var(--c-brand), var(--c-brand));
  width: var(--w);
  animation: bar-grow-x 1s ease-out backwards;
  box-shadow: 0 2px 8px color-mix(in srgb, var(--c-brand) 25%, transparent)
    /* = rgba 111,158,127/.25 */;
}
@keyframes bar-grow-x {
  from { width: 0; }
}

/* ====== 架构 ====== */
.architecture-section {
  padding-top: 110px;
  position: relative;
}
.architecture-section::before {
  content: "";
  position: absolute;
  inset: 110px 0 0;
  background:
    radial-gradient(ellipse at 20% 0%, color-mix(in srgb, var(--c-brand) 4%, transparent)
    /* = rgba 111,158,127/.04 */, transparent 60%),
    radial-gradient(ellipse at 80% 100%, color-mix(in srgb, var(--c-brand) 4%, transparent)
    /* = rgba 111,158,127/.04 */, transparent 60%);
  pointer-events: none;
  z-index: -1;
}
.arch-diagram {
  display: grid;
  grid-template-columns: 1fr auto 1fr auto 1fr;
  align-items: stretch;
  justify-content: center;
  gap: 16px;
  max-width: 1040px;
  margin: 0 auto;
  position: relative;
}
.arch-node {
  position: relative;
  padding: 32px 26px;
  border: 1px solid color-mix(in srgb, var(--c-ink-1) 6%, transparent)
    /* = rgba 15,34,24/.06 */;
  border-radius: var(--r-xl);
  background: linear-gradient(180deg, color-mix(in srgb, var(--c-surface) 95%, transparent), color-mix(in srgb, var(--c-surface-2) 90%, transparent)); /* = 原 rgba 255,255,255,.95) + rgba 248,250,252,.9) */
  box-shadow: var(--shadow-md);
  text-align: center;
  transition: border-color .25s, transform .25s, box-shadow .25s;
  -webkit-backdrop-filter: blur(20px) saturate(180%);
  backdrop-filter: blur(20px) saturate(180%);
  overflow: hidden;
}
.arch-node::before {
  content: "";
  position: absolute;
  top: 0;
  left: 50%;
  transform: translateX(-50%);
  width: 60%;
  height: 3px;
  background: linear-gradient(90deg, transparent, currentColor, transparent);
  opacity: .5;
}
.arch-node:nth-child(1) { color: var(--c-brand-deep); }
.arch-node:nth-child(3) { color: var(--c-brand); }
.arch-node:nth-child(5) { color: var(--c-brand-hover); }
.arch-node:hover {
  border-color: currentColor;
  transform: translateY(-4px);
  box-shadow: var(--shadow-lg);
}
.arch-icon {
  display: inline-grid;
  place-items: center;
  width: 52px;
  height: 52px;
  color: var(--c-on-brand);
  border-radius: var(--r-lg);
  background: linear-gradient(135deg, currentColor, currentColor);
  margin-bottom: 16px;
  box-shadow: 0 8px 20px currentColor;
  position: relative;
  overflow: hidden;
}
.arch-node:nth-child(1) .arch-icon { background: linear-gradient(135deg, var(--c-brand-deep), var(--c-brand-deeper)); }
.arch-node:nth-child(3) .arch-icon { background: linear-gradient(135deg, var(--c-brand), var(--c-brand-hover)); }
.arch-node:nth-child(5) .arch-icon { background: linear-gradient(135deg, var(--c-brand-hover), var(--c-brand)); }
.arch-icon::after {
  content: "";
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, rgba(255, 255, 255, .25) 0%, transparent 50%);
}
.arch-node strong {
  display: block;
  color: var(--c-text);
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "PingFang SC", sans-serif;
  font-size: 17px;
  font-weight: 700;
  letter-spacing: -0.02em;
}
.arch-node small {
  display: block;
  margin-top: 8px;
  color: var(--c-ink-1);
  font-size: 13px;
  line-height: 1.5;
  letter-spacing: -0.005em;
}
.arch-link {
  align-self: center;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  color: var(--c-brand-deep);
  background: linear-gradient(135deg, var(--c-brand-light), var(--c-brand-light));
  border-radius: 50%;
  transition: transform .2s, box-shadow .2s;
}
.arch-link:hover {
  transform: scale(1.15);
  box-shadow: 0 8px 20px color-mix(in srgb, var(--c-brand) 25%, transparent)
    /* = rgba 111,158,127/.25 */;
}
@keyframes link-nudge {
  0%, 100% { transform: translateX(0); }
  50% { transform: translateX(4px); }
}

/* ====== 定制化 ====== */
.tailored-section {
  padding-top: 110px;
}

/* ====== 角色方案 ====== */
.roles-section {
  padding-top: 110px;
  position: relative;
}
.roles-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 22px;
  align-items: stretch;
}
.role-card {
  position: relative;
  display: flex;
  flex-direction: column;
  padding: 32px 28px;
  border: 1px solid color-mix(in srgb, var(--c-ink-1) 8%, transparent)
    /* = rgba 15,34,24/.08 */;
  border-radius: var(--r-2xl);
  background: linear-gradient(180deg, var(--c-surface) 0%, var(--c-surface-3) 100%);
  box-shadow: var(--shadow-md);
  transition: border-color .25s, transform .25s, box-shadow .25s;
  overflow: hidden;
}
.role-card::before {
  content: "";
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 4px;
  background: linear-gradient(90deg, var(--c-text-3), var(--c-brand-tint));
  transition: background .25s;
}
.role-card:hover {
  transform: translateY(-6px);
  box-shadow: var(--shadow-lg);
}
.role-card.featured {
  border-color: transparent;
  background:
    linear-gradient(180deg, var(--c-brand-deeper) 0%, var(--c-brand-deeper) 50%, var(--c-brand-deeper) 100%);
  box-shadow:
    0 30px 60px -10px rgba(74, 122, 94, .45),
    0 0 0 1px rgba(255, 255, 255, .05) inset;
  transform: translateY(-8px);
  position: relative;
}
.role-card.featured::before {
  background: linear-gradient(90deg, var(--c-brand-hover), var(--c-brand-tint), var(--c-brand-hover));
  height: 4px;
}
.role-card.featured::after {
  content: "";
  position: absolute;
  inset: 0;
  background: radial-gradient(ellipse at top, color-mix(in srgb, var(--c-success) 15%, transparent)
    /* = rgba 76,189,106/.15 */, transparent 60%);
  pointer-events: none;
}
.role-badge {
  position: absolute;
  top: 20px;
  right: 20px;
  padding: 5px 12px;
  color: var(--c-brand-deeper);
  border-radius: var(--r-pill);
  background: linear-gradient(135deg, var(--c-brand-tint), var(--c-brand-hover));
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.04em;
  box-shadow: 0 6px 16px color-mix(in srgb, var(--c-success) 40%, transparent)
    /* = rgba 76,189,106/.4 */;
}
.role-name {
  display: block;
  color: var(--c-text);
  font-size: 22px;
  font-weight: 800;
  letter-spacing: -0.025em;
}
.role-card.featured .role-name { color: var(--c-on-brand); }
.role-en {
  display: block;
  margin-top: 6px;
  color: var(--c-text-3);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.26em;
}
.role-card.featured .role-en { color: rgba(255, 255, 255, .55); }
.role-desc {
  margin: 18px 0 0;
  color: var(--c-ink-1);
  font-size: 14px;
  line-height: 1.7;
  min-height: 48px;
}
.role-card.featured .role-desc { color: rgba(255, 255, 255, .75); }
.role-features {
  list-style: none;
  margin: 22px 0 0;
  padding: 20px 0 0;
  border-top: 1px solid color-mix(in srgb, var(--c-ink-1) 8%, transparent)
    /* = rgba 15,34,24/.08 */;
  display: grid;
  gap: 12px;
}
.role-card.featured .role-features { border-top-color: rgba(255, 255, 255, .12); }
.role-features li {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--c-text-2);
  font-size: 13px;
  font-weight: 500;
}
.role-card.featured .role-features li { color: color-mix(in srgb, var(--c-surface) 90%, transparent)
    /* = rgba 255,255,255/.9 */; }
.role-features li svg {
  color: var(--c-success);
  flex: 0 0 auto;
  background: color-mix(in srgb, var(--c-success) 12%, transparent)
    /* = rgba 16,185,129/.12 */;
  padding: 4px;
  border-radius: var(--r-sm);
  width: 20px;
  height: 20px;
}
.role-card.featured .role-features li svg {
  color: var(--c-brand-tint);
  background: color-mix(in srgb, var(--c-success) 15%, transparent)
    /* = rgba 109,223,139/.15 */;
}
.role-cta {
  margin-top: 22px;
  padding: 12px 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: var(--c-text-2);
  border: 1px solid color-mix(in srgb, var(--c-ink-1) 12%, transparent)
    /* = rgba 15,34,24/.12 */;
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--c-surface) 90%, transparent)
    /* = rgba 255,255,255/.9 */;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s, color 0.2s, transform 0.2s;
  box-shadow: 0 2px 4px rgba(15, 34, 24, .04);
}
.role-cta:hover {
  border-color: var(--c-brand-deep);
  color: var(--c-brand-deep);
  background: color-mix(in srgb, var(--c-brand) 8%, transparent)
    /* = rgba 111,158,127/.08 */;
  transform: translateY(-1px);
}
.role-card.featured .role-cta {
  border-color: transparent;
  color: var(--c-brand-deeper);
  background: linear-gradient(135deg, var(--c-brand-tint), var(--c-brand-hover));
  font-weight: 800;
  box-shadow: 0 8px 20px rgba(76, 189, 106, .35);
}
.role-card.featured .role-cta:hover {
  background: linear-gradient(135deg, #a5e8b8, var(--c-brand-tint)); /* #a5e8b8：品牌薄荷高光（有意保留的字面量，不随主题翻转） */
  transform: translateY(-2px);
  box-shadow: 0 12px 28px color-mix(in srgb, var(--c-success) 45%, transparent)
    /* = rgba 76,189,106/.45 */;
}

/* ====== FAQ ====== */
.faq-section {
  padding-top: 130px;
}
.faq-list {
  max-width: 800px;
  margin: 0 auto;
  display: grid;
  gap: 12px;
}
.faq-item {
  border: 1px solid color-mix(in srgb, var(--c-ink-1) 6%, transparent)
    /* = rgba 15,34,24/.06 */;
  border-radius: var(--r-lg);
  background: linear-gradient(180deg, var(--c-surface), var(--c-surface-3));
  box-shadow: 0 2px 8px rgba(15, 34, 24, .04);
  overflow: hidden;
  transition: border-color 0.25s, box-shadow 0.25s, transform 0.25s;
}
.faq-item:hover {
  border-color: color-mix(in srgb, var(--c-brand) 20%, transparent)
    /* = rgba 111,158,127/.2 */;
  box-shadow: var(--shadow-md);
  transform: translateX(2px);
}
.faq-item.open {
  border-color: color-mix(in srgb, var(--c-brand) 40%, transparent)
    /* = rgba 111,158,127/.4 */;
  background: linear-gradient(180deg, var(--c-surface), var(--c-brand-soft));
  box-shadow: var(--shadow-lg);
}
.faq-q {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 20px 24px;
  color: var(--c-text);
  border: 0;
  background: transparent;
  font-size: 16px;
  font-weight: 600;
  text-align: left;
  cursor: pointer;
  letter-spacing: -0.01em;
}
.faq-toggle {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  color: var(--c-on-brand);
  border-radius: var(--r-md);
  background: linear-gradient(135deg, var(--c-brand-deep), var(--c-brand));
  box-shadow: 0 4px 12px color-mix(in srgb, var(--c-brand) 30%, transparent)
    /* = rgba 111,158,127/.3 */;
  transition: transform .2s;
}
.faq-item.open .faq-toggle { transform: rotate(180deg); }
.faq-a {
  padding: 0 24px 22px;
}
.faq-a p {
  margin: 0;
  color: var(--c-ink-1);
  font-size: 15px;
  line-height: 1.75;
}
.faq-enter-active,
.faq-leave-active {
  transition: opacity 0.25s ease, max-height 0.3s ease;
  overflow: hidden;
}
.faq-enter-from,
.faq-leave-to {
  opacity: 0;
  max-height: 0;
}
.faq-enter-to,
.faq-leave-from {
  max-height: 200px;
}

/* ====== CTA ====== */
.cta-section {
  padding-top: 140px;
  padding-bottom: 0;
  position: relative;
}
.cta-card {
  position: relative;
  border: 1px solid color-mix(in srgb, var(--c-brand) 25%, transparent)
    /* = rgba 111,158,127/.25 */;
  border-radius: var(--r-2xl);
  background:
    radial-gradient(ellipse at top left, color-mix(in srgb, var(--c-brand) 35%, transparent)
    /* = rgba 111,158,127/.35 */, transparent 50%),
    radial-gradient(ellipse at bottom right, color-mix(in srgb, var(--c-brand) 35%, transparent)
    /* = rgba 111,158,127/.35 */, transparent 50%),
    linear-gradient(135deg, var(--c-brand-deeper) 0%, var(--c-brand-deeper) 50%, var(--c-brand-deeper) 100%);
  overflow: hidden;
  text-align: center;
  padding: 90px 32px;
  box-shadow:
    0 40px 100px -20px rgba(74, 122, 94, .5),
    0 0 0 1px rgba(255, 255, 255, .08) inset;
}
.cta-card::before {
  content: "";
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 20% 80%, color-mix(in srgb, var(--c-success) 15%, transparent)
    /* = rgba 76,189,106/.15 */, transparent 40%),
    radial-gradient(circle at 80% 20%, color-mix(in srgb, var(--c-brand) 15%, transparent)
    /* = rgba 111,158,127/.15 */, transparent 40%);
  pointer-events: none;
}
.cta-glow {
  position: absolute;
  left: 50%;
  top: -180px;
  width: 600px;
  height: 360px;
  transform: translateX(-50%);
  background: radial-gradient(circle, color-mix(in srgb, var(--c-success) 30%, transparent)
    /* = rgba 76,189,106/.3 */, transparent 70%);
  filter: blur(80px);
  pointer-events: none;
  animation: cta-pulse 6s ease-in-out infinite;
}
@keyframes cta-pulse {
  0%, 100% { transform: translateX(-50%) scale(1); opacity: .8; }
  50% { transform: translateX(-50%) scale(1.2); opacity: 1; }
}
.cta-content {
  position: relative;
  z-index: 1;
}
.cta-mascot {
  display: inline-flex;
  margin-bottom: 24px;
  filter: drop-shadow(0 10px 24px color-mix(in srgb, var(--c-success) 40%, transparent)
    /* = rgba 76,189,106/.4 */);
}
.cta-card h2 {
  margin: 0;
  color: var(--c-on-brand);
  font-size: clamp(32px, 4.5vw, 48px);
  letter-spacing: -0.035em;
  font-weight: 800;
  line-height: 1.1;
}
.cta-card h2 span {
  background: linear-gradient(135deg, var(--c-brand-tint), var(--c-brand-hover));
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}
.cta-card p {
  margin: 18px auto 36px;
  max-width: 480px;
  color: rgba(255, 255, 255, .8);
  font-size: 16px;
  line-height: 1.7;
}
.btn-light {
  border: 0;
  background: linear-gradient(135deg, var(--c-brand-tint) 0%, var(--c-brand-hover) 100%);
  color: var(--c-brand-deeper);
  box-shadow:
    0 16px 40px color-mix(in srgb, var(--c-success) 45%, transparent),
    inset 0 1px 0 rgba(255, 255, 255, .35); /* = 原 rgba 76,189,106/.45 */
}
.btn-light::before {
  background: linear-gradient(135deg, transparent 30%, rgba(255, 255, 255, .4) 50%, transparent 70%);
}
.btn-light:hover {
  background: linear-gradient(135deg, #a5e8b8 0%, var(--c-brand-tint) 100%); /* #a5e8b8：品牌薄荷高光（有意保留的字面量，不随主题翻转） */
  box-shadow:
    0 20px 48px color-mix(in srgb, var(--c-success) 55%, transparent),
    inset 0 1px 0 rgba(255, 255, 255, .4); /* = 原 rgba 76,189,106/.55 */
}
.cta-hint {
  display: block;
  margin-top: 22px;
  color: rgba(255, 255, 255, .55);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.02em;
}

/* ====== 页脚 ====== */
.footer {
  position: relative;
  z-index: 1;
  margin-top: 130px;
  border-top: 1px solid color-mix(in srgb, var(--c-ink-1) 6%, transparent)
    /* = rgba 15,34,24/.06 */;
  background: linear-gradient(180deg, transparent, color-mix(in srgb, var(--c-surface-2) 80%, transparent)
    /* = rgba 248,250,252/.8 */);
}
.footer-inner {
  max-width: 1280px;
  margin: 0 auto;
  padding: 50px 28px;
  display: flex;
  align-items: center;
  gap: 24px;
  flex-wrap: wrap;
}
.footer-brand {
  display: flex;
  align-items: center;
  gap: 14px;
}
.footer-brand .brand-mark {
  width: 38px;
  height: 38px;
  background: linear-gradient(135deg, var(--c-brand-deep), var(--c-brand));
  color: var(--c-on-brand);
  box-shadow: 0 6px 16px color-mix(in srgb, var(--c-brand) 30%, transparent)
    /* = rgba 111,158,127/.3 */;
}
.footer-brand strong {
  display: block;
  color: var(--c-text);
  font-size: 15px;
  font-weight: 700;
}
.footer-brand small {
  display: block;
  margin-top: 4px;
  color: var(--c-text-3);
  font-size: 11px;
  font-weight: 500;
}
.footer-links {
  display: flex;
  gap: 22px;
  margin-left: auto;
}
.footer-links a {
  color: var(--c-ink-1);
  font-size: 13px;
  font-weight: 500;
  transition: color 0.2s;
}
.footer-links a:hover {
  color: var(--c-brand-deep);
}
.footer-copy {
  width: 100%;
  margin: 0;
  color: var(--c-text-3);
  font-size: 12px;
  padding-top: 24px;
  border-top: 1px solid color-mix(in srgb, var(--c-ink-1) 6%, transparent)
    /* = rgba 15,34,24/.06 */;
}

/* =====================================================================
   视觉升级 · Hero 动效层
   视差原理：--parallax-y 由脚本按滚动距离写入 .hero-fx；
   这些图层随页面正常上滚，再被 translate 反向"拖住"，净速度小于前景文字。
   固定背景(.bg)不做位移，避免露出空白边缘。
   ===================================================================== */
.hero-fx {
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
}
.hero-inner,
.hero-preview {
  position: relative;
  z-index: 1;
}

/* 雷达波纹：以标题区为圆心，从中心缓慢扩散并消失 */
.hero-radar {
  position: absolute;
  left: 50%;
  top: 340px;
  width: 0;
  height: 0;
  translate: 0 calc(var(--parallax-y, 0px) * 1);
}
.radar-wave {
  position: absolute;
  left: 0;
  top: 0;
  width: 1100px;
  height: 1100px;
  margin: -550px 0 0 -550px;
  border: 1.5px solid rgba(142, 177, 160, 0.3);
  border-radius: 50%;
  background: radial-gradient(circle, transparent 58%, rgba(142, 177, 160, 0.07) 100%);
  opacity: 0;
  will-change: transform, opacity;
  animation: radar-expand 6.4s cubic-bezier(0.2, 0.6, 0.3, 1) infinite;
}
.radar-core {
  position: absolute;
  left: 0;
  top: 0;
  width: 420px;
  height: 420px;
  margin: -210px 0 0 -210px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(142, 177, 160, 0.22), transparent 68%);
  will-change: transform, opacity;
  animation: core-breathe 4.8s ease-in-out infinite;
}
@keyframes radar-expand {
  0% { transform: scale(0.12); opacity: 0; }
  12% { opacity: 0.85; }
  100% { transform: scale(1); opacity: 0; }
}
@keyframes core-breathe {
  0%, 100% { transform: scale(0.9); opacity: 0.55; }
  50% { transform: scale(1.1); opacity: 1; }
}

/* 卫星扫描光束：斜向光带自左向右扫过 */
.scan-beam {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 180px;
  background: linear-gradient(90deg, transparent, rgba(142, 177, 160, 0.17), transparent);
  opacity: 0;
  will-change: transform, opacity;
  animation: beam-sweep 10s linear infinite;
}
.scan-beam.beam-2 {
  width: 90px;
  animation-duration: 13s;
  animation-delay: 4.5s;
}
@keyframes beam-sweep {
  0% { transform: translate3d(-30vw, 0, 0) skewX(-18deg); opacity: 0; }
  10%, 90% { opacity: 1; }
  100% { transform: translate3d(115vw, 0, 0) skewX(-18deg); opacity: 0; }
}

/* 漂浮光点：每个点用独立的 --p 视差系数，制造前后层次 */
.hero-dots {
  position: absolute;
  left: 0;
  right: 0;
  top: -260px; /* 向上多留出位移余量，避免视差下移后顶部露空 */
  bottom: 0;
}
.hd {
  position: absolute;
  left: var(--x);
  top: var(--y);
  width: var(--s, 6px);
  height: var(--s, 6px);
  border-radius: 50%;
  background: #8eb1a0;
  box-shadow: 0 0 12px 2px rgba(142, 177, 160, 0.55);
  opacity: 0;
  translate: 0 calc(var(--parallax-y, 0px) * var(--p, 1));
  will-change: transform, opacity;
  animation: dot-float var(--t, 7s) ease-in-out var(--d, 0s) infinite;
}
@keyframes dot-float {
  0%, 100% { transform: translateY(0); opacity: 0.2; }
  50% { transform: translateY(-20px); opacity: 0.9; }
}
.hd-1 { --x: 8%; --y: 24%; --s: 5px; --p: 0.6; --t: 7s; --d: -1s; }
.hd-2 { --x: 17%; --y: 52%; --s: 8px; --p: 1.5; --t: 9s; --d: -4s; }
.hd-3 { --x: 27%; --y: 18%; --s: 4px; --p: 0.8; --t: 6s; --d: -2s; }
.hd-4 { --x: 36%; --y: 64%; --s: 6px; --p: 1.2; --t: 8s; --d: -5s; }
.hd-5 { --x: 44%; --y: 12%; --s: 4px; --p: 0.5; --t: 7.5s; --d: -3s; }
.hd-6 { --x: 56%; --y: 70%; --s: 7px; --p: 1.6; --t: 9.5s; --d: -6s; }
.hd-7 { --x: 63%; --y: 22%; --s: 5px; --p: 0.7; --t: 6.5s; --d: -1.5s; }
.hd-8 { --x: 72%; --y: 48%; --s: 9px; --p: 1.4; --t: 10s; --d: -7s; }
.hd-9 { --x: 81%; --y: 16%; --s: 4px; --p: 0.6; --t: 7s; --d: -2.5s; }
.hd-10 { --x: 88%; --y: 58%; --s: 6px; --p: 1.1; --t: 8.5s; --d: -4.5s; }
.hd-11 { --x: 93%; --y: 34%; --s: 5px; --p: 0.9; --t: 6.8s; --d: -3.5s; }
.hd-12 { --x: 4%; --y: 72%; --s: 6px; --p: 1.3; --t: 9s; --d: -6.5s; }
.hd-13 { --x: 49%; --y: 40%; --s: 3px; --p: 0.4; --t: 5.8s; --d: -0.5s; }
.hd-14 { --x: 76%; --y: 80%; --s: 5px; --p: 1.5; --t: 8s; --d: -5.5s; }

/* 标题中间四字：莫兰迪绿渐变底 + 发光扫过。
   高光与绿色底是同一个 background-clip:text 的两层背景，所以光只会出现在字形内，不会溢出成方块 */
.shine-text {
  background:
    linear-gradient(110deg, transparent 35%, rgba(255, 255, 255, 0.9) 50%, transparent 65%) no-repeat,
    linear-gradient(135deg, var(--c-brand-deep) 0%, var(--c-brand) 55%, #8eb1a0 100%);
  background-size: 250% 100%, 100% 100%;
  background-position: 130% 0, 0 0;
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
  filter: drop-shadow(0 0 16px rgba(142, 177, 160, 0.45));
  animation: title-shine 3.8s ease-in-out 1.2s infinite;
}
@keyframes title-shine {
  0% { background-position: 130% 0, 0 0; }
  45%, 100% { background-position: -30% 0, 0 0; }
}

/* 主按钮：加厚的多层阴影 + 悬停微光扫过 + 脉冲箭头 */
.hero-cta .btn-primary {
  padding: 16px 32px;
  font-size: 17px;
  box-shadow:
    0 22px 44px -8px color-mix(in srgb, var(--c-brand-deep) 55%, transparent),
    0 8px 16px color-mix(in srgb, var(--c-brand) 35%, transparent),
    inset 0 1px 0 rgba(255, 255, 255, 0.28);
}
.hero-cta .btn-primary:hover {
  box-shadow:
    0 28px 56px -8px color-mix(in srgb, var(--c-brand-deep) 65%, transparent),
    0 10px 20px color-mix(in srgb, var(--c-brand) 45%, transparent),
    inset 0 1px 0 rgba(255, 255, 255, 0.34);
}
.btn-primary::before {
  background: linear-gradient(105deg, transparent 35%, rgba(255, 255, 255, 0.42) 50%, transparent 65%);
  transform: translateX(-120%);
  transition: transform 0.75s cubic-bezier(0.2, 0.7, 0.3, 1);
}
.btn-primary:hover::before { transform: translateX(120%); }
.btn-arrow-pulse {
  display: inline-flex;
  animation: arrow-nudge 1.4s ease-in-out infinite;
}
.btn-primary:hover .btn-arrow-pulse { animation-duration: 0.7s; }
@keyframes arrow-nudge {
  0%, 100% { transform: translateX(0); opacity: 1; }
  50% { transform: translateX(5px); opacity: 0.8; }
}

/* =====================================================================
   视觉升级 · 伪浏览器窗口 / KPI
   ===================================================================== */
.preview-frame {
  box-shadow:
    0 20px 40px rgba(0, 0, 0, 0.1),
    0 1px 3px rgba(0, 0, 0, 0.05),
    0 48px 110px -24px rgba(31, 43, 37, 0.32),
    0 14px 36px -10px color-mix(in srgb, var(--c-brand) 18%, transparent),
    0 0 0 1px rgba(255, 255, 255, 0.5) inset;
}
.preview-kpi strong {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-top: 8px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 32px;
  font-variant-numeric: tabular-nums; /* 等宽数字，滚动时不会左右抖动 */
  letter-spacing: -0.04em;
  line-height: 1;
}
.preview-kpi strong.flash { animation: kpi-flash 0.9s ease-out; }
@keyframes kpi-flash {
  0% { text-shadow: 0 0 0 rgba(142, 177, 160, 0); transform: translateY(0); }
  25% { text-shadow: 0 0 14px rgba(142, 177, 160, 0.9); transform: translateY(-2px); }
  100% { text-shadow: 0 0 0 rgba(142, 177, 160, 0); transform: translateY(0); }
}
/* ↑↓ 指示器：绿色，持续轻微跳动；上下方向的位移方向不同 */
.trend {
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", sans-serif;
  font-size: 15px;
  font-style: normal;
  font-weight: 800;
  color: #6f9e7f;
  display: inline-block;
}
.trend.up { animation: trend-up 1.1s ease-in-out infinite; }
.trend.down { animation: trend-down 1.1s ease-in-out infinite; }
@keyframes trend-up {
  0%, 100% { transform: translateY(1px); opacity: 0.7; }
  50% { transform: translateY(-3px); opacity: 1; }
}
@keyframes trend-down {
  0%, 100% { transform: translateY(-1px); opacity: 0.7; }
  50% { transform: translateY(3px); opacity: 1; }
}

/* =====================================================================
   视觉升级 · 功能模块左右交替
   用 flex + row-reverse 交替，DOM 顺序始终是"文字在前"，
   这样移动端堆叠与读屏顺序一致，也不需要 direction:rtl 带来的文本对齐副作用
   ===================================================================== */
.features-alt-section { padding-bottom: 40px; }
.feature-alt-row {
  display: flex;
  align-items: center;
  gap: 72px;
  max-width: 1120px;
  margin: 0 auto;
  padding: 56px 0;
}
.feature-alt-row.reversed { flex-direction: row-reverse; }
.feature-alt-copy,
.feature-alt-visual { flex: 1 1 0; min-width: 0; }
.feature-alt-copy { text-align: left; }
.feature-alt-copy .eyebrow {
  display: inline-block;
  padding: 5px 12px;
  color: var(--c-brand-deep);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.2em;
  border: 1px solid color-mix(in srgb, var(--c-brand) 20%, transparent);
  border-radius: var(--r-pill);
  background: color-mix(in srgb, var(--c-brand) 8%, transparent);
}
.feature-alt-copy h3 {
  margin: 20px 0 0;
  color: var(--c-text);
  font-size: clamp(28px, 3.6vw, 42px);
  line-height: 1.18;
  letter-spacing: -0.03em;
  font-weight: 800;
}
.feature-alt-copy p {
  margin: 20px 0 0;
  color: var(--c-ink-1);
  font-size: 17px;
  line-height: 1.75;
}

/* 极光 / 全息图表：深色玻璃面板 + 漂移的模糊光斑 + 多模型曲线汇聚 */
.aurora-chart {
  position: relative;
  height: 340px;
  border: 1px solid rgba(142, 177, 160, 0.28);
  border-radius: var(--r-xl);
  background: linear-gradient(160deg, #1f2b25 0%, #26362e 100%);
  box-shadow: 0 24px 60px -18px rgba(31, 43, 37, 0.5), 0 0 0 1px rgba(255, 255, 255, 0.04) inset;
  overflow: hidden;
  isolation: isolate;
}
.aurora-wave {
  position: absolute;
  width: 70%;
  height: 70%;
  border-radius: 50%;
  filter: blur(46px);
  opacity: 0.55;
  will-change: transform, opacity;
  animation: aurora-shift 9s ease-in-out infinite;
  z-index: -1;
}
.wave-1 { left: -15%; top: -20%; background: rgba(142, 177, 160, 0.55); }
.wave-2 { right: -20%; top: 10%; background: rgba(111, 158, 127, 0.4); animation-delay: -3s; animation-duration: 11s; }
.wave-3 { left: 20%; bottom: -35%; background: rgba(184, 207, 196, 0.32); animation-delay: -6s; animation-duration: 13s; }
@keyframes aurora-shift {
  0%, 100% { transform: translate3d(-8%, -4%, 0) scale(1); opacity: 0.45; }
  50% { transform: translate3d(12%, 8%, 0) scale(1.18); opacity: 0.75; }
}
.aurora-grid {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(rgba(142, 177, 160, 0.09) 1px, transparent 1px) 0 0 / 32px 32px,
    linear-gradient(90deg, rgba(142, 177, 160, 0.09) 1px, transparent 1px) 0 0 / 32px 32px;
  -webkit-mask-image: radial-gradient(ellipse at center, #000 30%, transparent 78%);
  mask-image: radial-gradient(ellipse at center, #000 30%, transparent 78%);
}
.aurora-svg {
  position: absolute;
  left: 0;
  right: 0;
  top: 22px;
  width: 100%;
  height: 240px;
}
.aurora-svg .ln {
  fill: none;
  stroke-linecap: round;
  stroke-width: 1.6;
  vector-effect: non-scaling-stroke;
}
.ln-a { stroke: rgba(184, 207, 196, 0.5); stroke-dasharray: 5 7; animation: dash-flow 3.2s linear infinite; }
.ln-b { stroke: rgba(142, 177, 160, 0.5); stroke-dasharray: 5 7; animation: dash-flow 4.2s linear infinite reverse; }
.ln-c { stroke: rgba(111, 158, 127, 0.55); stroke-dasharray: 5 7; animation: dash-flow 5.2s linear infinite; }
.ln-fuse {
  stroke: #dce9e1;
  stroke-width: 2.6;
  stroke-dasharray: 520;
  stroke-dashoffset: 520;
  filter: drop-shadow(0 0 7px rgba(142, 177, 160, 0.95));
  animation: fuse-draw 5s ease-in-out infinite;
}
@keyframes dash-flow { to { stroke-dashoffset: -48; } }
@keyframes fuse-draw {
  0% { stroke-dashoffset: 520; opacity: 0.4; }
  55%, 85% { stroke-dashoffset: 0; opacity: 1; }
  100% { stroke-dashoffset: 0; opacity: 0.4; }
}
.aurora-legend {
  position: absolute;
  left: 20px;
  right: 20px;
  bottom: 16px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  color: rgba(255, 255, 255, 0.72);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}
.aurora-legend span { display: inline-flex; align-items: center; gap: 7px; }
.aurora-legend .lg-fuse { color: #dce9e1; font-weight: 700; }
.lg { width: 14px; height: 3px; border-radius: 2px; }
.lg-a { background: rgba(184, 207, 196, 0.8); }
.lg-b { background: rgba(142, 177, 160, 0.8); }
.lg-c { background: rgba(111, 158, 127, 0.9); }
.lg-f { background: #dce9e1; box-shadow: 0 0 8px rgba(142, 177, 160, 0.9); }

/* 状态机 / 时间轴：可点击，悬停暂停自动推进 */
.state-machine {
  display: flex;
  flex-direction: column;
  padding: 24px;
  border: 1px solid var(--border-color);
  border-radius: var(--r-xl);
  background: linear-gradient(160deg, var(--c-surface), var(--c-surface-2));
  box-shadow: 0 20px 48px -16px rgba(31, 43, 37, 0.2), 0 1px 3px rgba(31, 43, 37, 0.06);
}
.state-node {
  display: flex;
  align-items: center;
  gap: 16px;
  width: 100%;
  padding: 16px 18px;
  text-align: left;
  font: inherit;
  color: var(--c-text-2);
  border: 1px solid var(--border-color);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  cursor: pointer;
  transition: background 0.35s, border-color 0.35s, box-shadow 0.35s, transform 0.35s;
}
.state-node:hover { border-color: color-mix(in srgb, var(--c-brand) 50%, transparent); }
.state-node:focus-visible { outline: 2px solid var(--c-brand); outline-offset: 2px; }
.state-node.active {
  border-color: var(--c-brand);
  background: linear-gradient(135deg, var(--c-brand-soft), var(--c-surface));
  box-shadow: 0 10px 26px -10px color-mix(in srgb, var(--c-brand) 55%, transparent);
  transform: translateX(6px);
}
.state-node.done { background: var(--c-brand-mist); }
.state-index {
  flex: none;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  font-weight: 700;
  color: var(--c-text-3);
}
.state-node.active .state-index,
.state-node.done .state-index { color: var(--c-brand-deep); }
.state-text { flex: 1; min-width: 0; }
.state-text strong { display: block; color: var(--c-text); font-size: 16px; font-weight: 700; }
.state-text small { display: block; margin-top: 3px; color: var(--c-text-3); font-size: 13px; line-height: 1.5; }
.state-check { flex: none; color: var(--c-success); font-size: 18px; font-style: normal; font-weight: 800; }
.state-spinner {
  flex: none;
  width: 18px;
  height: 18px;
  border: 2px solid color-mix(in srgb, var(--c-brand) 25%, transparent);
  border-top-color: var(--c-brand);
  border-radius: 50%;
  animation: spin 0.9s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.state-link {
  width: 2px;
  height: 14px;
  margin-left: 30px;
  background: var(--border-color);
  transition: background 0.5s;
}
.state-link.filled { background: var(--c-brand); }

/* =====================================================================
   视觉升级 · 深色指挥中心区块（全宽）
   .reveal 的整体上浮会让全宽色块顶部露出缝隙，所以这里取消色块自身的位移，
   改为让内部的文字列与终端分别错峰上浮。
   ===================================================================== */
.section.dark-command-section {
  max-width: none;
  margin: 72px 0 0;
  padding: 120px 28px;
  color: #ffffff;
  background:
    radial-gradient(ellipse 60% 70% at 78% 40%, rgba(142, 177, 160, 0.14), transparent 70%),
    linear-gradient(rgba(142, 177, 160, 0.05) 1px, transparent 1px) 0 0 / 48px 48px,
    linear-gradient(90deg, rgba(142, 177, 160, 0.05) 1px, transparent 1px) 0 0 / 48px 48px,
    #1f2b25;
  border-top: 1px solid rgba(142, 177, 160, 0.22);
  border-bottom: 1px solid rgba(142, 177, 160, 0.22);
}
.dark-command-section.reveal {
  opacity: 1;
  transform: none;
  transition: none;
}
.dark-copy,
.dark-visual {
  opacity: 0;
  transform: translateY(30px);
  transition:
    opacity 0.8s cubic-bezier(0.2, 0.7, 0.3, 1),
    transform 0.8s cubic-bezier(0.2, 0.7, 0.3, 1);
}
.dark-visual { transition-delay: 0.18s; }
.dark-command-section.is-visible .dark-copy,
.dark-command-section.is-visible .dark-visual {
  opacity: 1;
  transform: translateY(0);
}
.dark-inner {
  max-width: 1120px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: 1fr 1.05fr;
  gap: 72px;
  align-items: center;
}
.eyebrow-light {
  display: inline-block;
  padding: 5px 12px;
  color: #8eb1a0;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.2em;
  border: 1px solid rgba(142, 177, 160, 0.4);
  border-radius: var(--r-pill);
  text-shadow: 0 0 12px rgba(142, 177, 160, 0.6);
}
.dark-h2 {
  margin: 20px 0 0;
  color: #ffffff;
  font-size: clamp(30px, 4vw, 46px);
  line-height: 1.15;
  letter-spacing: -0.03em;
  font-weight: 800;
}
.dark-desc {
  margin: 20px 0 0;
  color: rgba(255, 255, 255, 0.82);
  font-size: 17px;
  line-height: 1.75;
}
.dark-list {
  display: grid;
  gap: 14px;
  margin: 32px 0 0;
  padding: 0;
  list-style: none;
}
.dark-list li {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #ffffff;
  font-size: 16px;
  font-weight: 500;
}
.glow-dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #8eb1a0;
  box-shadow: 0 0 12px 2px rgba(142, 177, 160, 0.7);
  animation: glow-pulse 2.4s ease-in-out infinite;
}
@keyframes glow-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.45; }
}

.terminal-dark {
  position: relative;
  border: 1px solid rgba(142, 177, 160, 0.32);
  border-radius: var(--r-xl);
  background: rgba(18, 27, 22, 0.88);
  box-shadow:
    0 30px 70px -20px rgba(0, 0, 0, 0.6),
    0 0 40px -8px rgba(142, 177, 160, 0.22),
    0 0 0 1px rgba(142, 177, 160, 0.06) inset;
  overflow: hidden;
}
.terminal-dark::before {
  content: "";
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  height: 1px;
  background: linear-gradient(90deg, transparent, #8eb1a0, transparent);
  opacity: 0.8;
}
.terminal-bar-dark {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  color: rgba(255, 255, 255, 0.62);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  background: rgba(0, 0, 0, 0.32);
  border-bottom: 1px solid rgba(142, 177, 160, 0.16);
}
.terminal-bar-dark .dot { width: 11px; height: 11px; border-radius: 50%; }
.terminal-bar-dark span:last-child { margin-left: 10px; }
.terminal-body-dark {
  min-height: 264px; /* 固定高度：日志滚动时整块不会上下抖动 */
  padding: 20px 22px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13.5px;
  line-height: 1.8;
  color: #d3e0d9;
  overflow: hidden;
}
.code-line {
  margin-bottom: 4px;
  word-break: break-word;
  animation: log-in 0.5s cubic-bezier(0.2, 0.7, 0.3, 1);
}
@keyframes log-in {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}
/* 终端配色：全部落在莫兰迪绿 / 薄荷 / 灰的色域内 */
.prompt { color: #8eb1a0; font-weight: 700; }
.comment { color: rgba(255, 255, 255, 0.42); font-style: italic; }
.keyword { color: #8eb1a0; font-weight: 700; letter-spacing: 0.04em; }
.info-tag { color: rgba(255, 255, 255, 0.5); font-weight: 600; letter-spacing: 0.04em; }
.num { color: #b8cfc4; font-weight: 700; }
.badge-high {
  display: inline-block;
  margin-left: 6px;
  padding: 0 8px;
  color: #1f2b25; /* 深字配莫兰迪红底，对比度约 5:1 */
  font-size: 11px;
  font-weight: 800;
  line-height: 18px;
  border-radius: var(--r-pill);
  background: #c48b8b;
}
.badge-high.mid { background: #d4b48b; }
.anim-blink { animation: blink 1.1s step-end infinite; }
@keyframes blink { 50% { opacity: 0; } }

/* =====================================================================
   视觉升级 · 响应式与降级
   ===================================================================== */
@media (max-width: 980px) {
  /* 同时覆盖 .reversed，否则 row-reverse 会让移动端图在文字上方 */
  .feature-alt-row,
  .feature-alt-row.reversed {
    flex-direction: column;
    align-items: stretch;
    gap: 36px;
    padding: 36px 0;
  }
  .dark-inner {
    grid-template-columns: 1fr;
    gap: 48px;
  }
  .section.dark-command-section { padding: 88px 24px; }
}

/* 低端手机：去掉大面积模糊与持续动画，只保留静态氛围 */
@media (max-width: 767px) {
  .scan-beam,
  .radar-wave:nth-of-type(n + 3),
  .hd:nth-child(n + 7) {
    display: none;
  }
  .radar-wave {
    width: 640px;
    height: 640px;
    margin: -320px 0 0 -320px;
  }
  .radar-core {
    width: 260px;
    height: 260px;
    margin: -130px 0 0 -130px;
    animation: none;
    opacity: 0.7;
  }
  .hero-radar { top: 300px; }
  .aurora-wave {
    filter: blur(28px);
    animation: none;
    opacity: 0.5;
    will-change: auto;
  }
  .wave-3 { display: none; }
  .aurora-chart { height: 300px; }
  .ln-a,
  .ln-b,
  .ln-c { animation: none; }
  .preview-kpi strong { font-size: 26px; }
  .terminal-body-dark { font-size: 12.5px; min-height: 240px; }
}

@media (prefers-reduced-motion: reduce) {
  .radar-wave,
  .radar-core,
  .scan-beam,
  .hd,
  .shine-text,
  .btn-arrow-pulse,
  .trend,
  .preview-kpi strong.flash,
  .aurora-wave,
  .ln-a,
  .ln-b,
  .ln-c,
  .state-spinner,
  .glow-dot,
  .code-line,
  .anim-blink {
    animation: none;
  }
  /* 动画关掉后这些元素要保持可见的静态形态 */
  .radar-wave { opacity: 0.25; transform: scale(0.6); }
  .hd { opacity: 0.5; }
  .ln-fuse { animation: none; stroke-dashoffset: 0; }
  .dark-copy,
  .dark-visual {
    opacity: 1;
    transform: none;
    transition: none;
  }
  .state-node { transition: none; }
}

/* ====== Reveal ====== */
.reveal {
  opacity: 0;
  transform: translateY(30px);
  transition:
    opacity 0.7s cubic-bezier(0.2, 0.7, 0.3, 1) var(--delay, 0s),
    transform 0.7s cubic-bezier(0.2, 0.7, 0.3, 1) var(--delay, 0s);
}
.reveal.is-visible {
  opacity: 1;
  transform: translateY(0);
}

/* ====== 响应式 ====== */
@media (max-width: 980px) {
  .nav-links {
    position: fixed;
    top: 72px;
    left: 12px;
    right: 12px;
    flex-direction: column;
    padding: 12px;
    border: 1px solid color-mix(in srgb, var(--c-ink-1) 8%, transparent)
    /* = rgba 15,34,24/.08 */;
    border-radius: var(--r-lg);
    background: var(--c-surface);
    box-shadow: var(--shadow-xl);
    display: none;
  }
  .nav-links.open {
    display: flex;
  }
  .nav-burger {
    display: inline-flex;
  }
  /* 移动端沿用原抽屉菜单，不渲染 Mega Menu */
  .nav-chevron,
  .mega-wrap {
    display: none;
  }
  .cards-grid,
  .roles-grid {
    grid-template-columns: 1fr;
  }
  .workflow-row {
    grid-template-columns: 1fr;
    gap: 36px;
  }
  .workflow-row.reversed {
    direction: ltr;
  }
  .stats-strip-inner {
    grid-template-columns: repeat(2, 1fr);
  }
  .frame-body {
    grid-template-columns: 1fr;
  }
  .frame-side {
    display: none;
  }
  .stats-visual {
    grid-template-columns: 1fr;
  }
  .arch-diagram {
    flex-direction: column;
  }
  .arch-link {
    transform: rotate(90deg);
  }
  .arch-link:hover {
    transform: rotate(90deg) scale(1.15);
  }
}

@media (max-width: 560px) {
  .hero {
    padding: 130px 18px 50px;
  }
  .hero-title {
    font-size: clamp(36px, 9vw, 48px);
  }
  .hero-cta {
    flex-direction: column;
    width: 100%;
  }
  .hero-cta .btn-primary,
  .hero-cta .btn-ghost {
    width: 100%;
    justify-content: center;
  }
  .hero-trust {
    gap: 18px;
  }
  .preview-top {
    grid-template-columns: repeat(2, 1fr);
  }
  .preview-map {
    height: 200px;
  }
  .stats-strip-inner {
    grid-template-columns: 1fr;
  }
  .section {
    padding: 80px 24px 0;
  }
  .footer-links {
    width: 100%;
    margin-left: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .mega-enter-active,
  .mega-leave-active,
  .mega-grid,
  .mega-grid.is-active {
    transition: opacity 120ms linear;
    transform: none;
    clip-path: none;
  }
  .reveal {
    opacity: 1;
    transform: none;
    transition: none;
  }
  .radar-sweep,
  .frame-live i,
  .map-marker i,
  .m-marker,
  .arch-link,
  .chart-bars span,
  .big-chart span,
  .stat-bars i,
  .cta-glow {
    animation: none;
  }
  .chart-bars span,
  .big-chart span,
  .stat-bars i {
    height: auto;
    width: auto;
  }
}

/* ====== 登录弹窗样式 ====== */
.login-modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--c-overlay)
    /* = rgba 15,34,24/.65 */;
  backdrop-filter: blur(12px) saturate(180%);
  -webkit-backdrop-filter: blur(12px) saturate(180%);
  padding: 20px;
}

.login-modal {
  position: relative;
  width: 100%;
  max-width: 460px;
  max-height: 90vh;
  overflow-y: auto;
  padding: 40px 36px;
  border: 1px solid color-mix(in srgb, var(--c-ink-1) 8%, transparent)
    /* = rgba 15,34,24/.08 */;
  border-radius: var(--r-2xl);
  background: linear-gradient(180deg, var(--c-surface) 0%, var(--c-surface-3) 100%);
  box-shadow:
    0 40px 100px -20px rgba(15, 34, 24, .35),
    0 0 0 1px rgba(255, 255, 255, .5) inset;
}

.modal-close {
  position: absolute;
  top: 18px;
  right: 18px;
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  color: var(--c-ink-1);
  border: 1px solid color-mix(in srgb, var(--c-ink-1) 8%, transparent)
    /* = rgba 15,34,24/.08 */;
  border-radius: var(--r-md);
  background: color-mix(in srgb, var(--c-surface) 82%, transparent)
    /* = rgba 255,255,255/.82 */;
  cursor: pointer;
  transition: color 0.2s, border-color 0.2s, background 0.2s, transform 0.2s;
}
.modal-close:hover {
  color: var(--c-on-brand);
  border-color: transparent;
  background: linear-gradient(135deg, var(--c-brand-deep), var(--c-brand));
  transform: rotate(90deg);
}

.login-modal-head {
  margin-bottom: 32px;
}
.login-modal-head .eyebrow {
  display: inline-block;
  padding: 4px 12px;
  color: var(--c-brand-deep);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.22em;
  background: linear-gradient(135deg, color-mix(in srgb, var(--c-brand) 10%, transparent), color-mix(in srgb, var(--c-brand) 8%, transparent)); /* = 原 rgba 111,158,127,.10) + rgba 111,158,127,.08) */
  border: 1px solid color-mix(in srgb, var(--c-brand) 15%, transparent)
    /* = rgba 111,158,127/.15 */;
  border-radius: var(--r-pill);
  margin-bottom: 12px;
}
.login-modal-head h2 {
  margin: 0;
  color: var(--c-text);
  font-size: 28px;
  letter-spacing: -0.03em;
  font-weight: 800;
}
.login-subtitle {
  margin: 10px 0 0;
  color: var(--c-ink-1);
  font-size: 14px;
  line-height: 1.6;
}

.login-modal form {
  display: grid;
  gap: 20px;
}
.login-modal form label {
  display: block;
  color: var(--c-text-2);
  font-size: 13px;
  font-weight: 600;
}
.login-input {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
  padding: 0 16px;
  border: 1.5px solid color-mix(in srgb, var(--c-ink-1) 12%, transparent)
    /* = rgba 15,34,24/.12 */;
  border-radius: var(--r-lg);
  background: color-mix(in srgb, var(--c-surface) 82%, transparent)
    /* = rgba 255,255,255/.82 */;
  color: var(--c-ink-1);
  transition: border-color 0.2s, box-shadow 0.2s, background 0.2s;
}
.login-input:focus-within {
  border-color: var(--c-brand-deep);
  box-shadow: 0 0 0 4px var(--c-brand-light);
  background: var(--c-surface);
}
.login-input svg {
  flex: 0 0 auto;
  color: var(--c-text-3);
}
.login-input select,
.login-input input {
  flex: 1;
  padding: 14px 0;
  border: 0;
  background: transparent;
  color: var(--c-text);
  font-size: 15px;
  font-weight: 500;
  outline: none;
}
.login-input select {
  cursor: pointer;
}
.login-input input::placeholder {
  color: var(--c-text-3);
  font-weight: 400;
}
.login-input button {
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--c-text-3);
  cursor: pointer;
  transition: color 0.2s;
}
.login-input button:hover {
  color: var(--c-text);
}

.login-caps {
  margin: -10px 0 0;
  padding: 10px 14px;
  color: var(--c-brand-deep);
  font-size: 12px;
  font-weight: 600;
  background: color-mix(in srgb, var(--c-success) 12%, transparent)
    /* = rgba 76,189,106/.12 */;
  border: 1px solid color-mix(in srgb, var(--c-success) 25%, transparent)
    /* = rgba 76,189,106/.25 */;
  border-radius: var(--r-md);
}

.login-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: -8px;
}
.login-remember {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--c-ink-1);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}
.login-remember input {
  width: 16px;
  height: 16px;
  accent-color: var(--c-brand-deep);
}

.login-error {
  padding: 12px 16px;
  color: var(--c-danger-strong);
  font-size: 13px;
  font-weight: 600;
  background: var(--c-danger-bg)
    /* = rgba 239,68,68/.10 */;
  border: 1px solid color-mix(in srgb, var(--c-danger) 25%, transparent)
    /* = rgba 239,68,68/.25 */;
  border-radius: var(--r-md);
}

.login-btn {
  width: 100%;
  margin-top: 6px;
  padding: 15px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: 15px;
}

/* 注册入口：和「记住账号」一样低调，只做一条可点的补充路径 */
.login-register {
  margin: 14px 0 0;
  text-align: center;
  font-size: 12px;
  color: var(--c-text-4);
}

.login-register a {
  color: var(--c-brand);
  text-decoration: none;
  font-weight: 500;
}

.login-register a:hover { text-decoration: underline; }

/* 弹窗动画 */
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.25s ease;
}
.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}
.modal-enter-active .login-modal,
.modal-leave-active .login-modal {
  transition: transform 0.3s cubic-bezier(.34, 1.56, .64, 1), opacity 0.25s ease;
}
.modal-enter-from .login-modal,
.modal-leave-to .login-modal {
  transform: scale(0.92) translateY(20px);
  opacity: 0;
}
</style>