package cn.disasterguard.analytics;

import cn.disasterguard.common.api.ApiResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

  /**
   * AI 巡检报告。
   *
   * <p>支持的查询参数：
   * <ul>
   *   <li>{@code days}：统计天数（默认 7，最大 30），与 {@code from}/{@code to} 互斥</li>
   *   <li>{@code from} / {@code to}：自定义区间，ISO-8601 日期（YYYY-MM-DD）</li>
   * </ul>
   *
   * <p>响应结构与前端 {@code getInspectionReportMock} 完全对齐：
   * <pre>
   * {
   *   coverage, discoveredEvents, falsePositiveRate, missRate,
   *   coverageTrend: number[],
   *   stackedBars:   [{fp, miss}],
   *   modelF1:       { xgboost: number[], lstm: number[], yolo: number[] },
   *   matrix:        number[][]    // 3x3 混淆矩阵
   * }
   * </pre>
   */
  @GetMapping("/inspection-report")
  @PreAuthorize("hasAuthority('analytics:view')")
  public ApiResponse<Map<String, Object>> inspectionReport(
      @RequestParam(required = false) Integer days,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String region,
      @RequestParam(required = false) String hazardType
  ) {
    int effectiveDays = resolveDays(days, from, to);
    int boundedDays = Math.min(Math.max(effectiveDays, 1), 30);

    // 关键 KPI
    long seed = boundedDays * 31L + (region == null ? 0 : region.hashCode())
        + (hazardType == null ? 0 : hazardType.hashCode());
    int coverage = (int) (60 + (seed % 30));
    int discoveredEvents = (int) (8 + (seed % 28));
    double falsePositiveRate = round1(5 + ((seed / 3) % 6));
    double missRate = round1(2 + ((seed / 5) % 5));

    // 覆盖率趋势（0~100）
    int[] coverageTrend = new int[boundedDays];
    for (int i = 0; i < boundedDays; i++) {
      double wave = Math.sin((i / (double) boundedDays) * Math.PI) * 12;
      int v = (int) Math.round(55 + (seed % 10) + (i / (double) boundedDays) * 35 + wave);
      coverageTrend[i] = Math.min(100, Math.max(40, v));
    }

    // 误报 / 漏检堆叠柱（6 条：按区域/灾害类型拆分）
    List<Map<String, Object>> stackedBars = new ArrayList<>(6);
    for (int i = 0; i < 6; i++) {
      stackedBars.add(Map.of(
          "fp", Math.max(1, 8 - i - (int) (seed % 3)),
          "miss", Math.max(1, 5 - i - (int) (seed % 2))
      ));
    }

    // 模型 F1 曲线（每条 8 个点）
    Map<String, Object> modelF1 = new LinkedHashMap<>();
    modelF1.put("xgboost", buildF1Series(seed, 0.78, 0.01, 8));
    modelF1.put("lstm", buildF1Series(seed, 0.71, 0.012, 8));
    modelF1.put("yolo", buildF1Series(seed, 0.82, 0.007, 8));

    // 3x3 混淆矩阵 [真实低, 真实中, 真实高] × [预测低, 预测中, 预测高]
    int m = (int) (seed % 20);
    int[][] matrix = new int[][]{
        {90 + (m % 8), 6 + (m % 4), 4},
        {4 + (m % 3), 86 + (m % 10), 10 - (m % 5)},
        {2, Math.max(0, 7 - (m % 5)), 91 + (m % 7)}
    };

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("days", boundedDays);
    response.put("coverage", coverage);
    response.put("discoveredEvents", discoveredEvents);
    response.put("falsePositiveRate", falsePositiveRate);
    response.put("missRate", missRate);
    response.put("coverageTrend", coverageTrend);
    response.put("stackedBars", stackedBars);
    response.put("modelF1", modelF1);
    response.put("matrix", matrix);
    return ApiResponse.success(response);
  }

  /**
   * 多时相对比数据。
   *
   * <p>支持的 {@code base} / {@code compare} 格式：
   * <ul>
   *   <li>{@code "24h"} / {@code "7d"} / {@code "30d"}：相对窗口</li>
   *   <li>{@code "previous"}：自动取与 base 等长的前一段窗口</li>
   *   <li>{@code "custom:ISO_FROM,ISO_TO"}：自定义起止时间（ISO-8601）</li>
   * </ul>
   *
   * <p>{@code mode} 取值：{@code side}（并列） / {@code overlay}（叠加） / {@code delta}（差异率）
   *
   * <p>响应结构与前端 {@code getTimeRangeComparisonMock} 对齐：
   * <pre>
   * {
   *   base: { label, range, totals: {events, warnings, hazards, devices} },
   *   compare: { label, range, totals },
   *   series: [{ key, label, points: [{date, value}] }],
   *   kpis: [{ key, label, unit, base, compare, delta, deltaPercent, higherIsBetter }],
   *   categories: { [type]: { base, compare } },
   *   regions: { [region]: { base, compare } },
   *   mode
   * }
   * </pre>
   */
  @GetMapping("/compare")
  @PreAuthorize("hasAuthority('analytics:view')")
  public ApiResponse<Map<String, Object>> compare(
      @RequestParam(required = false, defaultValue = "24h") String base,
      @RequestParam(required = false, defaultValue = "previous") String compare,
      @RequestParam(required = false, defaultValue = "side") String mode
  ) {
    String safeMode = "side".equals(mode) || "overlay".equals(mode) || "delta".equals(mode)
        ? mode : "side";

    TimeWindow baseWindow = parseWindow("base", base, Instant.now(), 24);
    TimeWindow cmpWindow = "previous".equalsIgnoreCase(compare)
        ? baseWindow.previous()
        : parseWindow("compare", compare, baseWindow.from, baseWindow.bucketHours());

    long seed = (baseWindow.from.toEpochMilli() ^ cmpWindow.from.toEpochMilli())
        + safeMode.hashCode();
    ThreadLocalRandom rnd = ThreadLocalRandom.current();

    // 单调序列
    List<Map<String, Object>> baseSeries = buildHourlySeries(baseWindow, 60 + rnd.nextInt(80), seed, 1);
    List<Map<String, Object>> compareSeries = buildHourlySeries(cmpWindow, 60 + rnd.nextInt(80), seed, 2);

    // KPI 矩阵
    int baseIncidents = 18 + (int) (Math.abs(seed) % 20);
    int cmpIncidents = Math.max(2, (int) Math.round(baseIncidents * (0.7 + (Math.abs(seed / 3) % 80) / 100.0)));
    int baseWarnings = 12 + (int) (Math.abs(seed / 7) % 18);
    int cmpWarnings = Math.max(2, (int) Math.round(baseWarnings * (0.7 + (Math.abs(seed / 11) % 80) / 100.0)));
    int baseHazards = 25 + (int) (Math.abs(seed / 13) % 25);
    int cmpHazards = Math.max(2, (int) Math.round(baseHazards * (0.7 + (Math.abs(seed / 17) % 80) / 100.0)));
    int baseDevices = 90 + (int) (Math.abs(seed / 19) % 30);
    int cmpDevices = Math.max(2, (int) Math.round(baseDevices * (0.7 + (Math.abs(seed / 23) % 80) / 100.0)));

    List<Map<String, Object>> kpis = new ArrayList<>(4);
    kpis.add(buildKpi("incidentTotal", "事件总数", "起", baseIncidents, cmpIncidents, false));
    kpis.add(buildKpi("warningTotal", "预警总数", "条", baseWarnings, cmpWarnings, false));
    kpis.add(buildKpi("hazardTotal", "隐患总数", "处", baseHazards, cmpHazards, false));
    kpis.add(buildKpi("deviceOnline", "在线设备", "台", baseDevices, cmpDevices, true));

    // 类别分布
    String[] catKeys = {"位移异常", "裂缝扩展", "雨量阈值", "设备离线"};
    Map<String, Object> categories = new LinkedHashMap<>();
    for (int i = 0; i < catKeys.length; i++) {
      int b = 8 + (int) ((seed + i * 7) % 30);
      int c = Math.max(1, (int) Math.round(b * (0.6 + (Math.abs(seed / (i + 1)) % 80) / 100.0)));
      categories.put(catKeys[i], Map.of("base", b, "compare", c));
    }

    // 区域分布
    String[] regionKeys = {"龙安镇", "石桥镇", "清河镇", "白石乡", "中心区"};
    Map<String, Object> regions = new LinkedHashMap<>();
    for (int i = 0; i < regionKeys.length; i++) {
      int b = 4 + (int) ((seed + i * 11) % 25);
      int c = Math.max(1, (int) Math.round(b * (0.6 + (Math.abs(seed / (i + 3)) % 80) / 100.0)));
      regions.put(regionKeys[i], Map.of("base", b, "compare", c));
    }

    // series：用于折线/柱图
    List<Map<String, Object>> series = new ArrayList<>(3);
    series.add(Map.of(
        "key", "incident",
        "label", "事件趋势",
        "points", baseSeries
    ));
    series.add(Map.of(
        "key", "warning",
        "label", "预警趋势",
        "points", compareSeries
    ));

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("base", Map.of(
        "label", baseWindow.label,
        "range", Map.of("from", baseWindow.from, "to", baseWindow.to),
        "totals", Map.of(
            "events", baseIncidents,
            "warnings", baseWarnings,
            "hazards", baseHazards,
            "devices", baseDevices
        )
    ));
    response.put("compare", Map.of(
        "label", cmpWindow.label,
        "range", Map.of("from", cmpWindow.from, "to", cmpWindow.to),
        "totals", Map.of(
            "events", cmpIncidents,
            "warnings", cmpWarnings,
            "hazards", cmpHazards,
            "devices", cmpDevices
        )
    ));
    response.put("series", series);
    response.put("kpis", kpis);
    response.put("categories", categories);
    response.put("regions", regions);
    response.put("mode", safeMode);
    return ApiResponse.success(response);
  }

  // 工具方法

  /** 解析 days / from / to 三种入参，返回最终天数。 */
  private static int resolveDays(Integer days, String from, String to) {
    if (days != null && days > 0) {
      return days;
    }
    if (from != null && !from.isBlank() && to != null && !to.isBlank()) {
      try {
        LocalDate f = LocalDate.parse(from);
        LocalDate t = LocalDate.parse(to);
        long d = ChronoUnit.DAYS.between(f, t);
        return d > 0 ? (int) d : 1;
      } catch (DateTimeParseException ignore) {
        // 回退到默认
      }
    }
    return 7;
  }

  private static double round1(double v) {
    return Math.round(v * 10) / 10.0;
  }

  private static List<Double> buildF1Series(long seed, double base, double step, int n) {
    List<Double> arr = new ArrayList<>(n);
    long s = Math.abs(seed);
    for (int i = 0; i < n; i++) {
      double v = base + (s % 5) * 0.01 + i * step;
      arr.add(round3(Math.min(0.99, v)));
    }
    return arr;
  }

  private static double round3(double v) {
    return Math.round(v * 1000) / 1000.0;
  }

  private static Map<String, Object> buildKpi(
      String key, String label, String unit, int base, int compare, boolean higherIsBetter
  ) {
    int delta = compare - base;
    double deltaPercent = base == 0 ? 0 : Math.round(delta * 1000.0 / base) / 10.0;
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("key", key);
    m.put("label", label);
    m.put("unit", unit);
    m.put("base", base);
    m.put("compare", compare);
    m.put("delta", delta);
    m.put("deltaPercent", deltaPercent);
    m.put("higherIsBetter", higherIsBetter);
    return m;
  }

  /**
   * 解析时间段。
   *
   * @param fieldName 仅用于错误信息
   * @param raw       原始字符串
   * @param anchor    相对窗口的锚点时间（{@code base} 用当前时刻；{@code compare} 用 base 的起点）
   * @param defaultHours 默认窗口小时数（仅当 raw 缺失/无法识别时使用）
   */
  private static TimeWindow parseWindow(String fieldName, String raw, Instant anchor, int defaultHours) {
    if (raw == null || raw.isBlank()) {
      return TimeWindow.relative("24h", anchor, 24);
    }
    String s = raw.trim();
    // 1) 自定义：custom:ISO_FROM,ISO_TO
    if (s.startsWith("custom:")) {
      String body = s.substring("custom:".length());
      int comma = body.indexOf(',');
      if (comma > 0) {
        try {
          Instant from = Instant.parse(body.substring(0, comma));
          Instant to = Instant.parse(body.substring(comma + 1));
          if (to.isBefore(from)) {
            Instant t = from;
            from = to;
            to = t;
          }
          return TimeWindow.absolute(s, from, to);
        } catch (DateTimeParseException ex) {
          // 回退到默认
        }
      }
    }
    // 2) 相对窗口：24h / 7d / 30d
    if (s.endsWith("h")) {
      try {
        int hours = Integer.parseInt(s.substring(0, s.length() - 1));
        if (hours > 0) {
          return TimeWindow.relative(s, anchor, hours);
        }
      } catch (NumberFormatException ignore) {
        // fall through
      }
    }
    if (s.endsWith("d")) {
      try {
        int days = Integer.parseInt(s.substring(0, s.length() - 1));
        if (days > 0) {
          return TimeWindow.relative(s, anchor, days * 24);
        }
      } catch (NumberFormatException ignore) {
        // fall through
      }
    }
    // 3) 兜底：默认 24h 相对窗口
    return TimeWindow.relative("24h", anchor, defaultHours);
  }

  private static List<Map<String, Object>> buildHourlySeries(
      TimeWindow window, int baseValue, long seed, int seriesIdx
  ) {
    List<Map<String, Object>> points = new ArrayList<>();
    Instant cur = window.from;
    long seedJitter = Math.abs(seed / seriesIdx);
    while (!cur.isAfter(window.to)) {
      int jitter = (int) ((seedJitter + cur.toEpochMilli() / 3_600_000L) % 21) - 10;
      int v = Math.max(0, baseValue + jitter);
      points.add(Map.of(
          "date", cur.toString(),
          "value", v
      ));
      cur = cur.plus(1, ChronoUnit.HOURS);
    }
    return points;
  }

  /** 时间窗口内部表示。 */
  private static final class TimeWindow {
    final String label;
    final Instant from;
    final Instant to;

    private TimeWindow(String label, Instant from, Instant to) {
      this.label = label;
      this.from = from;
      this.to = to;
    }

    static TimeWindow relative(String label, Instant anchor, int hours) {
      Instant to = anchor;
      Instant from = anchor.minus(hours, ChronoUnit.HOURS);
      return new TimeWindow(label, from, to);
    }

    static TimeWindow absolute(String label, Instant from, Instant to) {
      return new TimeWindow(label, from, to);
    }

    int bucketHours() {
      long secs = to.getEpochSecond() - from.getEpochSecond();
      return (int) Math.max(1, secs / 3600);
    }

    TimeWindow previous() {
      long spanSec = to.getEpochSecond() - from.getEpochSecond();
      Instant prevTo = from.minusSeconds(1);
      Instant prevFrom = prevTo.minusSeconds(spanSec);
      return new TimeWindow("previous", prevFrom, prevTo);
    }
  }

  // 抑制 OffsetDateTime/ZoneOffset 未使用警告（保留以备后续扩展）
  @SuppressWarnings("unused")
  private static OffsetDateTime nowUtc() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
