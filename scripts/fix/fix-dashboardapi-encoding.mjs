/**
 * 一次性修复：还原 dashboardApi.js 因编码损坏丢失的中文字符串字面量。
 *
 * 背景：frontend/src/services/dashboardApi.js 曾经历一次编码损坏事故，
 * 文件内中文全部退化为 '?'（共 333 行）。其中 109 处位于**字符串字面量**内，
 * 会直接影响运行时展示（报错提示、导出文件名、状态标签等）。
 *
 * 还原依据（非猜测，均取自仓库内权威来源）：
 *   - 状态机标签  -> components/event/EventDetailDrawer.vue:48-54、views/system/EventCenterPage.vue:65-71
 *   - 解除范围    -> backend WarningService.java:235-241 的 resolveScope()
 *   - 其余功能名  -> 按所在函数名 + requireBackend() 的文案语义生成
 *                    （requireBackend 拼装为 `${feature} 需要后端服务。...`）
 *
 * 用法：node scripts/fix/fix-dashboardapi-encoding.mjs
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const TARGET = path.join(ROOT, "frontend/src/services/dashboardApi.js");

// 行号 -> 该行内按出现顺序待替换的字符串
const MAP = {
  100: ["用户名和密码不能为空"],
  115: ["综合态势"],
  119: ["灾害列表"],
  136: ["获取 AI 监测灾害"],
  149: ["按视野查询灾害"],
  156: ["灾害详情"],
  160: ["新建灾害"],
  167: ["更新灾害"],
  174: ["删除灾害"],
  178: ["巡检任务"],
  190: ["任务详情"],
  194: ["新建任务"],
  198: ["更新任务"],
  205: ["更新任务状态"],
  212: ["删除任务"],
  216: ["设备列表"],
  229: ["按视野查询设备"],
  236: ["设备详情"],
  240: ["更新设备"],
  247: ["删除设备"],
  251: ["设备诊断"],
  255: ["预警列表"],
  268: ["预警预览"],
  276: ["按视野查询预警"],
  283: ["预警详情"],
  287: ["更新预警"],
  294: ["确认预警"],
  298: ["下发预警"],
  302: ["关闭预警"],
  303: ["未填写关闭原因"],
  310: ["解除预警"],
  314: ["预警解除"],
  318: ["应急事件列表"],
  328: ["处置中", "处置中", "已结束"],
  341: ["人员列表"],
  346: ["关闭应急事件"],
  351: ["应急处置已完成"],
  355: ["综合态势报表.xlsx"],
  358: ["灾害统计报表.xlsx"],
  361: ["巡检任务报表.xlsx"],
  364: ["设备台账报表.xlsx"],
  367: ["预警统计报表.xlsx"],
  370: ["应急事件报表.xlsx"],
  396: ["附件上传失败"],
  504: ["上报人员定位"],
  649: ["认领会签任务"],
  658: ["转派会签任务"],
  667: ["确认灾害"],
  676: ["驳回灾害"],
  685: ["发布预警"],
  708: ["资源调度"],
  722: ["切换数据源"],
  736: ["同步统计"],
  745: ["同步任务"],
  754: ["同步历史"],
  763: ["数据完整性报告"],
  772: ["同步异常列表"],
  780: ["触发同步"],
  791: ["暂停同步"],
  802: ["忽略异常"],
  810: ["测试数据源连接"],
  826: ["新建用户"],
  834: ["更新用户"],
  842: ["删除用户"],
  882: ["标记已读"],
  887: ["全部标记已读"],
  931: ["重试任务"],
  941: ["终止任务"],
  954: ["待办汇总"],
  999: ["审批列表"],
  1008: ["审批统计"],
  1018: ["审批决策"],
  1058: ["重新投递"],
  1923: ["全部"],
  1924: ["龙安镇", "龙安镇"],
  1925: ["石桥镇", "石桥镇"],
  1926: ["清河镇", "清河镇"],
  1931: ["解除预警"],
  1942: ["系统管理员"],
  1943: ["应急管理中心"],
  1960: ["修改密码"],
  2139: ["统一事件状态流转"],
  2165: ["AI 识别"],
  2166: ["人工上报"],
  2167: ["忽略"],
  2170: ["确认"],
  2171: ["驳回"],
  2172: ["忽略"],
  2175: ["发布预警"],
  2176: ["启动应急"],
  2177: ["忽略"],
  2180: ["启动应急"],
  2181: ["关闭"],
  2184: ["结束应急"],
  2187: ["重新打开"],
  2190: ["重新打开"],
  2202: ["已发现"],
  2203: ["确认中"],
  2204: ["已确认"],
  2205: ["预警发布"],
  2206: ["应急处置"],
  2207: ["已关闭"],
  2208: ["已驳回"],
};

const src = fs.readFileSync(TARGET, "utf8");
const lines = src.split("\n");
let replaced = 0;
const missed = [];

for (const [lineNo, vals] of Object.entries(MAP)) {
  const idx = Number(lineNo) - 1;
  let i = 0;
  lines[idx] = lines[idx].replace(
    /(["'`])([^"'`]*\?\?[^"'`]*)\1/g,
    (_full, quote, inner) => {
      if (i < vals.length) {
        replaced += 1;
        return quote + vals[i++] + quote;
      }
      return quote + inner + quote;
    },
  );
  if (i < vals.length) missed.push(`${lineNo}: 期望 ${vals.length} 处，实际 ${i} 处`);
}

if (missed.length) {
  console.error("部分替换未完成：");
  missed.forEach((m) => console.error("  " + m));
  process.exit(1);
}

fs.writeFileSync(TARGET, lines.join("\n"), "utf8");
console.log(`已还原 ${replaced} 处字符串字面量`);

const remain = lines.filter((l) => /\?\?/.test(l)).length;
console.log(`文件内仍含 '?' 的行数：${remain}（均为注释，不影响运行）`);
