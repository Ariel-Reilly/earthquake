package cn.disasterguard.weather;

/**
 * 国家气象数据产品类型。
 *
 * <p>数据源分两类：
 * <ul>
 *   <li>{@link #NSMC}：国家卫星气象中心风云四号卫星云图（XML 清单模式，需解析最新帧 URL）</li>
 *   <li>{@link #NMC}：中央气象台预渲染产品（按 6 分钟档探测 URL）</li>
 * </ul>
 *
 * <p>前端 <code>weatherService.js</code> 与后端枚举必须保持 kind 字段一致：
 * satellite / satelliteDisk / radar / precip24h / snow24h / drought。
 */
public enum WeatherProductKind {

  /** 风云四号 A 中国区域云图。 */
  SATELLITE("satellite", "卫星云图", "风云四号 A 中国区域 真彩合成",
      SourceKind.NSMC, "FY4A/FY4A_AGRI_IMG_REGI_MTCC_GLL.xml", 15),

  /** 风云四号 A 全圆盘云图。 */
  SATELLITE_DISK("satelliteDisk", "全圆盘云图", "风云四号 A 全圆盘",
      SourceKind.NSMC, "FY4A/FY4A_AGRI_IMG_DISK_MTCC_NOM.xml", 15),

  /** 全国雷达基本反射率拼图。 */
  RADAR("radar", "雷达回波", "中央气象台全国基本反射率拼图",
      SourceKind.NMC, "RDP", 6),

  /** 24 小时累计降水量实况。 */
  PRECIP_24H("precip24h", "24h 降水", "中央气象台 24 小时累计降水",
      SourceKind.NMC, "RAIN24", 60),

  /** 24 小时积雪深度。 */
  SNOW_24H("snow24h", "24h 积雪", "中央气象台 24 小时积雪深度",
      SourceKind.NMC, "SDP", 1440),

  /** 农业干旱综合监测。 */
  DROUGHT("drought", "干旱监测", "农业干旱综合监测",
      SourceKind.NMC, "AGRI_DROUGHT", 1440);

  private final String kind;
  private final String label;
  private final String description;
  private final SourceKind sourceKind;
  private final String resource;
  /** 官方刷新间隔（分钟），用于 UI 提示"最新一帧应不晚于此时"。 */
  private final int refreshIntervalMinutes;

  WeatherProductKind(String kind, String label, String description,
      SourceKind sourceKind, String resource, int refreshIntervalMinutes) {
    this.kind = kind;
    this.label = label;
    this.description = description;
    this.sourceKind = sourceKind;
    this.resource = resource;
    this.refreshIntervalMinutes = refreshIntervalMinutes;
  }

  public String kind() { return kind; }
  public String label() { return label; }
  public String description() { return description; }
  public SourceKind sourceKind() { return sourceKind; }
  public String resource() { return resource; }
  public int refreshIntervalMinutes() { return refreshIntervalMinutes; }

  /** 等经纬度外接矩形：[minLon, minLat, maxLon, maxLat]，对应前端 OL ImageLayer 的 imageExtent。 */
  public double[] extent() {
    return switch (this) {
      // 风云四号 A 中国区域（约 0-60°N, 70-140°E）
      case SATELLITE -> new double[]{70.0, 0.0, 140.0, 60.0};
      // 风云四号 A 全圆盘（约 60°S-60°N, 60°E-140°E）
      case SATELLITE_DISK -> new double[]{60.0, -60.0, 220.0, 60.0};
      // NMC 静态产品（中国大陆及周边，行政区划）
      case RADAR, PRECIP_24H, SNOW_24H, DROUGHT -> new double[]{73.0, 16.0, 135.0, 54.0};
    };
  }

  /** 把字符串转换为枚举（大小写不敏感）；找不到时返回 null。 */
  public static WeatherProductKind fromKind(String kind) {
    if (kind == null) return null;
    String normalized = kind.trim();
    for (WeatherProductKind k : values()) {
      if (k.kind.equalsIgnoreCase(normalized)) return k;
    }
    return null;
  }

  /** 数据来源类型。 */
  public enum SourceKind {
    /** 国家卫星气象中心风云四号 XML 清单。 */
    NSMC,
    /** 中央气象台 image.nmc.cn 预渲染产品。 */
    NMC
  }
}