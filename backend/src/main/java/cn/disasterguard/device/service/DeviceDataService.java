package cn.disasterguard.device.service;

import cn.disasterguard.ai.AiServiceClient;
import cn.disasterguard.ai.dto.AnomalyResult;
import cn.disasterguard.ai.service.AiAnalysisService;
import cn.disasterguard.common.exception.AppException;
import cn.disasterguard.device.entity.DeviceMetric;
import cn.disasterguard.device.entity.MonitoringDevice;
import cn.disasterguard.device.repository.DeviceMetricRepository;
import cn.disasterguard.device.repository.MonitoringDeviceRepository;
import cn.disasterguard.realtime.RealtimeBroadcastService;
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
 *   <li>MqttDataSubscriber 订阅 disasterguard/+/data（EMQX 链路）</li>
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
    Map<String, Object> metrics = new LinkedHashMap<>();
    if (metricsObj instanceof Map<?, ?> m) {
      // 显式迭代并统一 key 为字符串，避免 unchecked cast
      for (Map.Entry<?, ?> entry : m.entrySet()) {
        metrics.put(String.valueOf(entry.getKey()), entry.getValue());
      }
    } else {
      metrics.putAll(payload);
    }

    // 去重时间戳：优先使用设备侧时间戳（ts / recordedAt / timestamp），
    // 避免同一批次里多个指标共享服务端 now() 与双通道重复上报互相冲突，
    // 也避免设备重发时（同 ts）被误判为两条不同数据。
    OffsetDateTime recordedAt = resolveTimestamp(payload);
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
        // 唯一索引 (device_code, metric_code, recorded_at_ms) 冲突 → 双通道重复上报，跳过
        log.debug("设备数据去重跳过 device={}, metric={}, recordedAt={}",
            deviceCode, entry.getKey(), recordedAt);
        continue;
      } catch (Exception ex) {
        // 其他数据库异常（外键、NOT NULL 等约束违规）才需要 warn
        log.warn("设备指标写入失败 device={}, metric={}: {}", deviceCode, entry.getKey(), ex.getMessage());
        continue;
      }

      // TDengine 写入：异步执行，不阻塞主上报事务（最多重试 3 次，含指数退避）
      if (tdengineMetricService.isEnabled()) {
        self.asyncTdengineWrite(deviceCode, entry.getKey(), value.doubleValue(), recordedAt);
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
          log.warn("AI 异常检测命中: device={}, metric={}, score={}, degraded={}",
              deviceCode, metric, result.score(), result.degraded());
        }
      }
    } catch (Exception e) {
      log.debug("AI 异步异常检测失败（忽略）: {}", e.getMessage());
    }
  }

  /**
   * 异步 TDengine 写入：不阻塞主上报事务。含 3 次指数退避重试。
   * 通过 self 代理调用以触发 @Async（同类内调用绕过代理）。
   */
  @Async
  public void asyncTdengineWrite(String deviceCode, String metricCode, double value, OffsetDateTime recordedAt) {
    Exception lastError = null;
    for (int attempt = 1; attempt <= 3; attempt++) {
      try {
        tdengineMetricService.writeMetric(deviceCode, metricCode, value, recordedAt);
        return;  // 成功即退出
      } catch (Exception ex) {
        lastError = ex;
        if (attempt < 3) {
          long sleepMs = (long) Math.pow(2, attempt) * 100;  // 200ms / 400ms
          try { Thread.sleep(sleepMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
      }
    }
    log.warn("TDengine 异步写入最终失败 device={}, metric={}（已重试 3 次）: {}",
        deviceCode, metricCode, lastError != null ? lastError.getMessage() : "unknown");
  }

  /**
   * 从上报 payload 解析设备侧时间戳，兼容多种格式：
   * <ul>
   *   <li>{@code ts / recorded_at / timestamp} 数字：epoch 秒或毫秒</li>
   *   <li>同一字段 ISO-8601 / "yyyy-MM-dd HH:mm:ss" 字符串</li>
   * </ul>
   * 解析失败或缺失时回退到服务端 now()。
   */
  private OffsetDateTime resolveTimestamp(Map<String, Object> payload) {
    for (String key : List.of("ts", "recorded_at", "recordedAt", "timestamp", "time")) {
      Object value = payload.get(key);
      OffsetDateTime ts = tryParseTimestamp(value);
      if (ts != null) {
        return ts;
      }
    }
    return OffsetDateTime.now();
  }

  private OffsetDateTime tryParseTimestamp(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number n) {
      long epoch = n.longValue();
      // > 10^12 视为毫秒，否则视为秒
      return epoch > 1_000_000_000_000L
          ? java.time.Instant.ofEpochMilli(epoch).atOffset(java.time.ZoneOffset.UTC)
          : java.time.Instant.ofEpochSecond(epoch).atOffset(java.time.ZoneOffset.UTC);
    }
    if (value instanceof String s) {
      String text = s.trim();
      if (text.isEmpty()) {
        return null;
      }
      try {
        return OffsetDateTime.parse(text);
      } catch (Exception ignore) {
        // 继续尝试其他格式
      }
      try {
        return java.time.LocalDateTime.parse(
                text, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            .atOffset(java.time.ZoneOffset.ofHours(8));  // 默认东八区（设备端约定）
      } catch (Exception ignore) {
        return null;
      }
    }
    return null;
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
