package cn.geosentry.inspection.service;

import cn.geosentry.common.exception.AppException;
import cn.geosentry.hazard.entity.HazardPoint;
import cn.geosentry.hazard.repository.HazardPointRepository;
import cn.geosentry.inspection.dto.GpsCheckRequest;
import cn.geosentry.inspection.dto.GpsCheckResult;
import cn.geosentry.inspection.dto.InspectionRecordDto;
import cn.geosentry.inspection.dto.InspectionSubmitRequest;
import cn.geosentry.inspection.entity.InspectionRecord;
import cn.geosentry.inspection.repository.InspectionRecordRepository;
import cn.geosentry.task.entity.InspectionTask;
import cn.geosentry.task.repository.InspectionTaskRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InspectionService {

  private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);
  private final InspectionRecordRepository recordRepository;
  private final InspectionTaskRepository taskRepository;
  private final HazardPointRepository hazardPointRepository;
  private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

  public InspectionService(InspectionRecordRepository recordRepository, InspectionTaskRepository taskRepository,
                           HazardPointRepository hazardPointRepository,
                           com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
    this.recordRepository = recordRepository;
    this.taskRepository = taskRepository;
    this.hazardPointRepository = hazardPointRepository;
    this.objectMapper = objectMapper;
  }

  public GpsCheckResult checkFence(GpsCheckRequest request) {
    HazardPoint hazardPoint = findHazard(request.hazardCode());
    BigDecimal distance = distanceMeters(request.lon(), request.lat(), BigDecimal.valueOf(hazardPoint.getLongitude()), BigDecimal.valueOf(hazardPoint.getLatitude()));
    BigDecimal radius = request.allowedRadius() == null ? BigDecimal.valueOf(100) : request.allowedRadius();
    boolean withinFence = distance.compareTo(radius) <= 0;
    return new GpsCheckResult(
        request.taskCode(),
        request.hazardCode(),
        distance,
        radius,
        withinFence,
        withinFence ? "已进入围栏" : "未进入围栏",
        request.lon(),
        request.lat(),
        OffsetDateTime.now()
    );
  }

  @Transactional
  public InspectionRecordDto submit(InspectionSubmitRequest request) {
    HazardPoint hazardPoint = findHazard(request.hazardCode());
    InspectionTask task = taskRepository.findByTaskCode(request.taskCode());
    if (task == null) {
      throw new AppException(404, "未找到任务: " + request.taskCode());
    }

    BigDecimal distance = distanceMeters(request.lon(), request.lat(), BigDecimal.valueOf(hazardPoint.getLongitude()), BigDecimal.valueOf(hazardPoint.getLatitude()));
    BigDecimal radius = request.allowedRadius() == null ? BigDecimal.valueOf(100) : request.allowedRadius();
    boolean withinFence = distance.compareTo(radius) <= 0;
    boolean abnormal = Boolean.TRUE.equals(request.abnormalFlag());

    InspectionRecord record = new InspectionRecord();
    record.setRecordCode("IR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    record.setTaskCode(request.taskCode());
    record.setHazardCode(request.hazardCode());
    record.setInspector(request.inspector() == null || request.inspector().isBlank() ? task.getExecutor() : request.inspector());
    record.setInspectAt(OffsetDateTime.now());
    record.setSignInLon(request.lon());
    record.setSignInLat(request.lat());
    if (request.lon() != null && request.lat() != null) {
      record.setGeom(GEOMETRY_FACTORY.createPoint(new Coordinate(request.lon().doubleValue(), request.lat().doubleValue())));
    }
    record.setDistanceM(distance.setScale(2, RoundingMode.HALF_UP));
    record.setAbnormalFlag(abnormal);
    record.setInspectionResult(request.formData() == null ? "{}" : toJson(request.formData()));
    record.setNote(request.note());
    record.setPhotoIds(request.photoIds() == null ? "[]" : toJson(request.photoIds()));
    record.setCreatedAt(OffsetDateTime.now());
    InspectionRecord saved = recordRepository.save(record);

    if (withinFence) {
      task.setStatus(abnormal ? "执行中" : task.getStatus());
      task.setProgress(Math.min(100, task.getProgress() + 15));
      taskRepository.save(task);
    }

    return toDto(saved);
  }

  public List<InspectionRecordDto> listByTask(String taskCode) {
    return recordRepository.findByTaskCodeOrderByCreatedAtDesc(taskCode).stream().map(this::toDto).toList();
  }

  public List<InspectionRecordDto> listByHazard(String hazardCode) {
    return recordRepository.findByHazardCodeOrderByCreatedAtDesc(hazardCode).stream().map(this::toDto).toList();
  }

  private HazardPoint findHazard(String hazardCode) {
    return hazardPointRepository.findAll().stream()
        .filter(item -> hazardCode.equals(item.getHazardCode()))
        .findFirst()
        .orElseThrow(() -> new AppException(404, "未找到灾害点"));
  }

  private InspectionRecordDto toDto(InspectionRecord record) {
    return new InspectionRecordDto(
        record.getId(),
        record.getRecordCode(),
        record.getTaskCode(),
        record.getHazardCode(),
        record.getInspector(),
        record.getInspectAt(),
        record.getSignInLon(),
        record.getSignInLat(),
        record.getDistanceM(),
        Boolean.TRUE.equals(record.getAbnormalFlag()),
        record.getNote(),
        List.of(),
        Map.of()
    );
  }

  private BigDecimal distanceMeters(BigDecimal lon1, BigDecimal lat1, BigDecimal lon2, BigDecimal lat2) {
    double earthRadius = 6371000d;
    double dLat = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
    double dLon = Math.toRadians(lon2.doubleValue() - lon1.doubleValue());
    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
        + Math.cos(Math.toRadians(lat1.doubleValue())) * Math.cos(Math.toRadians(lat2.doubleValue()))
        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return BigDecimal.valueOf(earthRadius * c);
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return "{}";
    }
  }
}
