<script setup>
import { computed, onMounted, onBeforeUnmount, ref, reactive, watch } from "vue";
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

/* ====== 登录弹窗 ====== */
const showLoginModal = ref(false);
// 打开登录弹窗时锁定 body 滚动，避免背后页面继续滚动
useBodyScrollLock(showLoginModal);
const loginLoading = ref(false);
const loginError = ref("");
const showPassword = ref(false);
const remember = ref(true);
const capsLockOn = ref(false);
const loginForm = reactive({ username: "admin", password: "123456", role: "admin" });
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

/* ====== 核心能力卡片 ====== */
const capabilities = [
  {
    icon: Bot,
    title: "不止一种算法",
    desc: "孤立森林、XGBoost 与多级规则引擎组合，针对不同数据特征选择最优模型，更快、更准地识别异常。",
    tag: "AI 融合",
  },
  {
    icon: Radar,
    title: "全流程闭环",
    desc: "从灾害点管理、巡检任务、GPS 打卡到预警研判、派发处置，覆盖地质灾害防治完整链条。",
    tag: "全流程",
  },
  {
    icon: Globe,
    title: "多端协同",
    desc: "Web 大屏、移动端 H5 与实时 WebSocket 推送，PC 指挥与野外现场巡检均可即时掌握风险动态。",
    tag: "多终端",
  },
];

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

const navScrolled = computed(() => scrollY.value > 24);

onMounted(() => {
  observer.value = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add("is-visible");
          if (
            entry.target.dataset.statSection === "true" &&
            !animatedStats[0].current
          ) {
            animateStats();
          }
        }
      });
    },
    { threshold: 0.15 },
  );
  sections.value = document.querySelectorAll(".reveal");
  sections.value.forEach((el) => observer.value.observe(el));
  window.addEventListener("scroll", onScroll, { passive: true });
});

onBeforeUnmount(() => {
  if (observer.value) {
    sections.value.forEach((el) => observer.value.unobserve(el));
    observer.value.disconnect();
  }
  window.removeEventListener("scroll", onScroll);
});
</script>

<template>
  <div class="welcome-page" :style="{ '--scroll-y': scrollY + 'px' }">
    <!-- 背景层（与登录页一致的粒子动画背景，固定铺满视口） -->
    <div class="bg" aria-hidden="true">
      <AnimatedLoginBackground />
    </div>

    <!-- 顶部导航 -->
    <header class="topnav" :class="{ scrolled: navScrolled }">
      <div class="topnav-inner">
        <button class="brand" @click="scrollToTop">
          <span class="brand-mark"><MountainSnow :size="20" /></span>
          <span class="brand-copy">
            <strong>御灾智巡</strong>
            <small>YuZaiGuard</small>
          </span>
        </button>

        <nav class="nav-links" :class="{ open: showMobileNav }">
          <a href="#features" @click="showMobileNav = false">核心能力</a>
          <a href="#workflow" @click="showMobileNav = false">工作流程</a>
          <a href="#architecture" @click="showMobileNav = false">系统架构</a>
          <a href="#roles" @click="showMobileNav = false">角色方案</a>
          <a href="#faq" @click="showMobileNav = false">常见问题</a>
        </nav>

        <div class="topnav-actions">
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
    </header>

    <!-- Hero -->
    <section class="hero">
      <div class="hero-inner">
        <span class="hero-badge reveal">
          <ShieldAlert :size="12" />
          地质灾害全流程智能监测预警平台
        </span>

        <h1 class="hero-title reveal">
          智能感知 · <span class="gradient-text">精准预警</span> · 高效应急
        </h1>

        <p class="hero-desc reveal">
          御灾智巡是一站式地质灾害防治平台——融合 IoT 传感器、AI
          模型与多级规则引擎，覆盖灾害点管理、风险研判、预警发布与应急处置全流程，让地质灾害防治更科学、更高效。
        </p>

        <div class="hero-cta reveal">
          <button class="btn-primary" @click="goLogin()">
            立即进入系统 <ArrowRight :size="16" />
          </button>
          <button class="btn-ghost" @click="scrollToContent">
            了解平台功能 <ChevronDown :size="15" />
          </button>
        </div>

        <div class="hero-trust reveal">
          <span class="hero-trust-item">物联网 + AI 融合</span>
          <span class="hero-trust-item">多级规则引擎</span>
          <span class="hero-trust-item">企业级安全防护</span>
        </div>
      </div>

      <!-- 终端样式的产品预览 -->
      <div class="hero-preview reveal">
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
              <div class="preview-top">
                <div class="preview-kpi">
                  <small>监测点</small>
                  <strong>128</strong>
                </div>
                <div class="preview-kpi">
                  <small>在线设备</small>
                  <strong>346</strong>
                </div>
                <div class="preview-kpi">
                  <small>今日预警</small>
                  <strong class="warn">12</strong>
                </div>
                <div class="preview-kpi">
                  <small>处置率</small>
                  <strong class="good">95%</strong>
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

    <!-- 核心能力 -->
    <section id="features" class="section features-section">
      <div class="section-head reveal">
        <span class="eyebrow">CAPABILITIES</span>
        <h2>超越一揽子方案</h2>
        <p>融合 AI、物联网与工程防治实践，为地质风险场景量身打造</p>
      </div>

      <div class="cards-grid">
        <article
          v-for="(c, i) in capabilities"
          :key="i"
          class="card reveal"
          :class="{ purple: i === 0, green: i === 2 }"
          :style="{ '--delay': i * 0.1 + 's' }"
        >
          <span class="card-icon" :class="{ purple: i === 0, green: i === 2 }"><component :is="c.icon" :size="22" /></span>
          <span class="card-tag">{{ c.tag }}</span>
          <h3>{{ c.title }}</h3>
          <p>{{ c.desc }}</p>
        </article>
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
          <small class="cta-hint">演示账号 admin / 123456 · 可选值守员与维护员角色</small>
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
  background: var(--c-scrim)
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