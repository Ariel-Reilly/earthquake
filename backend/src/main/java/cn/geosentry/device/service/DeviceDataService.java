package cn.geosentry.device.service;

import cn.geosentry.ai.AiServiceClient;
import cn.geosentry.ai.dto.AnomalyResult;
import cn.geosentry.ai.service.AiAnalysisService;
import cn.geosentry.common.exception.AppException;
import cn.geosentry.device.entity.DeviceMetric;
import cn.geosentry.device.entity.MonitoringDevice;
import cn.geosentry.device.repository.DeviceMetricRepository;
import cn.geosentry.device.repository.MonitoringDeviceRepository;
import cn.geosentry.realtime.RealtimeBroadcastService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备数据上报链路：
 * <ol>
 *   <li>POST /api/devices/{code}/data HTTP 入口（由 DeviceAuthFilter 鉴权 X-Device-Key）</li>
 *   <li>MqttDataSubscriber 订阅 geohazard/+/data（EMQX 链路）</li>
 * </ol>
 *
 * <p>写入路径：PostgreSQL device_metric 为主存储，TDengine 为可选高性能时序存储（配置启用 + 驱动在 classpath 时生效）。
 */
@Service
public class DeviceDataService {

  private static final Logger log = LoggerFactory.getLogger(DeviceDataService.class);

  private final MonitoringDeviceRepository deviceRepository;
  private final DeviceMetricRepository metricRepository;
  private final ObjectMapper objectMapper;
  private final TdengineMetricService tdengineMetricService;
  private final RealtimeBroadcastService broadcastService;
  private final AiServiceClient aiClient;
  private final AiAnalysisService aiAnalysisService;
  private final DeviceDataService self;  // 用于触发 @Async 代理

  public DeviceDataService(MonitoringDeviceRepository deviceRepository,
                           DeviceMetricRepository metricRepository,
                           ObjectMapper objectMapper,
                           TdengineMetricService tdengineMetricService,
                           RealtimeBroadcastService broadcastService,
                           AiServiceClient aiClient,
                           AiAnalysisService aiAnalysisService,
                           @Lazy DeviceDataService self) {
    this.deviceRepository = deviceRepository;
    this.metricRepository = metricRepository;
    this.objectMapper = objectMapper;
    this.tdengineMetricService = tdengineMetricService;
    this.broadcastService = broadcastService;
    this.aiClient = aiClient;
    this.aiAnalysisService = aiAnalysisService;
    this.self = self;
  }

  @Transactional
  public int ingest(String deviceCode, Map<String, Object> payload) {
    MonitoringDevice device = deviceRepository.findByDeviceCode(deviceCode);
    if (device == null) {
      throw new AppException(404, "未找到设备: " + deviceCode);
    }

    Object metricsObj = payload.get("metrics");
    Map<String, Object> metrics;
    if (metricsObj instanceof Map<?, ?> m) {
      metrics = (Map<String, Object>) m;
    } else {
      metrics = payload;
    }

    OffsetDateTime recordedAt = OffsetDateTime.now();
    int count = 0;
    for (Map.Entry<String, Object> entry : metrics.entrySet()) {
      Number value = toNumber(entry.getValue());
      if (value == null) {
        continue;
      }
      DeviceMetric metric = new DeviceMetric();
      metric.setDeviceCode(deviceCode);
      metric.setHazardCode(device.getHazardCode());
      metric.setMetricCode(entry.getKey());
      metric.setValue(value.doubleValue());
      metric.setRecordedAt(recordedAt);
      metric.setRawPayload(toJson(payload));
      try {
        metricRepository.save(metric);
        count++;
      } catch (DataIntegrityViolationException ex) {
        // HTTP/MQTT 双通道重复上报——跳过（唯一索引 device_code+metric_code+recorded_at）
        log.debug("设备数据去重跳过 device={}, metric={}, recordedAt={}",
            deviceCode, entry.getKey(), recordedAt);
        continue;
      }

      // TDengine 写入（尽力而为，不影响主事务）
      if (tdengineMetricService.isEnabled()) {
        try {
          tdengineMetricService.writeMetric(deviceCode, entry.getKey(), value.doubleValue(), recordedAt);
        } catch (Exception ex) {
          log.debug("TDengine 写入跳过 device={}, metric={}: {}", deviceCode, entry.getKey(), ex.getMessage());
        }
      }
    }

    // 更新设备在线状态与最后上报时间
    String previousStatus = device.getOnlineStatus();
    boolean statusChanged = !"在线".equals(previousStatus);
    device.setOnlineStatus("在线");
    device.setLastReportAt(recordedAt);
    deviceRepository.save(device);

    // 设备状态变更时广播（离线→在线 恢复）
    if (statusChanged) {
      Map<String, Object> deviceDto = buildDeviceDto(device);
      broadcastService.broadcastDevice(deviceDto);
      log.info("设备状态已变更为在线: device={}, previous={}", deviceCode, previousStatus);
    }

    // 异步触发 AI 异常检测（不阻塞主上报链路）
    if (aiClient.isEnabled() && count > 0) {
      List<String> metricCodes = new ArrayList<>(metrics.keySet());
      self.triggerAnomalyDetection(deviceCode, metricCodes);  // 通过代理触发 @Async
    }

    return count;
  }

  /**
   * 异步异常检测：对设备最近一段时间的数据窗口做 Isolation Forest / 3-sigma 检测。
   * 若检测到异常，保存 AI 分析记录（供前端/告警消费），不直接生成预警（由规则引擎负责）。
   */
  @Async
  public void triggerAnomalyDetection(String deviceCode, List<String> metricCodes) {
    try {
      for (String metric : metricCodes) {
        List<Double> history = metricRepository.findRecentValues(deviceCode, metric, 30);
        if (history == null || history.size() < 8) {
          continue;
        }
        AnomalyResult result = aiClient.anomaly(deviceCode, history);
        aiAnalysisService.saveRecord("anomaly", deviceCode + ":" + metric, result.model(), result);
        if (result.isAnomaly()) {
          log.warn("AI 异常检测命中: device={}, metric={}, score={}",
              deviceCode, metric, result.anomalyScore());
        }
      }
    } catch (Exception e) {
      log.debug("AI 异步异常检测失败（忽略）: {}", e.getMessage());
    }
  }

  private Map<String, Object> buildDeviceDto(MonitoringDevice device) {
    Map<String, Object> dto = new LinkedHashMap<>();
    dto.put("id", device.getId());
    dto.put("deviceCode", device.getDeviceCode());
    dto.put("name", device.getDeviceName());
    dto.put("type", device.getDeviceType());
    dto.put("hazardCode", device.getHazardCode());
    dto.put("state", device.getOnlineStatus());
    dto.put("battery", device.getBatteryLevel());
    dto.put("signal", device.getSignalLevel());
    dto.put("lastReportAt", device.getLastReportAt() != null ? device.getLastReportAt().toString() : null);
    dto.put("point", device.getInstallLocation());
    dto.put("lon", device.getLongitude());
    dto.put("lat", device.getLatitude());
    return dto;
  }

  private Number toNumber(Object value) {
    if (value instanceof Number n) {
      return n;
    }
    if (value instanceof String s) {
      try {
        return Double.parseDouble(s);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      return String.valueOf(value);
    }
  }
}
