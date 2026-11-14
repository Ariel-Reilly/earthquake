package cn.disasterguard.analytics;

import cn.disasterguard.common.api.ApiResponse;
import java.sql.Timestamp;
import java.time.Duration;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

  private final JdbcTemplate jdbcTemplate;

  public AnalyticsController(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

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
    // 数据可信标注：本接口当前没有接入真实巡检统计，指标由查询参数确定性生成。
    // 前端必须据此展示"演示数据"标识，避免把模拟值当成真实运营指标。
    response.put("synthetic", true);
    response.put("dataSource", "SIMULATED");
    response.put("note", "后端当前未接入真实巡检统计，以下指标为按查询参数生成的演示数据");
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
    Instant now = Instant.now();
    TimeWindow baseWindow = parseWindow("base", base, now, 24);
    TimeWindow cmpWindow;
    if ("previous".equalsIgnoreCase(compare)) {
      cmpWindow = baseWindow.previous();
    } else if ("lastYear".equalsIgnoreCase(compare)) {
      cmpWindow = baseWindow.shiftYears(-1);
    } else {
      cmpWindow = parseWindow("compare", compare, baseWindow.from, baseWindow.bucketHours());
    }

    WindowStats baseStats = loadWindow(baseWindow);
    WindowStats cmpStats = loadWindow(cmpWindow);

    List<Map<String, Object>> kpis = new ArrayList<>(6);
    kpis.add(kpi("incidentTotal", "事件总数", "起",
        baseStats.events, cmpStats.events, false));
    kpis.add(kpi("disposalRate", "处置率", "%",
        baseStats.disposalRate, cmpStats.disposalRate, true));
    kpis.add(kpi("avgHandleMinutes", "平均处置时长", "分钟",
        baseStats.avgHandleMinutes, cmpStats.avgHandleMinutes, false));
    kpis.add(kpi("onlineRate", "在线设备率", "%",
        baseStats.onlineRate, cmpStats.onlineRate, true));
    kpis.add(kpi("falseAlarmRate", "误报率", "%",
        baseStats.falseAlarmRate, cmpStats.falseAlarmRate, false));
    kpis.add(kpi("activeUsers", "用户活跃", "人",
        baseStats.activeUsers, cmpStats.activeUsers, true));

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("base", windowPayload(baseWindow, baseStats));
    response.put("compare", windowPayload(cmpWindow, cmpStats));
    response.put("series", Map.of(
        "base", baseStats.series,
        "compare", cmpStats.series
    ));
    response.put("spark", Map.of(
        "base", downsample(baseStats.series, 16),
        "compare", downsample(cmpStats.series, 16)
    ));
    response.put("kpis", kpis);
    response.put("categories", mergeCounts(baseStats.categories, cmpStats.categories));
    response.put("regions", mergeRegions(baseStats.regionCounts, cmpStats.regionCounts));
    response.put("mode", safeMode);
    response.put("synthetic", false);
    response.put("dataSource", "DATABASE");
    response.put("note", "基于 unified_event / warning_event / hazard_point / operation_log / monitoring_device 实时聚合");
    return ApiResponse.success(response);
  }

  /** 加载一个时间窗口的真实聚合指标。 */
  private WindowStats loadWindow(TimeWindow window) {
    WindowStats s = new WindowStats();
    Timestamp from = Timestamp.from(window.from);
    Timestamp to = Timestamp.from(window.to);
    s.events = count(
        "select count(*) from unified_event where created_at >= ? and created_at < ?", from, to);
    s.warnings = count(
        "select count(*) from warning_event where triggered_at >= ? and triggered_at < ?", from, to);
    s.hazards = count(
        "select count(*) from hazard_point where discovered_at >= ? and discovered_at < ?", from, to);
    s.devices = count("select count(*) from monitoring_device");
    s.onlineDevices = count(
        "select count(*) from monitoring_device where lower(online_status) = 'online'");
    s.activeUsers = count(
        "select count(distinct operator) from operation_log where created_at >= ? and created_at < ?",
        from, to);
    long closedEvents = count(
        "select count(*) from unified_event where state = 'CLOSED' "
            + "and created_at >= ? and created_at < ?", from, to);
    long autoResolved = count(
        "select count(*) from warning_event where coalesce(auto_resolved, false) "
            + "and triggered_at >= ? and triggered_at < ?", from, to);
    s.disposalRate = ratio(closedEvents, s.events);
    s.onlineRate = ratio(s.onlineDevices, s.devices);
    s.falseAlarmRate = ratio(autoResolved, s.warnings);
    Double avg = jdbcTemplate.queryForObject(
        "select avg(extract(epoch from (last_transition_at - created_at)) / 60.0) "
            + "from unified_event where state = 'CLOSED' "
            + "and last_transition_at >= ? and last_transition_at < ?",
        Double.class, from, to);
    s.avgHandleMinutes = avg == null ? 0 : round1(avg);
    s.series = buildSeries(window, "unified_event", "created_at");
    s.categories = countsBy(
        "select hazard_type as k, count(*) as cnt from unified_event "
            + "where created_at >= ? and created_at < ? and hazard_type is not null "
            + "group by hazard_type",
        from, to);
    s.regionCounts = countsBy(
        "select coalesce(region_code, '未分区') as k, count(*) as cnt from unified_event "
            + "where created_at >= ? and created_at < ? group by region_code",
        from, to);
    return s;
  }

  private long count(String sql, Object... args) {
    Long v = jdbcTemplate.queryForObject(sql, Long.class, args);
    return v == null ? 0 : v;
  }

  private Map<String, Integer> countsBy(String sql, Object... args) {
    Map<String, Integer> out = new LinkedHashMap<>();
    for (Map<String, Object> row : jdbcTemplate.queryForList(sql, args)) {
      out.put(String.valueOf(row.get("k")), ((Number) row.get("cnt")).intValue());
    }
    return out;
  }

  /** 按小时（≤48h）或按天（更长窗口）分桶的真实计数序列；最多 48 个桶。 */
  private List<Integer> buildSeries(TimeWindow window, String table, String timeColumn) {
    long spanSeconds = Math.max(1, Duration.between(window.from, window.to).getSeconds());
    long bucketSeconds = spanSeconds > 48 * 3600L ? 86400L : 3600L;
    long start = window.from.getEpochSecond() - Math.floorMod(window.from.getEpochSecond(), bucketSeconds);
    long end = window.to.getEpochSecond();
    int size = (int) Math.max(1, Math.min(48, (end - start) / bucketSeconds + 1));
    int[] counts = new int[size];
    for (Timestamp ts : jdbcTemplate.queryForList(
        "select " + timeColumn + " from " + table
            + " where " + timeColumn + " >= ? and " + timeColumn + " < ?",
        Timestamp.class, Timestamp.from(window.from), Timestamp.from(window.to))) {
      long idx = (ts.getTime() / 1000 - start) / bucketSeconds;
      if (idx >= 0 && idx < size) {
        counts[(int) idx]++;
      }
    }
    List<Integer> out = new ArrayList<>(size);
    for (int v : counts) {
      out.add(v);
    }
    return out;
  }

  private static List<Integer> downsample(List<Integer> values, int n) {
    List<Integer> out = new ArrayList<>(n);
    if (values.isEmpty()) {
      for (int i = 0; i < n; i++) {
        out.add(0);
      }
      return out;
    }
    for (int i = 0; i < n; i++) {
      int idx = (int) Math.round(i * (values.size() - 1.0) / (n - 1));
      out.add(values.get(idx));
    }
    return out;
  }

  private static Map<String, Object> windowPayload(TimeWindow window, WindowStats stats) {
    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("events", stats.events);
    totals.put("warnings", stats.warnings);
    totals.put("hazards", stats.hazards);
    totals.put("devices", stats.devices);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("key", window.label);
    payload.put("label", window.label);
    payload.put("range", Map.of("from", window.from, "to", window.to));
    payload.put("totals", totals);
    return payload;
  }

  private static List<Map<String, Object>> mergeCounts(
      Map<String, Integer> base, Map<String, Integer> compare) {
    java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>(base.keySet());
    keys.addAll(compare.keySet());
    List<Map<String, Object>> rows = new ArrayList<>(keys.size());
    for (String key : keys) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("key", key);
      row.put("label", key);
      row.put("base", base.getOrDefault(key, 0));
      row.put("compare", compare.getOrDefault(key, 0));
      rows.add(row);
    }
    return rows;
  }

  private List<Map<String, Object>> mergeRegions(
      Map<String, Integer> base, Map<String, Integer> compare) {
    Map<String, String> names = new LinkedHashMap<>();
    for (Map<String, Object> row : jdbcTemplate.queryForList(
        "select region_code, region_name from region")) {
      names.put(String.valueOf(row.get("region_code")), String.valueOf(row.get("region_name")));
    }
    java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>(base.keySet());
    keys.addAll(compare.keySet());
    List<Map<String, Object>> rows = new ArrayList<>(keys.size());
    for (String key : keys) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", names.getOrDefault(key, key));
      row.put("base", base.getOrDefault(key, 0));
      row.put("compare", compare.getOrDefault(key, 0));
      rows.add(row);
    }
    return rows;
  }

  private static Map<String, Object> kpi(
      String key, String label, String unit, double base, double compare, boolean higherIsBetter) {
    double delta = round1(compare - base);
    double deltaPercent = base == 0 ? 0 : round1(delta * 100 / base);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("key", key);
    m.put("label", label);
    m.put("unit", unit);
    m.put("base", round1(base));
    m.put("compare", round1(compare));
    m.put("delta", delta);
    m.put("deltaPercent", deltaPercent);
    m.put("higherIsBetter", higherIsBetter);
    return m;
  }

  private static double ratio(long numerator, long denominator) {
    return denominator == 0 ? 0 : round1(numerator * 100.0 / denominator);
  }

  private static final class WindowStats {
    long events;
    long warnings;
    long hazards;
    long devices;
    long onlineDevices;
    long activeUsers;
    double disposalRate;
    double onlineRate;
    double falseAlarmRate;
    double avgHandleMinutes;
    List<Integer> series = List.of();
    Map<String, Integer> categories = Map.of();
    Map<String, Integer> regionCounts = Map.of();
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

    /** 同比：整体平移 N 年（lastYear 对比）。 */
    TimeWindow shiftYears(int years) {
      Instant f = from.atZone(ZoneOffset.UTC).plusYears(years).toInstant();
      Instant t = to.atZone(ZoneOffset.UTC).plusYears(years).toInstant();
      return new TimeWindow(years == -1 ? "lastYear" : label + years, f, t);
    }
  }

  // 抑制 OffsetDateTime/ZoneOffset 未使用警告（保留以备后续扩展）
  @SuppressWarnings("unused")
  private static OffsetDateTime nowUtc() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
