/**
 * 前端缺失 import 检测（Vue SFC + JS）。
 *
 * 背景：2026-10-08 巡检发现 5 个页面在运行时崩溃（错误边界 "页面出错了"），
 *   根因全是 `xxx is not defined`：
 *     - AiResultsPage.vue     用了 reactive() 但 import 里没有
 *     - UserManagementPage.vue 用了 watch() 但 import 里没有
 *     - MessageCenterPage.vue  同上
 *     - DamageAssessmentPage.vue 用了图标组件 Mountain 但没 import
 *   这类问题 vite build 不会报（构建时 <script setup> 里未定义标识符
 *   只在运行时报错），必须静态扫描。
 *
 * 策略：
 *   1. 收集文件里所有出现的标识符调用（fn( 形式）与 PascalCase 用法
 *   2. 收集该文件所有 import 来源
 *   3. 与已知的 vue API / lucide 图标清单比对，报出"用了但没导入"的
 *
 * 用法：node scripts/audit/check-frontend-imports.mjs [--fix]
 */
import fs from 'node:fs'
import path from 'node:path'

const SRC = 'frontend/src'
const FIX = process.argv.includes('--fix')

// Vue 3 组合式 API 白名单（用了就必须从 vue import）
const VUE_APIS = [
  'ref','reactive','computed','watch','watchEffect','onMounted','onUnmounted','onBeforeMount',
  'onBeforeUnmount','onActivated','onDeactivated','onUpdated','onBeforeUpdate','nextTick',
  'provide','inject','defineComponent','defineAsyncComponent','toRef','toRefs','unref',
  'shallowRef','shallowReactive','readonly','markRaw','isRef','triggerRef','customRef',
  'h','useSlots','useAttrs','getCurrentInstance','onErrorCaptured','watchPostEffect',
]

// lucide-vue-next 图标（PascalCase，常见的）
const LUCIDE = [
  'Mountain','MountainSnow','ShieldCheck','ShieldAlert','AlertTriangle','AlertCircle','Bell','BellRing',
  'Search','Settings','User','Users','LogOut','Menu','X','Check','CheckCircle','CheckCircle2','ChevronDown',
  'ChevronRight','ChevronLeft','ChevronsLeft','ChevronsRight','CircleHelp','HelpCircle','Home','Map','MapPin',
  'Activity','BarChart3','BarChart','LineChart','PieChart','TrendingUp','TrendingDown','Database','Server',
  'Cloud','CloudRain','Wind','Thermometer','Droplets','Waves','Zap','Cpu','HardDrive','Wifi','WifiOff',
  'FileText','File','Folder','FolderOpen','Download','Upload','Trash2','Edit','Pencil','Plus','Minus',
  'RefreshCw','RotateCw','Play','Pause','Square','Circle','Info','Eye','EyeOff','Lock','Unlock','Key',
  'Calendar','Clock','Timer','Filter','Sliders','MoreVertical','MoreHorizontal','ExternalLink','Link','Copy',
  'ClipboardList','Clipboard','Send','Mail','MessageSquare','Phone','Video','Image','Camera','Paperclip',
  'ArrowUp','ArrowDown','ArrowLeft','ArrowRight','ArrowUpRight','ArrowDownRight','Maximize','Minimize',
  'Star','Heart','Bookmark','Tag','Flag','Award','Target','Crosshair','Navigation','Compass','Route',
  'Layers','Grid','Layout','LayoutDashboard','Monitor','Smartphone','Tablet','Printer','Scan','QrCode',
  'Building','Building2','Briefcase','Truck','Package','Boxes','Box','Wrench','Hammer','Tool','Cog',
  'Rocket','Flame','Snowflake','Sun','Moon','CloudLightning','CloudSnow','CloudFog','Umbrella',
  'Radio','Rss','Antenna','Radar','Satellite','Gauge','Scale','Ruler','MapPinned','Locate','LocateFixed',
  'Globe','Globe2','Landmark','GitBranch','Bot','HardHat','ClipboardCheck','ClipboardList','Sparkles',
  'ScrollText','Inbox','ListChecks','UserCheck','UserCircle','FileWarning','MessageCircle','History',
  'LayoutList','Siren','Mountain','Trash2','Share2','SlidersHorizontal','CircleCheck','CircleAlert',
  'TriangleAlert','Workflow','Waypoints','Milestone','Flag','Bookmark','Download','CloudUpload',
]

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.posix.join(dir, e.name)
    if (e.isDirectory()) {
      if (!/node_modules|dist/.test(e.name)) walk(p, out)
    } else if (/\.(vue|js)$/.test(e.name)) {
      out.push(p)
    }
  }
  return out
}

// 去掉注释，避免"注释里提到某 API"被误报
function stripComments(s) {
  return s
    .replace(/\/\*[\s\S]*?\*\//g, '')   // /* ... */
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1') // // ...（保留 https:// 这类协议后的内容）
}

const problems = []

for (const file of walk(SRC)) {
  const src = fs.readFileSync(file, 'utf8')
  if (src.includes('\uFFFD')) continue

  // 取 <script setup> 段（无则取 <script>）
  const m = src.match(/<script[^>]*>([\s\S]*?)<\/script>/)
  const code = stripComments(m ? m[1] : src)

  // 收集所有 import 语句（含多行）
  const importBlocks = [...code.matchAll(/import\s+([\s\S]*?)\s+from\s+['"][^'"]+['"]/g)].map((x) => x[1])
  const importedNames = new Set()
  for (const b of importBlocks) {
    // { a, b as c } 形式
    const braced = b.match(/\{([\s\S]*)\}/)
    if (braced) {
      for (const raw of braced[1].split(',')) {
        const name = raw.trim().split(/\s+as\s+/).pop().trim()
        if (name) importedNames.add(name)
      }
    }
    // default import 形式
    const def = b.replace(/\{[\s\S]*\}/, '').split(',')[0].trim()
    if (def && !def.includes('*')) importedNames.add(def)
  }

  const missingVue = []
  const missingLucide = []

  for (const api of VUE_APIS) {
    const used = new RegExp('(?<![\\w$.])' + api + '\\s*\\(').test(code)
    if (used && !importedNames.has(api)) missingVue.push(api)
  }

  // 图标：必须是"真的当组件/值用"，排除字符串与普通英文单词误报
  const tplMatch = src.match(/<template>([\s\S]*)<\/template>/)
  const template = tplMatch ? tplMatch[1] : ''
  for (const icon of LUCIDE) {
    // 模板里作为标签: <Icon 或 <Icon/>（后随空格/斜杠，避免 Mountain 误匹配 MountainSnow）
    const inTpl = new RegExp('<' + icon + '[\\s/>]').test(template)
    // script 里作为值: :is="Icon" / component(Icon) / [Icon] / Icon, / Icon) / = Icon
    const inScript = new RegExp(
      '(?<![\\w$."\'])' + icon + '(?![\\w$"\'])\\s*[,)\\}\\]]|:\\s*' + icon + '\\b(?!\\w)|=\\s*' + icon + '\\b(?!\\w)',
    ).test(code)
    if ((inTpl || inScript) && !importedNames.has(icon)) missingLucide.push(icon)
  }

  if (missingVue.length || missingLucide.length) {
    problems.push({ file, missingVue, missingLucide, code })
  }

  // ── 附加检查：computed 被当函数调用（Vue 模板中 computed 不应加括号）──
  const computedNames = [...code.matchAll(/const\s+(\w+)\s*=\s*computed\(/g)].map((x) => x[1])
  if (computedNames.length) {
    const tplAll = src.match(/<template>([\s\S]*)<\/template>/)
    const tplBody = tplAll ? tplAll[1] : ''
    const misused = computedNames.filter((n) => new RegExp('(?<![\\w$.])' + n + '\\s*\\(').test(tplBody))
    if (misused.length) {
      problems.push({ file, misusedComputed: misused, code })
    }
  }
}

// 单独汇总 computed 误用
const computedProblems = problems.filter((p) => p.misusedComputed)
if (computedProblems.length) {
  console.log(`\n⚠ computed 在模板中被当函数调用（应为 {{ name }} 而非 {{ name() }}）：`)
  for (const p of computedProblems) {
    console.log(`  ${p.file}\n      ${p.misusedComputed.join(', ')}`)
  }
}

// 输出
const importProblems = problems.filter((p) => p.missingVue?.length || p.missingLucide?.length)
if (!importProblems.length && !computedProblems.length) {
  console.log('✅ 未发现前端缺失 import / computed 误用')
  process.exit(0)
}

if (importProblems.length) {
  console.log(`❌ 发现 ${importProblems.length} 个文件存在缺失 import：\n`)
  for (const p of importProblems) {
    const parts = []
    if (p.missingVue.length) parts.push(`vue: ${p.missingVue.join(', ')}`)
    if (p.missingLucide.length) parts.push(`icons: ${p.missingLucide.join(', ')}`)
    console.log(`  ${p.file}\n      ${parts.join('  |  ')}`)
  }
}

// 单独汇总 computed 误用
const computedProblems2 = problems.filter((p) => p.misusedComputed?.length)
if (computedProblems2.length) {
  console.log(`\n⚠ ${computedProblems2.length} 个文件把 computed 当函数调用（模板应为 {{ name }} 而非 {{ name() }}）：`)
  for (const p of computedProblems2) {
    console.log(`  ${p.file}\n      ${p.misusedComputed.join(', ')}`)
  }
}

if (FIX) {
  console.log('\n── 开始修复 ──')
  let fixed = 0
  for (const p of importProblems) {
    let out = fs.readFileSync(p.file, 'utf8')
    // 修复 vue API
    if (p.missingVue.length) {
      const re = /import\s*\{([^}]*)\}\s*from\s*(['"])vue\2/
      const mm = out.match(re)
      if (mm) {
        const existing = mm[1].split(',').map((x) => x.trim()).filter(Boolean)
        const merged = [...new Set([...existing, ...p.missingVue])].sort()
        out = out.replace(mm[0], `import { ${merged.join(', ')} } from ${mm[2]}vue${mm[2]}`)
      } else {
        out = `import { ${p.missingVue.join(', ')} } from 'vue'\n` + out
      }
    }
    // 修复 icons
    if (p.missingLucide.length) {
      const re = /import\s*\{([^}]*)\}\s*from\s*(['"])lucide-vue-next\2/
      const mm = out.match(re)
      if (mm) {
        const existing = mm[1].split(',').map((x) => x.trim()).filter(Boolean)
        const merged = [...new Set([...existing, ...p.missingLucide])].sort()
        out = out.replace(mm[0], `import { ${merged.join(', ')} } from ${mm[2]}lucide-vue-next${mm[2]}`)
      } else {
        // 插到最后一个 import 之后
        const lastImport = [...out.matchAll(/^import .*$/gm)].pop()
        const line = `import { ${p.missingLucide.join(', ')} } from 'lucide-vue-next'`
        if (lastImport) {
          out = out.slice(0, lastImport.index + lastImport[0].length) + '\n' + line + out.slice(lastImport.index + lastImport[0].length)
        } else {
          out = line + '\n' + out
        }
      }
    }
    fs.writeFileSync(p.file, Buffer.from(out, 'utf8'))
    const back = fs.readFileSync(p.file, 'utf8')
    if (back.includes('\uFFFD')) console.log(`  ⚠ ${p.file} 出现编码损坏!`)
    else console.log(`  ✔ ${p.file}`)
    fixed++
  }
  console.log(`\n修复 ${fixed} 个文件`)
} else {
  console.log('\n提示：加 --fix 可自动补全 import')
  process.exit(1)
}
