package cn.geosentry.ai.service;

import cn.geosentry.ai.entity.AiComprehensiveScore;
import cn.geosentry.ai.repository.AiComprehensiveScoreRepository;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AI 综合风险快照 —— 全项目统一的"风险单一可信源"。
 *
 * <p>设计目的：AI 综合分析是整个项目的后台引擎，灾害点 / 监测设备 / 预警 / 分析报告
 * 的风险判断都必须来自这里，而不是各自维护一套硬编码规则。
 *
 * <p>实现要点：
 * <ul>
 *   <li>表小（每个 hazard 一行），整体读进内存做快照，避免逐条查询的 N+1；</li>
 *   <li>带 TTL 缓存（默认 30s，远小于 5 分钟的落库周期），保证读多写少场景下不打 DB；</li>
 *   <li>AI 模块不可用时返回空快照，各业务层自动降级为原有规则，不会拖垮主流程。</li>
 * </ul>
 */
@Service
public class AiRiskEnricher {

  private static final Logger log = LoggerFactory.getLogger(AiRiskEnricher.class);

  /** AI 风险等级 → 中文标签。 */
  public static final Map<String, String> LEVEL_LABEL = Map.of(
      "CRITICAL", "极高风险",
      "HIGH", "高风险",
      "MEDIUM", "中风险",
      "LOW", "低风险"
  );

  /** AI 风险等级 → 主题色（与前端 .ai-tag-* 保持一致）。 */
  public static final Map<String, String> LEVEL_COLOR = Map.of(
      "CRITICAL", "#c84a4a",
      "HIGH", "#d18d3f",
      "MEDIUM", "#4a8cc2",
      "LOW", "#2d8280"
  );

  /** AI 风险等级 → 传统中文风险等级（用于替换 hazard_point.risk_level 的静态值）。 */
  public static final Map<String, String> LEVEL_TO_CLASSIC = Map.of(
      "CRITICAL", "高风险",
      "HIGH", "高风险",
      "MEDIUM", "关注",
      "LOW", "正常"
  );

  /** 快照缓存 TTL。 */
  private static final long CACHE_TTL_MS = 30_000L;

  private final AiComprehensiveScoreRepository repository;
  private final AtomicReference<Snapshot> cache = new AtomicReference<>(Snapshot.empty());

  public AiRiskEnricher(AiComprehensiveScoreRepository repository) {
    this.repository = repository;
  }

  /** 单个灾害点的 AI 风险摘要（v1.3.0 起包含灾害类型 + 中文关注度 + 设备可用性）。 */
  public record AiRiskBrief(
      String hazardCode,
      String riskLevel,
      Double riskScore,
      String levelLabel,
      String levelColor,
      String classicLevel,
      Double confidence,
      String model,
      OffsetDateTime analyzedAt,
      String disasterType,
      String disasterTypeSource,
      Double disasterTypeConfidence,
      String concernLabel,
      Boolean deviceAvailable
  ) {
    public boolean isElevated() {
      return "CRITICAL".equals(riskLevel) || "HIGH".equals(riskLevel);
    }
  }

  /**
   * 取某个灾害点的 AI 风险摘要。
   *
   * @return 无记录时返回 {@code null}（调用方据此降级）
   */
  public AiRiskBrief of(String hazardCode) {
    if (hazardCode == null || hazardCode.isBlank()) return null;
    return snapshot().get(hazardCode);
  }

  /** 全量快照（hazardCode → 摘要）。表很小，直接整体返回。 */
  public Map<String, AiRiskBrief> snapshot() {
    Snapshot current = cache.get();
    if (current != null && !current.isExpired()) {
      return current.byCode;
    }
    Snapshot fresh = Snapshot.load(repository);
    cache.set(fresh);
    return fresh.byCode;
  }

  /** 强制丢弃缓存（落库调度写完 DB 后调用，让下次读立即拿到新分）。 */
  public void invalidate() {
    cache.set(Snapshot.empty());
  }

  /**
   * 按 AI 综合分倒序排序（分高在前）；无 AI 分的排最后并维持原相对顺序。
   *
   * @param items     待排序列表
   * @param codeOf    从元素中取出 hazardCode 的函数
   * @return 新的已排序列表（不修改入参）
   */
  public <T> List<T> sortByRisk(List<T> items, Function<T, String> codeOf) {
    if (items == null || items.isEmpty()) return items;
    Map<String, AiRiskBrief> snap = snapshot();
    return items.stream()
        .sorted(Comparator.comparing(
            (T item) -> {
              AiRiskBrief b = snap.get(codeOf.apply(item));
              return b == null || b.riskScore() == null ? Double.NEGATIVE_INFINITY : b.riskScore();
            },
            Comparator.reverseOrder()
        ).thenComparing(item -> codeOf.apply(item) == null ? "" : codeOf.apply(item)))
        .toList();
  }

  /** 把 AI 等级转成中文标签（未知等级返回空串）。 */
  public static String labelOf(String riskLevel) {
    return riskLevel == null ? "" : LEVEL_LABEL.getOrDefault(riskLevel, riskLevel);
  }

  /** 把 AI 等级转成传统中文风险等级（高风险/关注/正常）。 */
  public static String classicLevelOf(String riskLevel) {
    return riskLevel == null ? null : LEVEL_TO_CLASSIC.get(riskLevel);
  }

  private record Snapshot(Map<String, AiRiskBrief> byCode, long loadedAt) {

    static Snapshot empty() {
      return new Snapshot(Map.of(), 0L);
    }

    static Snapshot load(AiComprehensiveScoreRepository repository) {
      Map<String, AiRiskBrief> map = new LinkedHashMap<>();
      try {
        for (AiComprehensiveScore s : repository.findAll()) {
          if (s.getHazardCode() == null) continue;
          map.put(s.getHazardCode(), new AiRiskBrief(
              s.getHazardCode(),
              s.getRiskLevel(),
              s.getRiskScore() == null ? null : s.getRiskScore().doubleValue(),
              labelOf(s.getRiskLevel()),
              LEVEL_COLOR.getOrDefault(s.getRiskLevel(), "#7b8f8c"),
              classicLevelOf(s.getRiskLevel()),
              s.getConfidence(),
              s.getModel(),
              s.getAnalyzedAt(),
              s.getDisasterType(),
              s.getDisasterTypeSource(),
              s.getDisasterTypeConfidence(),
              s.getConcernLabel(),
              s.getDeviceAvailable()
          ));
        }
      } catch (Exception ex) {
        // AI 表还没建 / 服务不可用：返回空快照，业务层降级
        log.debug("AI 风险快照加载失败，本次按无 AI 数据处理: {}", ex.getMessage());
        return new Snapshot(Map.of(), System.currentTimeMillis());
      }
      return new Snapshot(Map.copyOf(map), System.currentTimeMillis());
    }

    boolean isExpired() {
      // loadedAt == 0 表示 empty() 构造的初始占位，必须加载
      return loadedAt == 0L || System.currentTimeMillis() - loadedAt > CACHE_TTL_MS;
    }
  }
}
