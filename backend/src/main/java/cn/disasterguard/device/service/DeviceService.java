package cn.disasterguard.device.service;

import cn.disasterguard.ai.service.AiRiskEnricher;
import cn.disasterguard.common.api.PageResponse;
import cn.disasterguard.common.exception.AppException;
import cn.disasterguard.common.export.DeviceExportRow;
import cn.disasterguard.hazard.entity.HazardPoint;
import cn.disasterguard.hazard.repository.HazardPointRepository;
import cn.disasterguard.device.dto.DeviceDto;
import cn.disasterguard.device.dto.DeviceUpsertRequest;
import cn.disasterguard.device.entity.MonitoringDevice;
import cn.disasterguard.device.repository.MonitoringDeviceRepository;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.criteria.Predicate;

@Service
public class DeviceService {

  private final MonitoringDeviceRepository repository;
  private final HazardPointRepository hazardRepository;
  private final AiRiskEnricher aiEnricher;

  public DeviceService(MonitoringDeviceRepository repository, HazardPointRepository hazardRepository, AiRiskEnricher aiEnricher) {
    this.repository = repository;
    this.hazardRepository = hazardRepository;
    this.aiEnricher = aiEnricher;
  }

  public PageResponse<DeviceDto> page(int page, int size, String keyword, String state) {
    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 200),
        Sort.by(Sort.Direction.DESC, "id"));
    Specification<MonitoringDevice> spec = (root, query, cb) -> {
      Predicate predicate = cb.conjunction();
      if (keyword != null && !keyword.isBlank()) {
        String like = "%" + keyword.trim().toLowerCase() + "%";
        predicate = cb.and(predicate, cb.or(
            cb.like(cb.lower(root.get("deviceName")), like),
            cb.like(cb.lower(root.get("deviceType")), like),
            cb.like(cb.lower(root.get("installLocation")), like)
        ));
      }
      if (state != null && !state.isBlank()) {
        predicate = cb.and(predicate, cb.equal(root.get("onlineStatus"), state));
      }
      return predicate;
    };

    var result = repository.findAll(spec, pageable);
    List<DeviceDto> records = result.getContent().stream().map(this::toDto).toList();
    return new PageResponse<>(records, result.getTotalElements(), page, size, result.getTotalPages());
  }

  public List<DeviceDto> listAll() {
    return repository.findAll().stream()
        .map(this::toDto)
        // 优先展示"高 AI 分灾害点下的设备"——按所属灾害点的 AI 分倒序
        .sorted(Comparator.comparing(
            (DeviceDto dto) -> dto.aiScore() == null ? Double.NEGATIVE_INFINITY : dto.aiScore(),
            Comparator.reverseOrder()
        ).thenComparing(DeviceDto::id))
        .toList();
  }

  /** 导出用：返回监测设备导出行。 */
  public List<DeviceExportRow> exportRows() {
    return repository.findAll().stream()
        .sorted(Comparator.comparing(MonitoringDevice::getId))
        .map(d -> new DeviceExportRow(d.getDeviceCode(), d.getDeviceName(), d.getDeviceType(), d.getHazardCode(), d.getOnlineStatus(),
            d.getBatteryLevel(), d.getSignalLevel(), d.getInstallLocation(), d.getLongitude(), d.getLatitude(), d.getLastReportAt()))
        .toList();
  }

  public List<DeviceDto> listByViewport(String bbox, String keyword, String state) {
    // 第一步：DB 侧过滤 keyword + state（大幅缩小结果集）
    Specification<MonitoringDevice> spec = (root, query, cb) -> {
      Predicate p = cb.conjunction();
      if (keyword != null && !keyword.isBlank()) {
        String like = "%" + keyword.trim().toLowerCase() + "%";
        p = cb.and(p, cb.or(
            cb.like(cb.lower(root.get("deviceName")), like),
            cb.like(cb.lower(root.get("deviceType")), like),
            cb.like(cb.lower(root.get("installLocation")), like)
        ));
      }
      if (state != null && !state.isBlank()) {
        p = cb.and(p, cb.equal(root.get("onlineStatus"), state));
      }
      return p;
    };
    List<MonitoringDevice> candidates = repository.findAll(spec);

    // 第二步：内存中做 bbox 过滤（需关联 hazard_point 坐标，无法用 JPA Specification 表达）
    Map<String, HazardPoint> hazardIndex = hazardRepository.findAll().stream()
        .filter(item -> item.getHazardCode() != null && !item.getHazardCode().isBlank())
        .collect(HashMap::new, (map, hazard) -> map.put(hazard.getHazardCode(), hazard), Map::putAll);

    return candidates.stream()
        .filter(item -> matchesBbox(item, hazardIndex, bbox))
        .map(this::toDto)
        .sorted(Comparator.comparing(
            (DeviceDto dto) -> dto.aiScore() == null ? Double.NEGATIVE_INFINITY : dto.aiScore(),
            Comparator.reverseOrder()
        ).thenComparing(DeviceDto::id))
        .toList();
  }

  private boolean matchesBbox(MonitoringDevice device, Map<String, HazardPoint> hazardIndex, String bbox) {
    if (bbox == null || bbox.isBlank()) return true;
    HazardPoint hazard = device.getHazardCode() == null ? null : hazardIndex.get(device.getHazardCode());
    if (hazard != null) {
      return withinBbox(hazard.getLongitude(), hazard.getLatitude(), bbox);
    }
    return withinBboxFromLocation(device.getInstallLocation(), bbox);
  }

  public DeviceDto getById(Long id) {
    return repository.findById(id).map(this::toDto).orElseThrow(() -> new AppException(404, "未找到设备"));
  }

  @Transactional
  public DeviceDto create(DeviceUpsertRequest request) {
    MonitoringDevice device = new MonitoringDevice();
    device.setDeviceCode("DV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    apply(device, request);
    device.setLastReportAt(OffsetDateTime.now());
    return toDto(repository.save(device));
  }

  @Transactional
  public DeviceDto update(Long id, DeviceUpsertRequest request) {
    MonitoringDevice device = repository.findById(id).orElseThrow(() -> new AppException(404, "未找到设备"));
    apply(device, request);
    return toDto(repository.save(device));
  }

  @Transactional
  public void delete(Long id) {
    if (!repository.existsById(id)) {
      throw new AppException(404, "未找到设备");
    }
    repository.deleteById(id);
  }

  @Transactional
  public DeviceDto diagnose(Long id) {
    MonitoringDevice device = repository.findById(id).orElseThrow(() -> new AppException(404, "未找到设备"));
    device.setLastReportAt(OffsetDateTime.now());
    device.setSignalLevel("强");
    device.setOnlineStatus("在线");
    return toDto(repository.save(device));
  }

  private void apply(MonitoringDevice device, DeviceUpsertRequest request) {
    device.setDeviceName(request.name());
    device.setDeviceType(request.type());
    device.setHazardCode(request.hazardCode());
    device.setRegionCode(request.regionCode());
    device.setOnlineStatus(request.state());
    device.setBatteryLevel(request.battery());
    device.setSignalLevel(request.signal());
    device.setInstallLocation(request.point());
  }

  private DeviceDto toDto(MonitoringDevice device) {
    // 设备的"关注度"以所属灾害点的 AI 综合分为准：高 AI 分灾害点下的设备优先级最高
    AiRiskEnricher.AiRiskBrief ai = device.getHazardCode() == null ? null : aiEnricher.of(device.getHazardCode());
    return new DeviceDto(
        device.getId(),
        device.getDeviceCode(),
        device.getDeviceName(),
        device.getDeviceType(),
        device.getHazardCode(),
        device.getRegionCode(),
        device.getOnlineStatus(),
        device.getBatteryLevel(),
        device.getSignalLevel(),
        device.getLastReportAt(),
        relativeTime(device.getLastReportAt()),
        device.getInstallLocation(),
        ai == null ? null : ai.riskLevel(),
        ai == null ? null : ai.levelLabel(),
        ai == null ? null : ai.riskScore(),
        ai == null ? null : ai.analyzedAt()
    );
  }

  /** 将 OffsetDateTime 转为人类可读的相对时间（刚刚/N分钟前/N小时前/N天前）。 */
  private static String relativeTime(OffsetDateTime time) {
    if (time == null) return "无数据";
    long seconds = java.time.Duration.between(time, OffsetDateTime.now()).getSeconds();
    if (seconds < 60) return "刚刚";
    if (seconds < 3600) return (seconds / 60) + " 分钟前";
    if (seconds < 86400) return (seconds / 3600) + " 小时前";
    return (seconds / 86400) + " 天前";
  }

  private boolean withinBbox(Double lon, Double lat, String bbox) {
    if (bbox == null || bbox.isBlank()) return true;
    var bounds = cn.disasterguard.common.geo.ViewportBounds.parse(bbox);
    if (bounds == null) return true;  // bbox 格式异常时不过滤（保守）
    return bounds.contains(lon, lat);
  }

  private boolean withinBboxFromLocation(String location, String bbox) {
    if (bbox == null || bbox.isBlank()) return true;
    // 设备无关联 hazard 也无坐标信息时，不在 bbox 过滤范围内返回 false
    // （避免把未知位置的设备混入视口结果集）
    return false;
  }
}
