package cn.geosentry.device.service;

import cn.geosentry.common.api.PageResponse;
import cn.geosentry.common.exception.AppException;
import cn.geosentry.common.export.DeviceExportRow;
import cn.geosentry.hazard.entity.HazardPoint;
import cn.geosentry.hazard.repository.HazardPointRepository;
import cn.geosentry.device.dto.DeviceDto;
import cn.geosentry.device.dto.DeviceUpsertRequest;
import cn.geosentry.device.entity.MonitoringDevice;
import cn.geosentry.device.repository.MonitoringDeviceRepository;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.criteria.Predicate;

@Service
public class DeviceService {

  private final MonitoringDeviceRepository repository;
  private final HazardPointRepository hazardRepository;

  public DeviceService(MonitoringDeviceRepository repository, HazardPointRepository hazardRepository) {
    this.repository = repository;
    this.hazardRepository = hazardRepository;
  }

  public PageResponse<DeviceDto> page(int page, int size, String keyword, String state) {
    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 200));
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
    return repository.findAll().stream().map(this::toDto).toList();
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
    Map<String, HazardPoint> hazardIndex = hazardRepository.findAll().stream()
        .filter(item -> item.getHazardCode() != null && !item.getHazardCode().isBlank())
        .collect(HashMap::new, (map, hazard) -> map.put(hazard.getHazardCode(), hazard), Map::putAll);

    return repository.findAll().stream()
        .filter(item -> matches(item, hazardIndex, bbox, keyword, state))
        .map(this::toDto)
        .toList();
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
        device.getLastReportAt() == null ? "刚刚" : "刚刚",
        device.getInstallLocation()
    );
  }

  private boolean matches(MonitoringDevice device, Map<String, HazardPoint> hazardIndex, String bbox, String keyword, String state) {
    if (keyword != null && !keyword.isBlank()) {
      String like = keyword.trim().toLowerCase();
      boolean matched = contains(device.getDeviceName(), like) || contains(device.getDeviceType(), like) || contains(device.getInstallLocation(), like);
      if (!matched) return false;
    }
    if (state != null && !state.isBlank() && !state.equals(device.getOnlineStatus())) return false;
    if (bbox != null && !bbox.isBlank()) {
      if (!withinBbox(device, hazardIndex, bbox)) return false;
    }
    return true;
  }

  private boolean withinBbox(MonitoringDevice device, Map<String, HazardPoint> hazardIndex, String bbox) {
    HazardPoint hazard = device.getHazardCode() == null ? null : hazardIndex.get(device.getHazardCode());
    if (hazard != null) {
      return withinBbox(hazard.getLongitude(), hazard.getLatitude(), bbox);
    }
    return withinBboxFromLocation(device.getInstallLocation(), bbox);
  }

  private boolean withinBbox(Double lon, Double lat, String bbox) {
    if (bbox == null || bbox.isBlank()) return true;
    if (lon == null || lat == null) return false;
    String[] parts = bbox.split(",");
    if (parts.length != 4) return true;
    try {
      double minLon = Double.parseDouble(parts[0].trim());
      double minLat = Double.parseDouble(parts[1].trim());
      double maxLon = Double.parseDouble(parts[2].trim());
      double maxLat = Double.parseDouble(parts[3].trim());
      return lon >= minLon && lon <= maxLon && lat >= minLat && lat <= maxLat;
    } catch (NumberFormatException ex) {
      return true;
    }
  }

  private boolean withinBboxFromLocation(String location, String bbox) {
    if (bbox == null || bbox.isBlank()) return true;
    if (location == null || location.isBlank()) return false;
    return true;
  }

  private boolean contains(String value, String keyword) {
    return value != null && value.toLowerCase().contains(keyword);
  }
}
