/**
 * 业务流程类离线 mock：
 * - 确认工作台（人工确认队列、统计）
 * - 待办中心（待我确认 / 待我审批 / 待发布预警 / 待跟踪任务）
 * - 审批中心（列表 / 统计 离线骨架）
 * - 预警发布回执（汇总 / 明细）
 * - 顶栏红点计数（预警 / 消息）
 */
import { SEED_BASE } from "./seed";

// =====================================================================
// 人工确认工作台：默认 2 条示例数据 + 统计
// =====================================================================

export function getConfirmationQueueMock() {
  return {
    items: [
      {
        id: 101,
        hazardCode: "HZ-001",
        name: "青龙坡滑坡隐患点",
        type: "滑坡",
        level: "高风险",
        confidence: 0.86,
        aiScore: 78.3,
        reason: "InSAR 形变 + 影像裂缝 + 24h 降雨 38mm 三重证据",
        discoveredAt: new Date(Date.now() - 8 * 60 * 1000).toISOString(),
        status: "待确认",
        assignee: null,
      },
      {
        id: 102,
        hazardCode: "HZ-005",
        name: "石门沟滑坡点",
        type: "滑坡",
        level: "关注",
        confidence: 0.71,
        aiScore: 62.4,
        reason: "GNSS 位移速率超过阈值 32%",
        discoveredAt: new Date(Date.now() - 33 * 60 * 1000).toISOString(),
        status: "待确认",
        assignee: null,
      },
    ],
    total: 2,
    pending: 2,
  };
}

export const confirmationQueueStatsMock = { pending: 2 };

// =====================================================================
// 预警统计（顶栏红点）
// =====================================================================

export const warningStatsMock = { pending: 5, dispatched: 2, closed: 18 };

// =====================================================================
// 待办中心 —— 4 个汇总入口
// =====================================================================

/** 待我确认（取 SEED_BASE.hazardPoints 前 8 条派生） */
export function listMyConfirmationsMock() {
  return {
    list: (SEED_BASE.hazardPoints ?? []).slice(0, 8).map((item) => ({
      id: item.id,
      title: item.name ?? `隐患点 ${item.code ?? item.id}`,
      point: item.location ?? "—",
      level: item.riskLevel ?? item.level ?? "黄色",
      status: item.status ?? "待确认",
      unread: Math.random() > 0.5,
      submittedAt: item.discoveredAt ?? new Date().toISOString(),
      reporter: item.reporter ?? "群测群防员",
    })),
    total: (SEED_BASE.hazardPoints ?? []).length,
  };
}

/** 待我审批（SEED_BASE.warnings 中 status=待确认/已派发/待审批） */
export function listMyApprovalsMock() {
  const list = (SEED_BASE.warnings ?? []).filter(
    (w) =>
      w.status === "待确认" || w.status === "已派发" || w.status === "待审批",
  );
  return {
    list: list.map((w) => ({
      id: w.id,
      title: w.title,
      category: w.category ?? "预警发布",
      applicant: w.applicant ?? w.dispatcher ?? "调度员",
      level: w.level ?? "蓝色",
      submittedAt: w.submittedAt ?? w.time,
      status: w.status,
      deadline: w.deadline ?? null,
    })),
    total: list.length,
  };
}

/** 待发布预警 */
export function listMyPendingWarningsMock() {
  const list = (SEED_BASE.warnings ?? []).filter(
    (w) => w.status === "待确认" || w.status === "待发布",
  );
  return {
    list: list.map((w) => ({
      id: w.id,
      title: w.title,
      level: w.level ?? "蓝色",
      point: w.point ?? "—",
      dueAt: w.dueAt ?? w.time,
      status: w.status,
      category: w.category ?? "预警发布",
    })),
    total: list.length,
  };
}

/** 待跟踪任务 = 任务 + 已派发预警 */
export function listMyOpenTasksMock() {
  const taskList = (SEED_BASE.tasks ?? []).slice(0, 6).map((t) => ({
    id: t.id,
    title: t.title ?? `巡检任务 ${t.code ?? t.id}`,
    type: "任务",
    point: t.point ?? "—",
    assignee: t.assignee ?? "现场作业组",
    progress: t.progress ?? Math.floor(Math.random() * 80) + 10,
    status: t.status ?? "执行中",
    dueAt: t.dueAt ?? null,
  }));
  const warnList = (SEED_BASE.warnings ?? [])
    .filter((w) => w.status === "已派发" || w.status === "处理中")
    .slice(0, 6)
    .map((w) => ({
      id: w.id,
      title: w.title,
      type: "预警",
      point: w.point ?? "—",
      assignee: w.assignee ?? w.dispatcher ?? "应急值守组",
      progress: w.progress ?? Math.floor(Math.random() * 70) + 20,
      status: w.status,
      dueAt: w.dueAt ?? w.time,
    }));
  return {
    list: [...taskList, ...warnList],
    total: taskList.length + warnList.length,
  };
}

// =====================================================================
// 消息中心：5 条示例 + 未读统计
// =====================================================================

export function getMessagesMock() {
  return {
    records: [
      {
        id: 1,
        type: "warning",
        title: "青龙坡滑坡隐患点 橙色预警派发",
        content: "派发给龙安镇值班组，请即刻处置",
        time: "8分钟前",
        unread: true,
        severity: "high",
      },
      {
        id: 2,
        type: "ai",
        title: "AI 发现疑似灾害事件",
        content: "AI 模型在大岭村附近发现疑似滑坡，置信度 86%，请人工确认",
        time: "12分钟前",
        unread: true,
        severity: "medium",
      },
      {
        id: 3,
        type: "system",
        title: "设备 RAIN-052 离线",
        content: "过去 18 分钟未上报，请检查供电与链路",
        time: "21分钟前",
        unread: true,
        severity: "medium",
      },
      {
        id: 4,
        type: "task",
        title: "今日巡检任务待处理",
        content: "共 3 项任务未完成，截止 18:00",
        time: "36分钟前",
        unread: false,
        severity: "low",
      },
      {
        id: 5,
        type: "warning",
        title: "白石沟泥石流黄色预警已解除",
        content: "降雨停止，预警级别降为关注",
        time: "2小时前",
        unread: false,
        severity: "low",
      },
    ],
    total: 5,
    page: 1,
    size: 5,
    pages: 1,
  };
}

export const messageStatsMock = { unread: 3 };
