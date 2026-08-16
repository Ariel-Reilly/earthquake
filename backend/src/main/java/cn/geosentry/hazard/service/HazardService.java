package cn.geosentry.hazard.service;

import cn.geosentry.common.api.PageResponse;
import cn.geosentry.common.exception.AppException;
import cn.geosentry.common.geo.ViewportBounds;
import cn.geosentry.common.export.HazardExportRow;
import cn.geosentry.hazard.dto.HazardDto;
import cn.geosentry.hazard.dto.HazardUpsertRequest;
import cn.geosentry.hazard.entity.HazardPoint;
import cn.geosentry.hazard.repository.HazardPointRepository;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HazardService {

  private final HazardPointRepository repository;

  public HazardService(HazardPointRepository repository) {
    this.repository = repository;
  }

  public PageResponse<HazardDto> page(int page, int size, String keyword, String regionCode, String level, String type, String bbox) {
    int pageSize = Math.min(Math.max(size, 1), 200);
    int pageNo = Math.max(page, 1);
    ViewportBounds bounds = ViewportBounds.parse(bbox);
    List<HazardDto> records = loadMatched(keyword, regionCode, level, type, bounds).stream()
        .sorted(Comparator.comparing(HazardPoint::getId, Comparator.nullsLast(Long::compareTo)))
        .map(this::toDto)
        .toList();
    long total = records.size();
    int pages = (int) Math.max(1, Math.ceil(total / (double) pageSize));
    int fromIndex = Math.min((pageNo - 1) * pageSize, records.size());
    int toIndex = Math.min(fromIndex + pageSize, records.size());
    return new PageResponse<>(records.subList(fromIndex, toIndex), total, pageNo, pageSize, pages);
  }

  public List<HazardDto> listAll(String regionCode) {
    List<HazardPoint> hazards = (regionCode == null || regionCode.isBlank()) ? repository.findAll() : repository.findByRegionCode(regionCode);
    return hazards.stream().sorted(Comparator.comparing(HazardPoint::getId)).map(this::toDto).toList();
  }

  /** 导出用：返回灾害点导出行。 */
  public List<HazardExportRow> exportRows() {
    return repository.findAll().stream()
        .sorted(Comparator.comparing(HazardPoint::getId))
        .map(p -> new HazardExportRow(p.getHazardCode(), p.getName(), p.getHazardType(), p.getRiskLevel(),
            p.getRegionCode(), p.getOwner(), p.getStatus(), p.getLongitude(), p.getLatitude(), p.getThreatObject(), p.getLastInspectionAt()))
        .toList();
  }

  public List<HazardDto> listByViewport(String bbox, String keyword, String regionCode, String level, String type) {
    ViewportBounds bounds = ViewportBounds.parse(bbox);
    return loadMatched(keyword, regionCode, level, type, bounds).stream()
        .sorted(Comparator.comparing(HazardPoint::getId, Comparator.nullsLast(Long::compareTo)))
        .map(this::toDto)
        .toList();
  }

  /** bbox 存在时走 PostGIS ST_MakeEnvelope + GiST 索引，否则全表扫描后内存过滤 */
  private List<HazardPoint> loadMatched(String keyword, String regionCode, String level, String type, ViewportBounds bounds) {
    List<HazardPoint> source = (bounds == null)
        ? repository.findAll()
        : repository.findInViewport(bounds.minLon(), bounds.minLat(), bounds.maxLon(), bounds.maxLat());
    return source.stream()
        .filter(point -> matches(point, keyword, regionCode, level, type, bounds))
        .toList();
  }

  public HazardDto getById(Long id) {
    return repository.findById(id).map(this::toDto).orElseThrow(() -> new AppException(404, "未找到灾害点"));
  }

  public Map<String, Object> geojson(String bbox, String keyword, String regionCode, String level, String type) {
    ViewportBounds bounds = ViewportBounds.parse(bbox);
    List<Map<String, Object>> features = loadMatched(keyword, regionCode, level, type, bounds).stream()
        .sorted(Comparator.comparing(HazardPoint::getId, Comparator.nullsLast(Long::compareTo)))
        .map(point -> {
          Map<String, Object> feature = new LinkedHashMap<>();
          feature.put("type", "Feature");
          feature.put("geometry", Map.of("type", "Point", "coordinates", List.of(point.getLongitude(), point.getLatitude())));

          Map<String, Object> properties = new LinkedHashMap<>();
          properties.put("id", point.getId());
          properties.put("hazardCode", point.getHazardCode());
          properties.put("name", point.getName());
          properties.put("type", point.getHazardType());
          properties.put("level", point.getRiskLevel());
          properties.put("regionCode", point.getRegionCode());
          properties.put("owner", point.getOwner());
          properties.put("status", point.getStatus());
          properties.put("lon", point.getLongitude());
          properties.put("lat", point.getLatitude());
          properties.put("distance", "1.2 km");

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
    return toDto(repository.save(point));
  }

  @Transactional
  public HazardDto update(Long id, HazardUpsertRequest request) {
    HazardPoint point = repository.findById(id).orElseThrow(() -> new AppException(404, "未找到灾害点"));
    apply(point, request);
    return toDto(repository.save(point));
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
    return new HazardDto(
        point.getId(),
        point.getHazardCode(),
        point.getName(),
        point.getHazardType(),
        point.getRiskLevel(),
        point.getRegionCode(),
        point.getOwner(),
        point.getStatus(),
        point.getLongitude(),
        point.getLatitude(),
        point.getThreatObject(),
        point.getLastInspectionAt(),
        "1.2 km"
    );
  }

  private boolean matches(HazardPoint point, String keyword, String regionCode, String level, String type, ViewportBounds bounds) {
    if (keyword != null && !keyword.isBlank()) {
      String like = keyword.trim().toLowerCase();
      boolean matched = contains(point.getName(), like) || contains(point.getHazardType(), like) || contains(point.getOwner(), like) || contains(point.getStatus(), like);
      if (!matched) return false;
    }
    if (regionCode != null && !regionCode.isBlank() && !regionCode.equals(point.getRegionCode())) return false;
    if (level != null && !level.isBlank() && !level.equals(point.getRiskLevel())) return false;
    if (type != null && !type.isBlank() && !type.equals(point.getHazardType())) return false;
    if (bounds != null && !bounds.contains(point.getLongitude(), point.getLatitude())) return false;
    return true;
  }

  private boolean contains(String value, String keyword) {
    return value != null && value.toLowerCase().contains(keyword);
  }

  private String generateCode() {
    return "HZ-" + System.currentTimeMillis();
  }
}
