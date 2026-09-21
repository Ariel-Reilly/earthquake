package cn.disasterguard.hazard.service;

import cn.disasterguard.ai.service.AiRiskEnricher;
import cn.disasterguard.common.api.PageResponse;
import cn.disasterguard.common.exception.AppException;
import cn.disasterguard.common.geo.ViewportBounds;
import cn.disasterguard.common.export.HazardExportRow;
import cn.disasterguard.event.entity.UnifiedEventEntity;
import cn.disasterguard.event.service.EventStateService;
import cn.disasterguard.hazard.dto.HazardDto;
import cn.disasterguard.hazard.dto.HazardUpsertRequest;
import cn.disasterguard.hazard.entity.HazardPoint;
import cn.disasterguard.hazard.repository.HazardPointRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HazardService {

  private static final Logger log = LoggerFactory.getLogger(HazardService.class);

  private final HazardPointRepository repository;
  private final AiRiskEnricher aiEnricher;
  private final EventStateService eventStateService;

  public HazardService(
      HazardPointRepository repository,
      AiRiskEnricher aiEnricher,
      EventStateService eventStateService
  ) {
    this.repository = repository;
    this.aiEnricher = aiEnricher;
    this.eventStateService = eventStateService;
  }

  public PageResponse<HazardDto> page(int page, int size, String keyword, String regionCode, String level, String type, String bbox, String source) {
    int pageSize = Math.min(Math.max(size, 1), 200);
    int pageNo = Math.max(page, 1);
    ViewportBounds bounds = ViewportBounds.parse(bbox);
    List<HazardDto> records = loadMatched(keyword, regionCode, level, type, bounds, source).stream()
        .map(this::toDto)
        // AI 综合分驱动排序：高风险（CRITICAL/HIGH）灾害点排最前；无 AI 分的按原 ID 顺序
        .sorted(Comparator.comparing(
            (HazardDto dto) -> dto.aiScore() == null ? Double.NEGATIVE_INFINITY : dto.aiScore(),
            Comparator.reverseOrder()
        ).thenComparing(
            // AI 监测灾害优先（discovered_at 倒序），其次按 ID
            Comparator.comparing(HazardDto::discoveredAt,
                Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(HazardDto::id, Comparator.nullsLast(Long::compareTo))
        ))
        .toList();
    long total = records.size();
    int pages = (int) Math.max(1, Math.ceil(total / (double) pageSize));
    int fromIndex = Math.min((pageNo - 1) * pageSize, records.size());
    int toIndex = Math.min(fromIndex + pageSize, records.size());
    return new PageResponse<>(records.subList(fromIndex, toIndex), total, pageNo, pageSize, pages);
  }

  public List<HazardDto> listAll(String regionCode) {
    List<HazardPoint> hazards = (regionCode == null || regionCode.isBlank()) ? repository.findAll() : repository.findByRegionCode(regionCode);
    return toDtoList(hazards);
  }

  /** 导出用：返回灾害点导出行。 */
  public List<HazardExportRow> exportRows() {
    return repository.findAll().stream()
        .sorted(Comparator.comparing(HazardPoint::getId))
        .map(p -> new HazardExportRow(p.getHazardCode(), p.getName(), p.getHazardType(), p.getRiskLevel(),
            p.getRegionCode(), p.getOwner(), p.getStatus(), p.getLongitude(), p.getLatitude(), p.getThreatObject(), p.getLastInspectionAt()))
        .toList();
  }

  public List<HazardDto> listByViewport(String bbox, String keyword, String regionCode, String level, String type, String source) {
    ViewportBounds bounds = ViewportBounds.parse(bbox);
    return toDtoList(loadMatched(keyword, regionCode, level, type, bounds, source));
  }

  /**
   * 批量把 HazardPoint 列表映射为 HazardDto，解决 N+1：
   * 一次性按 hazardCode 集合预取 unified_event，避免每个 row 一次 DB 查询。
   */
  private List<HazardDto> toDtoList(List<HazardPoint> points) {
    if (points.isEmpty()) return List.of();
    java.util.Set<String> codes = points.stream()
        .map(HazardPoint::getHazardCode)
        .filter(java.util.Objects::nonNull)
        .collect(java.util.stream.Collectors.toSet());
    java.util.Map<String, cn.disasterguard.event.entity.UnifiedEventEntity> eventByCode =
        eventStateService.findByHazardCodeIn(codes);
    return points.stream()
        .map(p -> {
          cn.disasterguard.event.entity.UnifiedEventEntity e =
              eventByCode.get(p.getHazardCode());
          String preCode = e == null ? null : e.getEventCode();
          String preState = e == null || e.getState() == null ? null : e.getState().name();
          return toDto(p, preCode, preState);
        })
        .sorted(Comparator.comparing(
            (HazardDto dto) -> dto.aiScore() == null ? Double.NEGATIVE_INFINITY : dto.aiScore(),
            Comparator.reverseOrder()
        ).thenComparing(
            Comparator.comparing(HazardDto::discoveredAt,
                Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(HazardDto::id, Comparator.nullsLast(Long::compareTo))
        ))
        .toList();
  }

  /** bbox 存在时走 PostGIS ST_MakeEnvelope + GiST 索引，否则全表扫描后内存过滤 */
  private List<HazardPoint> loadMatched(String keyword, String regionCode, String level, String type, ViewportBounds bounds, String source) {
    // source 过滤走 repository 加速（避免全表扫），其它过滤条件在内存里跑
    List<HazardPoint> base;
    if (source != null && !source.isBlank()) {
      base = repository.findBySourceOrderByDiscoveredAtDesc(source);
    } else {
      base = (bounds == null)
          ? repository.findAll()
          : repository.findInViewport(bounds.minLon(), bounds.minLat(), bounds.maxLon(), bounds.maxLat());
    }
    return base.stream()
        .filter(point -> matches(point, keyword, regionCode, level, type, bounds))
        .toList();
  }

  public HazardDto getById(Long id) {
    HazardPoint point = repository.findById(id).orElseThrow(() -> new AppException(404, "未找到灾害点"));
    return toDto(point);
  }

  public Map<String, Object> geojson(String bbox, String keyword, String regionCode, String level, String type, String source) {
    ViewportBounds bounds = ViewportBounds.parse(bbox);
    List<Map<String, Object>> features = loadMatched(keyword, regionCode, level, type, bounds, source).stream()
        .map(this::toDto)
        .sorted(Comparator.comparing(
            (HazardDto dto) -> dto.aiScore() == null ? Double.NEGATIVE_INFINITY : dto.aiScore(),
            Comparator.reverseOrder()
        ).thenComparing(HazardDto::id, Comparator.nullsLast(Long::compareTo)))
        .map(dto -> {
          Map<String, Object> feature = new LinkedHashMap<>();
          feature.put("type", "Feature");
          feature.put("geometry", Map.of("type", "Point", "coordinates", List.of(dto.lon(), dto.lat())));

          Map<String, Object> properties = new LinkedHashMap<>();
          properties.put("id", dto.id());
          properties.put("hazardCode", dto.hazardCode());
          properties.put("name", dto.name());
          properties.put("type", dto.type());
          properties.put("level", dto.level());
          properties.put("regionCode", dto.regionCode());
          properties.put("owner", dto.owner());
          properties.put("status", dto.status());
          properties.put("lon", dto.lon());
          properties.put("lat", dto.lat());
          properties.put("distance", null);
          properties.put("source", dto.source());
          properties.put("discoveredAt", dto.discoveredAt());
          properties.put("detectionConfidence", dto.detectionConfidence());
          // AI 综合分（地图前端按它决定渲染样式/优先级）
          properties.put("aiLevel", dto.aiLevel());
          properties.put("aiLevelLabel", dto.aiLevelLabel());
          properties.put("aiScore", dto.aiScore());

          feature.put("properties", properties);
          return feature;
        })
        .toList();
    return Map.of("type", "FeatureCollection", "features", features);
  }

  @Transactional
  public HazardDto create(HazardUpsertRequest request) {
    HazardPoint point = new HazardPoint();
    point.setHazardCode(generateCode());
    apply(point, request);
    point.setLastInspectionAt(OffsetDateTime.now().minusHours(4));
    point.setSource("MANUAL");  // 手动新增
    HazardPoint saved = repository.save(point);
    String eventCode = eventStateService.createFromManualReport(
        saved.getHazardCode(), saved.getName(), "system");
    log.info("Hazard created with unified_event: hazard={}, eventCode={}", saved.getHazardCode(), eventCode);
    return toDto(saved, eventCode, null);
  }

  /**
   * v1.6.0：AI 监测到新灾害时由 AiHazardMonitorScheduler 调用。
   *
   * <p>设计要点：
   * <ul>
   *   <li>hazard_code 自动生成（与 generateCode 一致：HZ- + 8 位 UUID）；</li>
   *   <li>source 强制设为 AI_MONITOR，discovered_at 用当前 UTC 时间；</li>
   *   <li>30 分钟内同 region + 同 disasterType 不重复创建（deduplication），
   *       防止 AI 调度周期内同一位置反复识别产生噪声；</li>
   *   <li>name 使用 AI 给出的类型 + 区域，如"AI 监测-滑坡(龙安镇青龙村)"；</li>
   *   <li>status 默认"待核查"——AI 识别后需值班员人工确认/核实。</li>
   * </ul>
   *
   * @return 已持久化的 HazardDto；如命中去重窗口，返回 null。
   */
  @Transactional
  public HazardDto createFromAiDetection(
      String regionCode,
      String disasterType,
      Double lon,
      Double lat,
      String riskLevel,
      Double detectionConfidence
  ) {
    if (regionCode == null || disasterType == null || lon == null || lat == null) {
      log.warn("AI 监测创建跳过：参数不完整 region={} type={} lon={} lat={}",
          regionCode, disasterType, lon, lat);
      return null;
    }
    // Deduplication：30 分钟内同 region + 同 disasterType 不重复创建
    OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(30);
    List<HazardPoint> recent = repository.findByRegionCodeAndHazardTypeAndDiscoveredAtAfterOrderByDiscoveredAtDesc(
        regionCode, disasterType, cutoff);
    if (!recent.isEmpty()) {
      log.debug("AI 监测去重：region={} type={} 已有 {} 条 30 分钟内记录",
          regionCode, disasterType, recent.size());
      return null;
    }

    HazardPoint point = new HazardPoint();
    point.setHazardCode(generateCode());
    point.setName(String.format("AI 监测-%s(%s)", disasterType, regionCode));
    point.setHazardType(disasterType);
    point.setRiskLevel(orDefault(riskLevel, "关注"));
    point.setRegionCode(regionCode);
    point.setOwner("AI 自动识别");
    point.setStatus("待核查");
    point.setLongitude(lon);
    point.setLatitude(lat);
    point.setGeom(GEOMETRY_FACTORY.createPoint(new Coordinate(lon, lat)));
    point.setThreatObject("AI 实时监测识别，待值班员现场核实");
    point.setSource("AI_MONITOR");
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    point.setDiscoveredAt(now);
    point.setDetectionConfidence(detectionConfidence == null ? 0.5 : Math.min(1.0, Math.max(0.0, detectionConfidence)));
    HazardPoint saved = repository.save(point);
    String eventCode = eventStateService.createFromAiDetection(
        saved.getHazardCode(), saved.getName());
    log.info("AI Hazard detected with unified_event: hazard={}, eventCode={}", saved.getHazardCode(), eventCode);
    return toDto(saved, eventCode, null);
  }

  /** AI 监测创建的灾害全部列出来（按发现时间倒序），供 AiHazardMonitorScheduler 健康检查。 */
  public List<HazardDto> listAiMonitoredRecent(int limit) {
    int safeLimit = Math.min(Math.max(limit, 1), 200);
    return repository.findTop50BySourceOrderByDiscoveredAtDesc("AI_MONITOR").stream()
        .limit(safeLimit)
        .map(this::toDto)
        .toList();
  }

  private static <T> T orDefault(T value, T fallback) {
    return value == null ? fallback : value;
  }

  @Transactional
  public HazardDto update(Long id, HazardUpsertRequest request) {
    HazardPoint point = repository.findById(id).orElseThrow(() -> new AppException(404, "未找到灾害点"));
    apply(point, request);
    HazardPoint saved = repository.save(point);
    return toDto(saved);
  }

  @Transactional
  public void delete(Long id) {
    if (!repository.existsById(id)) {
      throw new AppException(404, "未找到灾害点");
    }
    repository.deleteById(id);
  }

  private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

  private void apply(HazardPoint point, HazardUpsertRequest request) {
    point.setName(request.name());
    point.setHazardType(request.type());
    point.setRiskLevel(request.level());
    point.setRegionCode(request.regionCode());
    point.setOwner(request.owner());
    point.setStatus(request.status() == null || request.status().isBlank() ? "待核查" : request.status());
    point.setLongitude(request.lon());
    point.setLatitude(request.lat());
    point.setGeom(GEOMETRY_FACTORY.createPoint(new Coordinate(request.lon(), request.lat())));
    point.setThreatObject(request.threatObject());
  }

  private HazardDto toDto(HazardPoint point) {
    return toDto(point, null, null);
  }

  /**
   * v1.7.0+ P3-4：构造 HazardDto 时同时查 unified_event，写入 eventCode + eventState。
   * - eventCode 已存在：直接用（如 createFromAiDetection / create 时刚生成）
   * - eventCode 为 null：按 hazard_code 反查 unified_event（处理存量数据，无锚点则留空）
   */
  private HazardDto toDto(HazardPoint point, String preloadedEventCode, String preloadedEventState) {
    // AI 综合分决定"显示等级"：CRITICAL/HIGH → 高风险，MEDIUM → 关注，LOW → 正常；无 AI 时回退到 DB 静态值
    AiRiskEnricher.AiRiskBrief ai = aiEnricher.of(point.getHazardCode());
    String effectiveLevel = ai != null && ai.classicLevel() != null ? ai.classicLevel() : point.getRiskLevel();

    String eventCode = preloadedEventCode;
    String eventState = preloadedEventState;
    if (eventCode == null) {
      // 存量数据 / 列表接口：按 hazard_code 反查（最多一条，O(1)）
      List<UnifiedEventEntity> linked = eventStateService.findByHazardCode(point.getHazardCode());
      if (linked != null && !linked.isEmpty()) {
        UnifiedEventEntity e = linked.get(0);
        eventCode = e.getEventCode();
        eventState = e.getState() == null ? null : e.getState().name();
      }
    } else if (eventState == null) {
      // preloadedEventCode 不为空但 state 缺：补查
      cn.disasterguard.event.entity.UnifiedEventEntity e = eventStateService.findByEventCode(eventCode);
      eventState = e == null || e.getState() == null ? null : e.getState().name();
    }

    return new HazardDto(
        point.getId(),
        point.getHazardCode(),
        point.getName(),
        point.getHazardType(),
        effectiveLevel,
        point.getRegionCode(),
        point.getOwner(),
        point.getStatus(),
        point.getLongitude(),
        point.getLatitude(),
        point.getThreatObject(),
        point.getLastInspectionAt(),
        null,  // distance 依赖调用方传入参考点后计算，这里无上下文返回 null
        point.getSource() == null ? "AI_MONITOR" : point.getSource(),
        point.getDiscoveredAt(),
        point.getDetectionConfidence(),
        ai == null ? null : ai.riskLevel(),
        ai == null ? null : ai.levelLabel(),
        ai == null ? null : ai.riskScore(),
        ai == null ? null : ai.analyzedAt(),
        ai == null ? null : ai.disasterType(),
        ai == null ? null : ai.disasterTypeSource(),
        ai == null ? null : ai.concernLabel(),
        ai == null ? null : ai.deviceAvailable(),
        eventCode,
        eventState
    );
  }

  private boolean matches(HazardPoint point, String keyword, String regionCode, String level, String type, ViewportBounds bounds) {
    if (keyword != null && !keyword.isBlank()) {
      String like = keyword.trim().toLowerCase();
      boolean matched = contains(point.getName(), like) || contains(point.getHazardType(), like) || contains(point.getOwner(), like) || contains(point.getStatus(), like);
      if (!matched) return false;
    }
    if (regionCode != null && !regionCode.isBlank() && !regionCode.equals(point.getRegionCode())) return false;
    if (level != null && !level.isBlank()) {
      // level 筛选也走 AI：前端筛"高风险"会同时匹配 DB 标 + AI 升级为 CRITICAL/HIGH 的灾害点
      AiRiskEnricher.AiRiskBrief ai = aiEnricher.of(point.getHazardCode());
      String effectiveLevel = ai != null && ai.classicLevel() != null ? ai.classicLevel() : point.getRiskLevel();
      if (!level.equals(effectiveLevel)) return false;
    }
    if (type != null && !type.isBlank() && !type.equals(point.getHazardType())) return false;
    if (bounds != null && !bounds.contains(point.getLongitude(), point.getLatitude())) return false;
    return true;
  }

  private boolean contains(String value, String keyword) {
    return value != null && value.toLowerCase().contains(keyword);
  }

  private String generateCode() {
    return "HZ-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
  }
}
