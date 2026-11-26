package cn.disasterguard.warning;

import java.util.List;
import java.util.Set;

/**
 * 预警状态唯一口径（{@code warning_event.status}）。
 *
 * 历史问题（R11 L3）：状态是中文裸字面量，散落在 ≥11 个后端文件、37 处；
 * 终态集合 {@code List.of("已关闭","已忽略","已解除")} 在 7 个文件各写一份。
 * 一处拼写漂移（例如「已解除」写成「已解除预警」）就会让筛选/统计静默漏数，
 * 且编译期完全无感。这里收敛成常量 + 终态集合。
 *
 * 注意与 {@link cn.disasterguard.event.EventState} 区分：本类描述的是**预警单据**状态，
 * EventState 描述的是**统一事件**状态机（DETECTED/CONFIRMED/…）。两者通过
 * {@code WarningService#triggerForStatus} 桥接，不要把两套词表混用。
 *
 * 另：{@code "已发布"} 是应急事件的发布态，不是预警状态 —— 系统内没有任何路径把
 * warning.status 写成该值（见 {@code WarningService#resolve} 注释）。保留兼容判断时
 * 请显式注明，不要再新增一份终态/活跃集合。
 */
public final class WarningStatus {

  /** 待确认：AI 推送或人工上报后等待复核。 */
  public static final String PENDING = "待确认";
  /** 已确认：复核通过，准备发布。 */
  public static final String CONFIRMED = "已确认";
  /** 处理中：已派发处置。 */
  public static final String PROCESSING = "处理中";
  /** 已解除：风险消除（终态）。 */
  public static final String RESOLVED = "已解除";
  /** 已忽略：误报驳回（终态）。 */
  public static final String IGNORED = "已忽略";
  /** 已关闭：处置完成（终态）。 */
  public static final String CLOSED = "已关闭";

  /**
   * 终态集合。顺序固定为「已关闭 / 已忽略 / 已解除」，
   * 供 JPA 派生查询按 {@code List} 参数传入（{@code ... NOT IN (:statuses)}）。
   */
  public static final List<String> TERMINAL = List.of(CLOSED, IGNORED, RESOLVED);

  /** 终态集合的 Set 视图，用于内存判断。 */
  public static final Set<String> TERMINAL_SET = Set.copyOf(TERMINAL);

  /** 活跃（未终结）状态，供统计与筛选复用。 */
  public static final List<String> ACTIVE = List.of(PENDING, CONFIRMED, PROCESSING);

  /** 是否终态。null 视为非终态（由调用方决定如何处理空状态）。 */
  public static boolean isTerminal(String status) {
    return status != null && TERMINAL_SET.contains(status);
  }

  private WarningStatus() {}
}
