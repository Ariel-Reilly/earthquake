package cn.disasterguard.common.dict;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 灾种代码唯一口径（{@code unified_event.hazard_type} / {@code sys_dict.disaster_type}）。
 *
 * <p>背景（R11 E4）：AI 侧 {@code SUPPORTED_DISASTER_TYPES} 返回的是**中文**六类
 * （滑坡 / 崩塌 / 泥石流 / 地裂缝 / 地面沉降 / 塌陷，另有「其他」「待定」两个兜底），
 * 而库表约定、字典与前端 {@code utils/hazardTypes.js} 全是**英文码** 11 类。
 * {@code HazardService#createFromAiDetection} 此前把 AI 返回值原样写库，于是 AI 新建的
 * 隐患点带上中文类型 —— 前端类型筛选、标签、统计全部失配，「其他」还会被当成一个真实灾种。
 *
 * <p>本类提供「中文标签 → 英文码」的唯一映射，供写入前归一化使用。三处必须一致：
 * <ul>
 *   <li>后端本类（写库边界）</li>
 *   <li>{@code data.sql} 的 {@code sys_dict.disaster_type}</li>
 *   <li>前端 {@code utils/hazardTypes.js}</li>
 * </ul>
 *
 * <p>「塌陷」映射到 {@code subsidence}（地面沉降），与 {@code WarningService} 既有的
 * 「地面塌陷 → subsidence」先例一致：塌陷是地面沉降类灾害的表现形式，单列第 12 类
 * （sinkhole）会与地面沉降语义重叠，需同步字典 / 前端 / 报表 / 权限，收益不成比例。
 * 「其他」「待定」「未知」等无法判定项返回 {@code null} —— 与其伪造一个类型，不如留空。
 */
public final class HazardTypeCodes {

  private static final Logger log = LoggerFactory.getLogger(HazardTypeCodes.class);

  /** 英文码 → 中文展示文案，顺序与 data.sql / 前端字典一致。 */
  private static final Map<String, String> CODE_TO_LABEL = new LinkedHashMap<>();

  /** 中文标签（含历史混用写法）→ 英文码。 */
  private static final Map<String, String> LABEL_TO_CODE = new LinkedHashMap<>();

  static {
    register("landslide", "滑坡");
    register("collapse", "崩塌");
    register("debris_flow", "泥石流");
    register("subsidence", "地面沉降");
    register("ground_fissure", "地裂缝");
    register("earthquake", "地震");
    register("flood", "洪水");
    register("fire", "火灾");
    register("typhoon", "台风");
    register("drought", "干旱");
    register("waterlogging", "内涝");

    // 历史混用写法 / AI 侧的额外词表：统一归到已存在的码上。
    LABEL_TO_CODE.put("地面塌陷", "subsidence");
    LABEL_TO_CODE.put("塌陷", "subsidence");
    LABEL_TO_CODE.put("地面裂缝", "ground_fissure");
    LABEL_TO_CODE.put("地裂", "ground_fissure");
    LABEL_TO_CODE.put("城市内涝", "waterlogging");
    LABEL_TO_CODE.put("山洪", "flood");
    LABEL_TO_CODE.put("泥石流灾害", "debris_flow");
  }

  /** 无法判定灾种时 AI 会返回的兜底词，归一化后一律视为「无类型」。 */
  private static final Set<String> UNDETERMINED = Set.of("其他", "待定", "未知", "无", "未识别", "unknown", "other");

  private static void register(String code, String label) {
    CODE_TO_LABEL.put(code, label);
    LABEL_TO_CODE.put(label, code);
  }

  private HazardTypeCodes() {}

  /** 字典内的全部英文码（11 类）。 */
  public static Set<String> codes() {
    return Set.copyOf(CODE_TO_LABEL.keySet());
  }

  /** 字典全部条目（英文码 → 中文），按 display 顺序。 */
  public static Map<String, String> codeToLabel() {
    return Map.copyOf(CODE_TO_LABEL);
  }

  /** 是否为字典内的英文码。 */
  public static boolean isCode(String value) {
    return value != null && CODE_TO_LABEL.containsKey(value.trim());
  }

  /** 英文码 → 中文标签；未知码原样返回（不静默吞掉）。 */
  public static String labelOf(String code) {
    if (code == null) {
      return null;
    }
    String trimmed = code.trim();
    return CODE_TO_LABEL.getOrDefault(trimmed, trimmed);
  }

  /**
   * 归一化为字典英文码。
   *
   * @param raw 中文标签、英文码或 AI 兜底词
   * @return 字典内的英文码；无法判定（含「其他」「待定」与空值）返回 {@code null}
   */
  public static String toCode(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty() || UNDETERMINED.contains(trimmed.toLowerCase())) {
      return null;
    }
    String byLabel = LABEL_TO_CODE.get(trimmed);
    if (byLabel != null) {
      return byLabel;
    }
    if (CODE_TO_LABEL.containsKey(trimmed)) {
      return trimmed;
    }
    log.warn("未知灾种标签：{}，按「无法判定」处理", trimmed);
    return null;
  }

  /**
   * 归一化，无法判定时保留原文。
   *
   * <p>用于 {@code warning_event.hazard_type}（该列可空，但历史逻辑是「原样入库 + 告警」，
   * 便于回查脏数据来源）；{@code hazard_point} 请用 {@link #toCode(String)} 的严格语义。
   */
  public static String toCodeOrOriginal(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    String code = toCode(trimmed);
    return code != null ? code : trimmed;
  }

  /** 供批量迁移脚本比对用：非字典码但已知的中文写法。 */
  public static List<String> legacyLabels() {
    return List.copyOf(LABEL_TO_CODE.keySet());
  }
}
