<script setup>
import { computed, onMounted, onBeforeUnmount, ref, reactive, watch } from "vue";
import { useRouter, useRoute } from "vue-router";
import {
  Activity,
  ArrowRight,
  ArrowUpRight,
  BarChart3,
  Bell,
  BrainCircuit,
  ChevronDown,
  ChevronRight,
  Globe,
  Layers,
  Lock,
  MapPin,
  Minus,
  Mountain,
  Plus,
  Radar,
  Radio,
  Satellite,
  Server,
  ShieldCheck,
  Sparkles,
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
const loginLoading = ref(false);
const loginError = ref("");
const loginHint = ref("");
const showPassword = ref(false);
const remember = ref(true);
const capsLockOn = ref(false);
const loginForm = reactive({ username: "admin", password: "123456", role: "admin" });
const REMEMBER_KEY = "geosentry_remembered_username";

watch(
  () => route.query,
  (query) => {
    if (query.login === "true") {
      showLoginModal.value = true;
      if (query.role && ROLE_OPTIONS.some((r) => r.value === query.role)) {
        useDemoRole(query.role);
      }
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
  loginHint.value = "";
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

const useDemoRole = (role) => {
  loginForm.role = role;
  loginForm.username = "admin";
  loginForm.password = "123456";
  loginError.value = "";
  loginHint.value = `已填入「${ROLE_OPTIONS.find((item) => item.value === role)?.label}」演示账号，点击「进入系统」即可`;
};

const forgotPassword = () => {
  loginHint.value = "演示环境默认密码为 123456，正式环境请联系管理员重置密码";
};

/* ====== 统计数字 ====== */
const stats = [
  { value: 128, suffix: "+", label: "灾害监测点", icon: MapPin },
  { value: 350, suffix: "+", label: "IoT 传感器", icon: Radio },
  { value: 24, suffix: "h", label: "全天候监控", icon: Activity },
  { value: 99.9, suffix: "%", label: "系统可用性", icon: ShieldCheck },
];
const animatedStats = reactive(stats.map((s) => ({ ...s, current: 0 })));

/* ====== 核心能力卡片 ====== */
const capabilities = [
  {
    icon: BrainCircuit,
    title: "不止一种算法",
    desc: "孤立森林、XGBoost 与多级规则引擎组合，针对不同数据特征选择最优模型，更快、更准地识别异常。",
    tag: "AI 融合",
  },
  {
    icon: Radar,
    title: "按你的工作流",
    desc: "从巡检任务创建、GPS 打卡、轨迹回溯到预警确认、派发、处置闭环，覆盖防治全流程。",
    tag: "全流程",
  },
  {
    icon: Globe,
    title: "随处可用",
    desc: "Web 大屏、移动端 H5 与实时 WebSocket 推送，PC 与野外现场都能即时掌握风险动态。",
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

/* ====== 定制化三列 ====== */
const tailored = [
  {
    icon: Layers,
    title: "全流程闭环",
    desc: "建立从隐患排查、巡检打卡、设备监测到预警处置的统一业务模型，让团队步调一致。",
  },
  {
    icon: Lock,
    title: "权限治理",
    desc: "管理员 / 值守员 / 维护员多角色权限矩阵，操作留痕、审计可查，安全可控。",
  },
  {
    icon: ShieldCheck,
    title: "安全可靠",
    desc: "JWT 鉴权、设备级鉴权与 MQTT 安全预检，PostgreSQL 与 TDengine 双存储保障数据可靠。",
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
    cta: "以管理员进入",
  },
  {
    name: "值班员",
    en: "ATTENDANT",
    desc: "专注实时预警处置、态势跟踪与任务协调，适合应急值守场景。",
    featured: true,
    features: ["实时预警处置", "巡检任务管理", "应急事件处理", "GPS 轨迹核查", "风险统计查看"],
    cta: "以值班员进入",
  },
  {
    name: "运维员",
    en: "MAINTENANCE",
    desc: "负责设备运维与现场作业，适合一线巡检与设备管理。",
    featured: false,
    features: ["巡检任务打卡", "设备状态查看", "灾害点现场登记", "GPS 定位签到", "设备异常上报"],
    cta: "以运维员进入",
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
    a: "支持。演示环境提供管理员 admin / 值守员 attendant / 维护员 maintenance 三种角色，登录时选择对应角色即可体验对应权限范围。",
  },
];

/* ====== 交互逻辑 ====== */
const scrollToContent = () =>
  document.getElementById("workflow")?.scrollIntoView({ behavior: "smooth" });
const scrollToTop = () => window.scrollTo({ top: 0, behavior: "smooth" });
const goLogin = (role = "admin") => {
  showMobileNav.value = false;
  if (role) useDemoRole(role);
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
    <!-- ====== 背景层（与登录页一致的粒子动画背景，固定铺满视口） ====== -->
    <div class="bg" aria-hidden="true">
      <AnimatedLoginBackground />
    </div>

    <!-- ====== 顶部导航 ====== -->
    <header class="topnav" :class="{ scrolled: navScrolled }">
      <div class="topnav-inner">
        <button class="brand" @click="scrollToTop">
          <span class="brand-mark"><Mountain :size="20" /></span>
          <span class="brand-copy">
            <strong>地脉智巡</strong>
            <small>GeoSentry</small>
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

    <!-- ====== Hero ====== -->
    <section class="hero">
      <div class="hero-inner">
        <span class="hero-badge reveal">
          <Sparkles :size="12" />
          地质灾害智能监测 · 预警 · 应急
        </span>

        <h1 class="hero-title reveal">
          掌控每一次
          <span class="gradient-text">地质灾害风险</span>
        </h1>

        <p class="hero-desc reveal">
          面向地质灾害防治的全流程智能平台——从灾害点管理、IoT
          设备监测到 AI 预警研判与应急处置，
          <strong>地脉智巡</strong> 让每一次风险都尽在掌握。
        </p>

        <div class="hero-cta reveal">
          <button class="btn-primary" @click="goLogin()">
            立即进入系统 <ArrowRight :size="16" />
          </button>
          <button class="btn-ghost" @click="scrollToContent">
            了解平台功能 <ChevronDown :size="15" />
          </button>
        </div>
      </div>

      <!-- 终端样式的产品预览 -->
      <div class="hero-preview reveal">
        <div class="preview-frame">
          <div class="frame-bar">
            <span class="dot dot-r" /><span class="dot dot-y" /><span class="dot dot-g" />
            <span class="frame-url">geosentry · 实时监测总览</span>
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

    <!-- ====== 数据条 ====== -->
    <section class="stats-strip reveal" data-stat-section="true">
      <div
        v-for="(s, i) in stats"
        :key="i"
        class="strip-item"
        :style="{ '--delay': i * 0.08 + 's' }"
      >
        <span class="strip-icon"><component :is="s.icon" :size="16" /></span>
        <div class="strip-num">
          <strong>{{ animatedStats[i].current }}<small>{{ s.suffix }}</small></strong>
          <span>{{ s.label }}</span>
        </div>
      </div>
    </section>

    <!-- ====== 核心能力 ====== -->
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
          :style="{ '--delay': i * 0.1 + 's' }"
        >
          <span class="card-icon"><component :is="c.icon" :size="20" /></span>
          <span class="card-tag">{{ c.tag }}</span>
          <h3>{{ c.title }}</h3>
          <p>{{ c.desc }}</p>
        </article>
      </div>
    </section>

    <!-- ====== 工作流程 ====== -->
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
                    <span><i style="background:#3caa82" />已完成 86%</span>
                    <span><i style="background:#deb154" />进行中 9%</span>
                    <span><i style="background:#e07859" />逾期 5%</span>
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

    <!-- ====== 系统架构 ====== -->
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

    <!-- ====== 定制化 ====== -->
    <section class="section tailored-section">
      <div class="section-head reveal">
        <span class="eyebrow">TAILORED FOR YOUR TEAM</span>
        <h2>为你的组织量身打造</h2>
        <p>自定义平台的知识体系、治理边界与安全基线</p>
      </div>

      <div class="cards-grid">
        <article
          v-for="(t, i) in tailored"
          :key="i"
          class="card reveal"
          :style="{ '--delay': i * 0.1 + 's' }"
        >
          <span class="card-icon"><component :is="t.icon" :size="20" /></span>
          <h3>{{ t.title }}</h3>
          <p>{{ t.desc }}</p>
        </article>
      </div>
    </section>

    <!-- ====== 角色方案 ====== -->
    <section id="roles" class="section roles-section">
      <div class="section-head reveal">
        <span class="eyebrow">PLANS & ROLES</span>
        <h2>按角色进入工作区</h2>
        <p>选择与你工作方式匹配的角色，即刻开启智能巡查</p>
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
          <button class="role-cta" @click="goLogin(r.en.toLowerCase())">
            {{ r.cta }} <ArrowRight :size="14" />
          </button>
        </article>
      </div>
    </section>

    <!-- ====== FAQ ====== -->
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

    <!-- ====== CTA ====== -->
    <section class="section cta-section">
      <div class="cta-card reveal">
        <div class="cta-glow" />
        <div class="cta-content">
          <div class="cta-mascot"><BrandMascot :size="88" /></div>
          <h2>即刻开启智能巡查</h2>
          <p>登录平台，体验一体化的地质灾害巡检与预警管理</p>
          <button class="btn-primary btn-light" @click="goLogin()">
            登录系统 <ArrowRight :size="16" />
          </button>
          <small class="cta-hint">演示账号 admin / 123456 · 可选值守员与维护员角色</small>
        </div>
      </div>
    </section>

    <!-- ====== 页脚 ====== -->
    <footer class="footer">
      <div class="footer-inner">
        <div class="footer-brand">
          <span class="brand-mark"><Mountain :size="18" /></span>
          <div>
            <strong>地脉智巡 GeoSentry</strong>
            <small>地质灾害点智能巡检与预警管理平台</small>
          </div>
        </div>
        <div class="footer-links">
          <a href="#features">核心能力</a>
          <a href="#workflow">工作流程</a>
          <a href="#architecture">系统架构</a>
          <a href="#faq">常见问题</a>
        </div>
        <p class="footer-copy">© 2026 地脉智巡 GeoSentry · All rights reserved</p>
      </div>
    </footer>

    <!-- ====== 登录弹窗 ====== -->
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
              <p class="login-subtitle">使用您的账号登录地脉智巡平台</p>
            </div>

            <form novalidate @submit.prevent="submitLogin">
              <label>
                角色
                <div class="login-input">
                  <ChevronDown :size="16" />
                  <select v-model="loginForm.role" aria-label="选择登录角色">
                    <option v-for="role in ROLE_OPTIONS" :key="role.value" :value="role.value">
                      {{ role.label }} · {{ role.description }}
                    </option>
                  </select>
                </div>
              </label>

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
                <button type="button" class="login-forgot" @click="forgotPassword">忘记密码？</button>
              </div>

              <p v-if="loginError" class="login-error" role="alert">{{ loginError }}</p>
              <p v-if="loginHint && !loginError" class="login-hint">{{ loginHint }}</p>

              <button class="primary-btn login-btn" type="submit" :disabled="loginLoading">
                <LogIn :size="16" />{{ loginLoading ? "登录中..." : "进入系统" }}
              </button>
            </form>

            <div class="demo-accounts">
              <span>演示账号 · 点击快速填充</span>
              <div class="demo-grid">
                <button
                  v-for="role in ROLE_OPTIONS"
                  :key="role.value"
                  type="button"
                  class="demo-role-btn"
                  :class="{ active: loginForm.role === role.value }"
                  @click="useDemoRole(role.value)"
                >
                  {{ role.label }}
                  <small>{{ role.value }}</small>
                </button>
              </div>
            </div>

            <div class="login-footer">
              <span>默认账号：admin</span>
              <span>默认密码：123456</span>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.welcome-page {
  position: relative;
  min-height: 100vh;
  scroll-behavior: smooth;
  background: radial-gradient(circle at top left, #edf7f3 0, #f4f7f5 32%, #eef3f1 100%);
  color: #17333b;
  font-family: "DM Sans", "Noto Sans SC", -apple-system, sans-serif;
  overflow-x: hidden;
}

/* ====== 背景层（与登录页一致） ====== */
.bg {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background: radial-gradient(circle at top left, #edf7f3 0, #f4f7f5 32%, #eef3f1 100%);
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
  background: rgba(251, 252, 251, 0.8);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border-bottom-color: rgba(229, 236, 233, 0.8);
}
.topnav-inner {
  max-width: 1240px;
  margin: 0 auto;
  padding: 14px 24px;
  display: flex;
  align-items: center;
  gap: 24px;
}
.brand {
  display: inline-flex;
  align-items: center;
  gap: 11px;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  color: inherit;
}
.brand-mark {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  color: #0f4e47;
  background: linear-gradient(135deg, #d8f4dd, #b7e6cb);
  border-radius: 11px;
  box-shadow: 0 6px 18px rgba(73, 131, 118, 0.18);
}
.brand-copy {
  text-align: left;
  line-height: 1;
}
.brand-copy strong {
  display: block;
  font-size: 16px;
  color: #24494d;
  letter-spacing: 0.02em;
}
.brand-copy small {
  display: block;
  margin-top: 4px;
  color: #829793;
  font-size: 10px;
  letter-spacing: 0.28em;
  text-transform: uppercase;
}
.nav-links {
  display: flex;
  gap: 4px;
  margin-left: 16px;
}
.nav-links a {
  padding: 8px 13px;
  color: #5f7b78;
  border-radius: 8px;
  font-size: 14px;
  transition: color 0.2s, background 0.2s;
}
.nav-links a:hover {
  color: #2f7f80;
  background: rgba(47, 127, 128, 0.08);
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
  padding: 9px 16px;
  color: #fff;
  border: 1px solid #2b7778;
  border-radius: 8px;
  background: linear-gradient(135deg, #3fae97, #246f70);
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  transition: transform 0.2s, box-shadow 0.2s, background 0.2s;
  box-shadow: 0 4px 14px rgba(47, 127, 128, 0.22);
}
.nav-cta:hover {
  transform: translateY(-1px);
  background: linear-gradient(135deg, #47b8a0, #277061);
  box-shadow: 0 8px 20px rgba(47, 127, 128, 0.3);
}
.nav-burger {
  display: none;
  width: 40px;
  height: 40px;
  border: 1px solid rgba(47, 127, 128, 0.25);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.7);
  cursor: pointer;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 5px;
}
.nav-burger span {
  width: 18px;
  height: 2px;
  background: #5f7f7b;
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
  padding: 150px 24px 60px;
  text-align: center;
}
.hero-inner {
  max-width: 860px;
  margin: 0 auto;
}
.hero-badge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 7px 14px;
  color: #2d8280;
  border: 1px solid rgba(47, 127, 128, 0.28);
  border-radius: 999px;
  background: rgba(47, 127, 128, 0.08);
  font-size: 13px;
  letter-spacing: 0.02em;
}
.hero-title {
  margin: 26px 0 0;
  font-size: clamp(42px, 7vw, 76px);
  line-height: 1.08;
  letter-spacing: -0.045em;
  font-weight: 800;
  color: #1f3e44;
}
.gradient-text {
  background: linear-gradient(92deg, #246b6c 0%, #2f7f80 45%, #6a9fd6 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
}
.hero-desc {
  margin: 26px auto 0;
  max-width: 640px;
  color: #5f7b78;
  font-size: 18px;
  line-height: 1.75;
}
.hero-desc strong {
  color: #24494d;
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
  padding: 13px 28px;
  color: #fff;
  border: 1px solid #2b7778;
  border-radius: 9px;
  background: linear-gradient(135deg, #3fae97, #246f70);
  font-size: 15px;
  font-weight: 700;
  cursor: pointer;
  transition: transform 0.2s, box-shadow 0.2s, background 0.2s;
  box-shadow: 0 6px 20px rgba(47, 127, 128, 0.28);
}
.btn-primary:hover {
  transform: translateY(-2px);
  background: linear-gradient(135deg, #47b8a0, #277061);
  box-shadow: 0 12px 28px rgba(47, 127, 128, 0.36);
}
.btn-ghost {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 13px 26px;
  color: #547474;
  border: 1px solid #dbe6e2;
  border-radius: 9px;
  background: #fff;
  font-size: 15px;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s, color 0.2s;
}
.btn-ghost:hover {
  border-color: #2f7f80;
  color: #2f7f80;
  background: #f0faf7;
}

/* ====== 产品预览 ====== */
.hero-preview {
  max-width: 1000px;
  margin: 70px auto 0;
  text-align: left;
}
.preview-frame {
  border: 1px solid #dce9e4;
  border-radius: 14px;
  background: #fff;
  box-shadow:
    0 30px 80px rgba(31, 79, 70, 0.12),
    0 0 0 1px rgba(57, 88, 82, 0.04);
  overflow: hidden;
}
.frame-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  border-bottom: 1px solid rgba(229, 236, 233, 0.9);
  background: #f8fbfa;
}
.frame-bar .dot {
  width: 11px;
  height: 11px;
  border-radius: 50%;
}
.dot-r { background: #e9855f; }
.dot-y { background: #deb154; }
.dot-g { background: #63d19c; }
.frame-url {
  margin-left: 12px;
  color: #829793;
  font-size: 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.frame-live {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #3a8d6b;
  font-size: 11px;
  letter-spacing: 0.08em;
}
.frame-live i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #3a8d6b;
  animation: live-pulse 1.8s ease-in-out infinite;
}
@keyframes live-pulse {
  0%, 100% { opacity: 1; box-shadow: 0 0 0 0 rgba(58, 141, 107, 0.5); }
  50% { opacity: 0.7; box-shadow: 0 0 0 6px rgba(58, 141, 107, 0); }
}
.frame-body {
  display: grid;
  grid-template-columns: 158px 1fr;
  min-height: 320px;
}
.frame-side {
  padding: 16px 12px;
  border-right: 1px solid #edf2f0;
  background: #fbfdfc;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.side-item {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 9px 11px;
  color: #7f9592;
  border-radius: 7px;
  font-size: 13px;
}
.side-item.active {
  color: #2f7f80;
  background: rgba(47, 127, 128, 0.08);
}
.frame-main {
  padding: 18px;
  background: linear-gradient(180deg, #fbfdfc, #f6faf8);
  display: grid;
  gap: 16px;
  align-content: start;
}
.preview-top {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}
.preview-kpi {
  padding: 12px 14px;
  border: 1px solid #e6eeeb;
  border-radius: 10px;
  background: #fafcfb;
}
.preview-kpi small {
  display: block;
  color: #8b9f9b;
  font-size: 11px;
}
.preview-kpi strong {
  display: block;
  margin-top: 5px;
  color: #21464a;
  font-size: 22px;
  letter-spacing: -0.03em;
}
.preview-kpi strong.warn { color: #d18f2f; }
.preview-kpi strong.good { color: #3a8d6b; }
.preview-map {
  position: relative;
  height: 230px;
  border: 1px solid #e6eeeb;
  border-radius: 10px;
  overflow: hidden;
  background: linear-gradient(180deg, rgba(47, 127, 128, 0.05), rgba(106, 159, 214, 0.07));
}
.map-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(47, 127, 128, 0.09) 1px, transparent 1px),
    linear-gradient(90deg, rgba(47, 127, 128, 0.09) 1px, transparent 1px);
  background-size: 34px 34px;
}
.map-contour {
  position: absolute;
  border: 1px solid rgba(47, 127, 128, 0.2);
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
  border-left: 3px solid rgba(106, 159, 214, 0.5);
  border-radius: 50%;
  transform: rotate(22deg);
}
.map-marker {
  position: absolute;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  transform: translate(-50%, -50%);
}
.map-marker i {
  position: absolute;
  inset: -6px;
  border-radius: 50%;
  border: 1.5px solid currentColor;
  animation: marker-ping 2.2s ease-out infinite;
}
.map-marker.m1 { left: 32%; top: 38%; background: #e07859; color: #e07859; }
.map-marker.m2 { left: 58%; top: 60%; background: #deb154; color: #deb154; }
.map-marker.m3 { left: 74%; top: 30%; background: #6a9fd6; color: #6a9fd6; }
@keyframes marker-ping {
  0% { transform: scale(0.6); opacity: 0.9; }
  100% { transform: scale(2.2); opacity: 0; }
}
.map-tag {
  position: absolute;
  padding: 4px 8px;
  color: #4d7773;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.92);
  font-size: 10px;
  border: 1px solid #d9e6e1;
  box-shadow: 0 2px 8px rgba(31, 79, 70, 0.1);
}
.map-tag.t1 { left: 26%; top: 20%; }
.map-tag.t2 { left: 52%; top: 76%; }
.radar-sweep {
  position: absolute;
  right: -70px;
  top: -70px;
  width: 190px;
  height: 190px;
  background: conic-gradient(from 0deg, rgba(47, 127, 128, 0.28), transparent 60deg);
  border-radius: 50%;
  animation: sweep 6s linear infinite;
}
@keyframes sweep { to { transform: rotate(360deg); } }
.preview-chart {
  padding: 14px 16px;
  border: 1px solid #e6eeeb;
  border-radius: 10px;
  background: #fafcfb;
}
.chart-bars {
  display: flex;
  align-items: flex-end;
  gap: 7px;
  height: 64px;
}
.chart-bars span {
  flex: 1;
  border-radius: 3px 3px 0 0;
  background: linear-gradient(180deg, #5db486, rgba(93, 180, 134, 0.25));
  animation: bar-rise 1s ease-out backwards;
}
@keyframes bar-rise {
  from { height: 0; }
}
.chart-meta {
  display: flex;
  justify-content: space-between;
  margin-top: 10px;
  color: #9aaead;
  font-size: 11px;
}
.chart-meta em { font-style: normal; }

/* ====== 数据条 ====== */
.stats-strip {
  position: relative;
  z-index: 1;
  max-width: 1000px;
  margin: 0 auto;
  padding: 44px 24px;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
.strip-item {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 20px 22px;
  border: 1px solid #e3ebe7;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 5px 18px rgba(30, 75, 69, 0.03);
  transition: border-color 0.2s, transform 0.2s, box-shadow 0.2s;
}
.strip-item:hover {
  border-color: #2f7f80;
  transform: translateY(-2px);
  background: #f4faf7;
  box-shadow: 0 12px 30px rgba(30, 75, 69, 0.08);
}
.strip-icon {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  color: #2d8280;
  border-radius: 10px;
  background: #e4f4ef;
}
.strip-num strong {
  display: block;
  color: #21464a;
  font-size: 26px;
  letter-spacing: -0.03em;
}
.strip-num strong small {
  font-size: 15px;
  color: #2f7f80;
}
.strip-num span {
  display: block;
  margin-top: 3px;
  color: #78908f;
  font-size: 12px;
}

/* ====== 通用 Section ====== */
.section {
  position: relative;
  z-index: 1;
  max-width: 1240px;
  margin: 0 auto;
  padding: 100px 24px 0;
}
.section-head {
  text-align: center;
  max-width: 700px;
  margin: 0 auto 60px;
}
.section-head .eyebrow {
  display: inline-block;
  color: #2f7f80;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.22em;
  margin-bottom: 16px;
}
.section-head h2 {
  margin: 0;
  color: #24494d;
  font-size: clamp(30px, 4vw, 44px);
  letter-spacing: -0.035em;
  line-height: 1.15;
}
.section-head p {
  margin: 16px 0 0;
  color: #78908f;
  font-size: 16px;
  line-height: 1.7;
}

/* ====== 卡片网格 ====== */
.cards-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 18px;
}
.card {
  position: relative;
  padding: 28px 26px;
  border: 1px solid #e3ebe7;
  border-radius: 14px;
  background: linear-gradient(180deg, #fff, #f8fbfa);
  box-shadow: 0 5px 18px rgba(30, 75, 69, 0.03);
  transition: border-color 0.25s, transform 0.25s, box-shadow 0.25s;
  overflow: hidden;
}
.card:hover {
  border-color: #d5e6df;
  transform: translateY(-3px);
  box-shadow: 0 18px 40px rgba(30, 75, 69, 0.09);
}
.card-icon {
  display: inline-grid;
  place-items: center;
  width: 46px;
  height: 46px;
  color: #2d8280;
  border-radius: 12px;
  background: #e4f4ef;
  margin-bottom: 18px;
}
.card-tag {
  position: absolute;
  top: 26px;
  right: 24px;
  padding: 3px 9px;
  color: #2d8280;
  border: 1px solid rgba(47, 127, 128, 0.25);
  border-radius: 999px;
  background: #eef7f4;
  font-size: 10px;
  letter-spacing: 0.06em;
}
.card h3 {
  margin: 0;
  color: #24494d;
  font-size: 18px;
  letter-spacing: -0.01em;
}
.card p {
  margin: 10px 0 0;
  color: #607c79;
  font-size: 14px;
  line-height: 1.7;
}

/* ====== 工作流程 ====== */
.workflow-section {
  padding-top: 120px;
}
.workflow-list {
  display: flex;
  flex-direction: column;
  gap: 90px;
}
.workflow-row {
  display: grid;
  grid-template-columns: 1fr 1.15fr;
  gap: 56px;
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
  padding-left: 26px;
}
.workflow-num {
  position: absolute;
  left: 0;
  top: 2px;
  color: rgba(47, 127, 128, 0.4);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 26px;
  font-weight: 700;
}
.workflow-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  color: #2d8280;
  border-radius: 10px;
  background: #e4f4ef;
  margin-bottom: 16px;
}
.workflow-copy h3 {
  margin: 0;
  color: #24494d;
  font-size: 26px;
  letter-spacing: -0.02em;
}
.workflow-copy p {
  margin: 12px 0 0;
  max-width: 440px;
  color: #5f7b78;
  font-size: 15px;
  line-height: 1.75;
}
.explore-link {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-top: 20px;
  padding: 0;
  color: #2f7f80;
  border: 0;
  background: transparent;
  font-size: 14px;
  cursor: pointer;
  transition: gap 0.2s, color 0.2s;
}
.explore-link:hover {
  gap: 9px;
  color: #3a8d6b;
}

/* 终端卡片 */
.terminal-card {
  border: 1px solid #e3ebe7;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 24px 60px rgba(30, 75, 69, 0.1);
  overflow: hidden;
}
.terminal-bar {
  padding: 11px 16px;
  color: #8b9f9b;
  border-bottom: 1px solid #edf2f0;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  background: #f8fbfa;
}
.terminal-body {
  padding: 18px;
}
.map-visual .mini-map {
  position: relative;
  height: 300px;
  border: 1px solid #e6eeeb;
  border-radius: 8px;
  overflow: hidden;
  background:
    linear-gradient(rgba(47, 127, 128, 0.1) 1px, transparent 1px),
    linear-gradient(90deg, rgba(47, 127, 128, 0.1) 1px, transparent 1px),
    linear-gradient(180deg, rgba(47, 127, 128, 0.05), rgba(106, 159, 214, 0.06));
  background-size: 34px 34px, 34px 34px, 100% 100%;
}
.m-marker {
  position: absolute;
  width: 11px;
  height: 11px;
  border-radius: 50%;
  transform: translate(-50%, -50%);
  box-shadow: 0 0 14px currentColor;
  animation: marker-bob 2.6s ease-in-out infinite;
}
.m-marker.mm1 { left: 30%; top: 34%; background: #e07859; color: #e07859; }
.m-marker.mm2 { left: 52%; top: 62%; background: #deb154; color: #deb154; }
.m-marker.mm3 { left: 72%; top: 28%; background: #6a9fd6; color: #6a9fd6; }
.m-marker.mm4 { left: 63%; top: 44%; background: #5db486; color: #5db486; }
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
  padding: 8px 14px;
  border: 1px solid #d9e6e1;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 8px 20px rgba(31, 79, 70, 0.08);
  text-align: center;
}
.mini-map-info small {
  display: block;
  color: #829793;
  font-size: 10px;
}
.mini-map-info strong {
  display: block;
  margin-top: 3px;
  color: #24494d;
  font-size: 17px;
}
.chart-visual {
  display: grid;
  gap: 16px;
}
.big-chart {
  display: flex;
  align-items: flex-end;
  gap: 9px;
  height: 210px;
  padding: 8px;
  border: 1px solid #eef2f0;
  border-radius: 8px;
  background: #f7faf9;
}
.big-chart span {
  flex: 1;
  border-radius: 4px 4px 0 0;
  background: linear-gradient(180deg, #5db486, rgba(93, 180, 134, 0.22));
  animation: bar-rise 0.8s ease-out backwards;
  animation-delay: calc(var(--i) * 60ms);
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
  padding: 7px 12px;
  border-radius: 999px;
  font-size: 12px;
}
.chip i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}
.chip.on {
  color: #228b5e;
  border: 1px solid rgba(34, 139, 94, 0.3);
  background: rgba(34, 139, 94, 0.09);
}
.chip.on i { background: #3a8d6b; }
.chip.off {
  color: #d45c48;
  border: 1px solid rgba(212, 92, 72, 0.3);
  background: rgba(212, 92, 72, 0.08);
}
.chip.off i { background: #e07859; }
.alerts-visual {
  display: grid;
  gap: 9px;
}
.alert-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid #e5ece9;
  border-radius: 9px;
  background: #fbfdfc;
}
.alert-row > i {
  width: 9px;
  height: 9px;
  flex: 0 0 auto;
  border-radius: 50%;
  box-shadow: 0 0 10px currentColor;
}
.alert-row > div { min-width: 0; flex: 1; }
.alert-row strong {
  display: block;
  color: #24494d;
  font-size: 13px;
}
.alert-row small {
  display: block;
  margin-top: 3px;
  color: #829793;
  font-size: 11px;
}
.alert-row em {
  font-style: normal;
  font-size: 11px;
  font-weight: 700;
  padding: 3px 9px;
  border-radius: 999px;
}
.ar-high > i { background: #e07859; color: #e07859; }
.ar-high em { color: #d45c48; background: #fdf0ec; }
.ar-mid > i { background: #deb154; color: #deb154; }
.ar-mid em { color: #d18f2f; background: #fdf6e9; }
.ar-low > i { background: #6a9fd6; color: #6a9fd6; }
.ar-low em { color: #638fc2; background: #eaf1fa; }
.alert-footer {
  display: flex;
  justify-content: space-between;
  padding: 10px 4px 0;
  color: #8b9f9b;
  font-size: 11px;
}
.stats-visual {
  display: grid;
  grid-template-columns: 1fr 1.2fr;
  gap: 18px;
  align-items: center;
}
.stat-donut {
  display: grid;
  gap: 14px;
  justify-items: center;
}
.donut {
  position: relative;
  width: 130px;
  height: 130px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: conic-gradient(#3caa82 0 86%, #deb154 86% 95%, #e07859 95% 100%);
}
.donut::before {
  content: "";
  position: absolute;
  width: 98px;
  height: 98px;
  border-radius: 50%;
  background: #fff;
}
.donut span {
  position: relative;
  z-index: 1;
  color: #24494d;
  font-size: 20px;
  font-weight: 700;
}
.donut-legend {
  display: grid;
  gap: 6px;
}
.donut-legend span {
  display: flex;
  align-items: center;
  gap: 7px;
  color: #5f7b78;
  font-size: 12px;
}
.donut-legend i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.stat-bars {
  display: grid;
  gap: 12px;
}
.stat-bars div {
  display: grid;
  grid-template-columns: 30px 1fr;
  align-items: center;
  gap: 10px;
}
.stat-bars span {
  color: #829793;
  font-size: 11px;
}
.stat-bars i {
  height: 8px;
  border-radius: 999px;
  background: linear-gradient(90deg, #5db486, rgba(93, 180, 134, 0.25));
  width: var(--w);
  animation: bar-grow-x 1s ease-out backwards;
}
@keyframes bar-grow-x {
  from { width: 0; }
}

/* ====== 架构 ====== */
.architecture-section {
  padding-top: 120px;
}
.arch-diagram {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  flex-wrap: wrap;
}
.arch-node {
  min-width: 200px;
  padding: 26px 28px;
  border: 1px solid #e3ebe7;
  border-radius: 14px;
  background: linear-gradient(180deg, #fff, #f8fbfa);
  box-shadow: 0 5px 18px rgba(30, 75, 69, 0.03);
  text-align: center;
  transition: border-color 0.25s, transform 0.25s;
}
.arch-node:hover {
  border-color: #2f7f80;
  transform: translateY(-3px);
}
.arch-icon {
  display: inline-grid;
  place-items: center;
  width: 48px;
  height: 48px;
  color: #2d8280;
  border-radius: 12px;
  background: #e4f4ef;
  margin-bottom: 14px;
}
.arch-node strong {
  display: block;
  color: #24494d;
  font-size: 16px;
}
.arch-node small {
  display: block;
  margin-top: 7px;
  color: #7f9794;
  font-size: 12px;
  line-height: 1.6;
}
.arch-link {
  color: #2f7f80;
  animation: link-nudge 1.6s ease-in-out infinite;
}
@keyframes link-nudge {
  0%, 100% { transform: translateX(0); }
  50% { transform: translateX(4px); }
}

/* ====== 定制化 ====== */
.tailored-section {
  padding-top: 120px;
}

/* ====== 角色方案 ====== */
.roles-section {
  padding-top: 120px;
}
.roles-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 18px;
}
.role-card {
  position: relative;
  display: flex;
  flex-direction: column;
  padding: 30px 28px;
  border: 1px solid #e3ebe7;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 5px 18px rgba(30, 75, 69, 0.03);
  transition: border-color 0.25s, transform 0.25s, box-shadow 0.25s;
}
.role-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 18px 40px rgba(30, 75, 69, 0.08);
}
.role-card.featured {
  border-color: rgba(47, 127, 128, 0.4);
  background: linear-gradient(180deg, #eef7f4, #fff);
  box-shadow: 0 20px 50px rgba(47, 127, 128, 0.12);
}
.role-badge {
  position: absolute;
  top: 16px;
  right: 16px;
  padding: 4px 10px;
  color: #083f40;
  border-radius: 999px;
  background: linear-gradient(135deg, #f7d28c, #eeb764);
  font-size: 11px;
  font-weight: 700;
}
.role-name {
  display: block;
  color: #24494d;
  font-size: 20px;
  font-weight: 700;
}
.role-en {
  display: block;
  margin-top: 5px;
  color: #9aaead;
  font-size: 10px;
  letter-spacing: 0.24em;
}
.role-desc {
  margin: 16px 0 0;
  color: #607c79;
  font-size: 13px;
  line-height: 1.7;
  min-height: 44px;
}
.role-features {
  list-style: none;
  margin: 20px 0 0;
  padding: 18px 0 0;
  border-top: 1px solid #edf2f0;
  display: grid;
  gap: 10px;
}
.role-features li {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #37555a;
  font-size: 13px;
}
.role-features li svg {
  color: #3a8d6b;
  flex: 0 0 auto;
}
.role-cta {
  margin-top: auto;
  padding: 11px 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin-top: 22px;
  color: #547474;
  border: 1px solid #dbe6e2;
  border-radius: 9px;
  background: #fff;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s, color 0.2s;
}
.role-card.featured .role-cta {
  border-color: transparent;
  color: #fff;
  background: linear-gradient(135deg, #3fae97, #246f70);
  font-weight: 700;
}
.role-cta:hover {
  border-color: #2f7f80;
  color: #2f7f80;
  background: #f0faf7;
}
.role-card.featured .role-cta:hover {
  color: #fff;
  background: linear-gradient(135deg, #47b8a0, #277061);
}

/* ====== FAQ ====== */
.faq-section {
  padding-top: 120px;
}
.faq-list {
  max-width: 760px;
  margin: 0 auto;
  display: grid;
  gap: 10px;
}
.faq-item {
  border: 1px solid #e3ebe7;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 5px 18px rgba(30, 75, 69, 0.03);
  overflow: hidden;
  transition: border-color 0.25s;
}
.faq-item.open {
  border-color: rgba(47, 127, 128, 0.45);
}
.faq-q {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 18px 20px;
  color: #24494d;
  border: 0;
  background: transparent;
  font-size: 15px;
  text-align: left;
  cursor: pointer;
}
.faq-toggle {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  flex: 0 0 auto;
  color: #2f7f80;
  border-radius: 8px;
  background: #eef7f4;
}
.faq-a {
  padding: 0 20px 18px;
}
.faq-a p {
  margin: 0;
  color: #607c79;
  font-size: 14px;
  line-height: 1.75;
}
.faq-enter-active,
.faq-leave-active {
  transition: opacity 0.25s ease;
}
.faq-enter-from,
.faq-leave-to {
  opacity: 0;
}

/* ====== CTA ====== */
.cta-section {
  padding-top: 120px;
  padding-bottom: 0;
}
.cta-card {
  position: relative;
  border: 1px solid rgba(47, 127, 128, 0.25);
  border-radius: 20px;
  background: linear-gradient(135deg, #e6f5ee, #eef4fb);
  overflow: hidden;
  text-align: center;
  padding: 70px 32px;
}
.cta-glow {
  position: absolute;
  left: 50%;
  top: -120px;
  width: 480px;
  height: 300px;
  transform: translateX(-50%);
  background: radial-gradient(circle, rgba(47, 127, 128, 0.18), transparent 70%);
  filter: blur(60px);
  pointer-events: none;
}
.cta-content {
  position: relative;
  z-index: 1;
}
.cta-mascot {
  display: inline-flex;
  margin-bottom: 18px;
}
.cta-card h2 {
  margin: 0;
  color: #1f3e44;
  font-size: clamp(28px, 4vw, 38px);
  letter-spacing: -0.03em;
}
.cta-card p {
  margin: 14px auto 30px;
  max-width: 440px;
  color: #5f7b78;
  font-size: 15px;
  line-height: 1.7;
}
.btn-light {
  border: 1px solid #2b7778;
  background: #2f7f80;
  box-shadow: 0 10px 30px rgba(47, 127, 128, 0.25);
}
.btn-light:hover {
  background: #246b6c;
}
.cta-hint {
  display: block;
  margin-top: 18px;
  color: #829793;
  font-size: 12px;
}

/* ====== 页脚 ====== */
.footer {
  position: relative;
  z-index: 1;
  margin-top: 110px;
  border-top: 1px solid rgba(47, 127, 128, 0.12);
}
.footer-inner {
  max-width: 1240px;
  margin: 0 auto;
  padding: 40px 24px;
  display: flex;
  align-items: center;
  gap: 24px;
  flex-wrap: wrap;
}
.footer-brand {
  display: flex;
  align-items: center;
  gap: 12px;
}
.footer-brand .brand-mark {
  width: 34px;
  height: 34px;
  background: #f4c87b;
  color: #083f40;
  box-shadow: 0 4px 12px rgba(224, 160, 80, 0.25);
}
.footer-brand strong {
  display: block;
  color: #24494d;
  font-size: 14px;
}
.footer-brand small {
  display: block;
  margin-top: 3px;
  color: #829793;
  font-size: 11px;
}
.footer-links {
  display: flex;
  gap: 18px;
  margin-left: auto;
}
.footer-links a {
  color: #7f9794;
  font-size: 13px;
  transition: color 0.2s;
}
.footer-links a:hover {
  color: #2f7f80;
}
.footer-copy {
  width: 100%;
  margin: 0;
  color: #9aaead;
  font-size: 12px;
}

/* ====== Reveal ====== */
.reveal {
  opacity: 0;
  transform: translateY(26px);
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
    top: 64px;
    left: 12px;
    right: 12px;
    flex-direction: column;
    padding: 10px;
    border: 1px solid #e3ebe7;
    border-radius: 12px;
    background: #fff;
    box-shadow: 0 20px 50px rgba(25, 65, 64, 0.14);
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
    gap: 34px;
  }
  .workflow-row.reversed {
    direction: ltr;
  }
  .stats-strip {
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
    transform: rotate(90deg);
  }
}

@media (max-width: 560px) {
  .hero {
    padding: 120px 18px 44px;
  }
  .hero-title {
    font-size: clamp(34px, 9vw, 48px);
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
  .preview-top {
    grid-template-columns: repeat(2, 1fr);
  }
  .preview-map {
    height: 200px;
  }
  .stats-strip {
    grid-template-columns: 1fr;
  }
  .section {
    padding: 70px 18px 0;
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
  .stat-bars i {
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
  background: rgba(31, 62, 68, 0.5);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
  padding: 20px;
}

.login-modal {
  position: relative;
  width: 100%;
  max-width: 440px;
  max-height: 90vh;
  overflow-y: auto;
  padding: 36px 32px;
  border: 1px solid rgba(229, 236, 233, 0.9);
  border-radius: 18px;
  background: linear-gradient(180deg, #fff 0%, #f8faf9 100%);
  box-shadow:
    0 32px 80px rgba(31, 79, 70, 0.18),
    0 0 0 1px rgba(57, 88, 82, 0.05);
}

.modal-close {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  color: #7f9794;
  border: 1px solid #e3ebe7;
  border-radius: 9px;
  background: #fff;
  cursor: pointer;
  transition: color 0.2s, border-color 0.2s, background 0.2s;
}
.modal-close:hover {
  color: #2f7f80;
  border-color: #2f7f80;
  background: #f0faf7;
}

.login-modal-head {
  margin-bottom: 28px;
}
.login-modal-head .eyebrow {
  display: inline-block;
  color: #2f7f80;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.22em;
  margin-bottom: 8px;
}
.login-modal-head h2 {
  margin: 0;
  color: #24494d;
  font-size: 26px;
  letter-spacing: -0.02em;
}
.login-subtitle {
  margin: 8px 0 0;
  color: #78908f;
  font-size: 14px;
}

.login-modal form {
  display: grid;
  gap: 18px;
}
.login-modal form label {
  display: block;
  color: #37555a;
  font-size: 13px;
  font-weight: 500;
}
.login-input {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 7px;
  padding: 0 14px;
  border: 1px solid #d9e6e1;
  border-radius: 10px;
  background: #fff;
  color: #5f7b78;
  transition: border-color 0.2s;
}
.login-input:focus-within {
  border-color: #2f7f80;
}
.login-input svg {
  flex: 0 0 auto;
  color: #9aaead;
}
.login-input select,
.login-input input {
  flex: 1;
  padding: 13px 0;
  border: 0;
  background: transparent;
  color: #24494d;
  font-size: 14px;
  outline: none;
}
.login-input select {
  cursor: pointer;
}
.login-input input::placeholder {
  color: #b8c9c5;
}
.login-input button {
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  background: transparent;
  color: #9aaead;
  cursor: pointer;
  transition: color 0.2s;
}
.login-input button:hover {
  color: #5f7b78;
}

.login-caps {
  margin: -10px 0 0;
  padding: 8px 12px;
  color: #d18f2f;
  font-size: 12px;
  background: #fdf6e9;
  border-radius: 8px;
}

.login-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: -6px;
}
.login-remember {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #607c79;
  font-size: 13px;
  cursor: pointer;
}
.login-remember input {
  width: 16px;
  height: 16px;
  accent-color: #2f7f80;
}
.login-forgot {
  padding: 0;
  color: #2f7f80;
  border: 0;
  background: transparent;
  font-size: 13px;
  cursor: pointer;
  transition: color 0.2s;
}
.login-forgot:hover {
  color: #3a8d6b;
}

.login-error {
  padding: 10px 14px;
  color: #c23d2a;
  font-size: 13px;
  background: #fdf0ec;
  border: 1px solid rgba(212, 92, 72, 0.3);
  border-radius: 9px;
}
.login-hint {
  padding: 10px 14px;
  color: #2d8280;
  font-size: 13px;
  background: #eef7f4;
  border: 1px solid rgba(47, 127, 128, 0.25);
  border-radius: 9px;
}

.login-btn {
  width: 100%;
  margin-top: 4px;
  padding: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: 15px;
}

.demo-accounts {
  margin-top: 24px;
  padding-top: 22px;
  border-top: 1px solid #edf2f0;
}
.demo-accounts > span {
  display: block;
  margin-bottom: 12px;
  color: #9aaead;
  font-size: 12px;
}
.demo-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.demo-role-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 10px 8px;
  color: #607c79;
  border: 1px solid #e3ebe7;
  border-radius: 10px;
  background: #fff;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.2s, color 0.2s, background 0.2s;
}
.demo-role-btn small {
  color: #9aaead;
  font-size: 10px;
  letter-spacing: 0.1em;
}
.demo-role-btn:hover {
  border-color: #2f7f80;
  color: #2f7f80;
  background: #f0faf7;
}
.demo-role-btn.active {
  border-color: #2f7f80;
  color: #2f7f80;
  background: #eef7f4;
}

.login-footer {
  display: flex;
  justify-content: center;
  gap: 18px;
  margin-top: 18px;
  color: #9aaead;
  font-size: 12px;
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
  transition: transform 0.25s ease, opacity 0.25s ease;
}
.modal-enter-from .login-modal,
.modal-leave-to .login-modal {
  transform: scale(0.95) translateY(10px);
  opacity: 0;
}
</style>