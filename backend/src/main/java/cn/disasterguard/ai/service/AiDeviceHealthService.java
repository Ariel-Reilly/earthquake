package cn.disasterguard.ai.service;

import cn.disasterguard.ai.AiServiceClient;
import cn.disasterguard.ai.AiUnavailableException;
import cn.disasterguard.ai.dto.DeviceHealthRequestDto;
import cn.disasterguard.ai.dto.DeviceHealthResponseDto;
import cn.disasterguard.ai.entity.AiDeviceHealth;
import cn.disasterguard.ai.repository.AiDeviceHealthRepository;
import cn.disasterguard.device.entity.MonitoringDevice;
import cn.disasterguard.device.repository.DeviceMetricRepository;
import cn.disasterguard.device.repository.MonitoringDeviceRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 设备健康评分调度器：每 5 分钟全量刷一遍。
 *
 * <p>职责清晰划分：
 * <ul>
 *   <li><b>本类（Java 侧）</b>：仅做"数据准备 + 调度 + 落库"——从 device_metric 拉最近 N 个时序，
 *       调 Python 端 /api/ai/device-health/score，把返回结果 UPSERT 到 ai_device_health。</li>
 *   <li><b>Python ai-service</b>（{@code app/models/device_health_model.py}）：
 *       多指标异常检测 + 位移时序预测 + health_score 加权融合 + 推理链 + 建议生成——
 *       所有"AI 算法"在此完成，Java 不再保留任何启发式评分逻辑。</li>
 * </ul>
 *
 * <p>AI 不可用时整台设备的健康评估跳过并日志记录，不影响其他设备；
 * 失败后由下一轮 5 分钟调度重新尝试，无需手动干预。
 */
@Service
public class AiDeviceHealthService {

  private static final Logger log = LoggerFactory.getLogger(AiDeviceHealthService.class);

  /** 参与评分的核心指标，与 Python 端 HEALTH_METRICS 一一对应。 */
  private static final List<String> HEALTH_METRICS = List.of(
      "disp_rate", "disp_total", "crack_width", "tilt");

  /** 单次拉取的样本数；30 个时序点足以让 anomaly / forecast 给出有意义的分数。 */
  private static final int SAMPLE_LIMIT = 30;

  private final MonitoringDeviceRepository deviceRepository;
  private final DeviceMetricRepository metricRepository;
  private final AiDeviceHealthRepository healthRepository;
  private final AiServiceClient aiClient;
  private final ObjectMapper objectMapper;
  private final boolean enabled;

  public AiDeviceHealthService(MonitoringDeviceRepository deviceRepository,
                                DeviceMetricRepository metricRepository,
                                AiDeviceHealthRepository healthRepository,
                                AiServiceClient aiClient,
                                ObjectMapper objectMapper,
                                @Value("${disasterguard.ai.device-health-scheduler-enabled:true}") boolean enabled) {
    this.deviceRepository = deviceRepository;
    this.metricRepository = metricRepository;
    this.healthRepository = healthRepository;
    this.aiClient = aiClient;
    this.objectMapper = objectMapper;
    this.enabled = enabled;
  }

  @Scheduled(cron = "${disasterguard.ai.device-health-scheduler-cron:0 */5 * * * *}")
  public void refreshAll() {
    if (!enabled) return;
    long t0 = System.currentTimeMillis();
    List<MonitoringDevice> devices = deviceRepository.findAll();
    int updated = 0;
    int failed = 0;
    for (MonitoringDevice d : devices) {
      try {
        analyzeOne(d);
        updated++;
      } catch (Exception ex) {
        failed++;
        log.warn("AI 设备健康评估失败 device={}: {}", d.getDeviceCode(), ex.getMessage());
      }
    }
    long cost = System.currentTimeMillis() - t0;
    log.info("AI 设备健康批量刷新完成：{} 台更新 / {} 失败 / 耗时 {} ms", updated, failed, cost);
  }

  /** 给 AiController 用的强制刷新入口（单条）。 */
  @Transactional
  public AiDeviceHealth refreshOne(String deviceCode) {
    MonitoringDevice device = deviceRepository.findByDeviceCode(deviceCode);
    if (device == null) throw new IllegalArgumentException("设备不存在: " + deviceCode);
    return analyzeOne(device);
  }

  /** 给 AiController 用的全量强制刷新入口。 */
  public Map<String, Object> refreshAllNow() {
    long t0 = System.currentTimeMillis();
    List<MonitoringDevice> devices = deviceRepository.findAll();
    int updated = 0;
    int failed = 0;
    List<String> failedDevices = new ArrayList<>();
    for (MonitoringDevice d : devices) {
      try {
        analyzeOne(d);
        updated++;
      } catch (Exception ex) {
        failed++;
        failedDevices.add(d.getDeviceCode() + ": " + ex.getMessage());
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("analyzedCount", updated);
    out.put("failedCount", failed);
    out.put("failedDevices", failedDevices);
    out.put("elapsedMs", System.currentTimeMillis() - t0);
    return out;
  }

  /**
   * 对单台设备做 AI 健康评分：收集时序 → 调 Python → 透传落库。
   *
   * <p>算法主体全部由 ai-service 完成。本方法仅做：
   * <ol>
   *   <li>从 device_metric 取最近 N 个时序（按指标分组、ASC 排列）；</li>
   *   <li>组装 {@link DeviceHealthRequestDto}（包含在线状态 + 上报时间）；</li>
   *   <li>调 ai-service /device-health/score，返回完整结果；</li>
   *   <li>把评分 / 等级 / reasoning / suggested_actions 等透传 UPSERT 到 ai_device_health。</li>
   * </ol>
   */
  @Transactional
  AiDeviceHealth analyzeOne(MonitoringDevice device) {
    String deviceCode = device.getDeviceCode();

    // 1) 收集每条核心指标的最新 30 个值（按时间升序）
    Map<String, List<Double>> metrics = new LinkedHashMap<>();
    for (String metric : HEALTH_METRICS) {
      List<Double> values = metricRepository.findRecentValues(deviceCode, metric, SAMPLE_LIMIT);
      // repository 返回的是 DESC，需要反转
      java.util.Collections.reverse(values);
      metrics.put(metric, values);
    }

    // 2) 调 Python 端做算法评分（一次往返，包含异常检测 + 时序预测 + 加权 + 归因）
    DeviceHealthRequestDto req = new DeviceHealthRequestDto(
        deviceCode,
        metrics,
        device.getOnlineStatus(),
        device.getLastReportAt() == null ? null : device.getLastReportAt().toString()
    );
    DeviceHealthResponseDto result;
    try {
      result = aiClient.deviceHealthScore(req);
    } catch (AiUnavailableException ex) {
      // AI 不可用：跳过本台设备，下一轮再试；其他设备不受影响
      throw ex;
    }

    // 3) 透传 Python 端返回值，组装 dimensions JSON
    Map<String, Object> dimensions = new LinkedHashMap<>();
    dimensions.put("anomalyByMetric", result.anomalyByMetric());
    dimensions.put("onlineStatus", device.getOnlineStatus());
    dimensions.put("lastReportAt",
        device.getLastReportAt() == null ? null : device.getLastReportAt().toString());
    dimensions.put("metricCoverage", result.metricCoverage());

    // 4) UPSERT（更新时间戳采用服务端时间，避免前端显示与落库时间漂移）
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    AiDeviceHealth row = healthRepository.findByDeviceCode(deviceCode).orElseGet(AiDeviceHealth::new);
    if (row.getCreatedAt() == null) row.setCreatedAt(now);
    row.setDeviceCode(deviceCode);
    row.setHealthLevel(result.healthLevel());
    row.setHealthScore(result.healthScore());
    row.setAnomalyScore(result.anomalyScore());
    row.setForecastRisk(result.forecastRisk());
    row.setMetricCoverage(result.metricCoverage());
    if (result.anomalyScore() != null && result.anomalyScore() > 30 && result.lastAnomalyAt() != null) {
      // Python 给出的 lastAnomalyAt 是 ISO 字符串，原样回写便于历史排查
      try {
        row.setLastAnomalyAt(OffsetDateTime.parse(result.lastAnomalyAt()));
      } catch (Exception parseEx) {
        row.setLastAnomalyAt(now);
      }
    }
    row.setDimensionsJson(toJson(dimensions));
    row.setReasoningJson(toJson(result.reasoning()));
    row.setSuggestedActionsJson(toJson(result.suggestedActions()));
    row.setConfidence(result.confidence());
    row.setModel(result.model());
    row.setDegraded(Boolean.TRUE.equals(result.degraded()));
    row.setAnalyzedAt(now);
    row.setUpdatedAt(now);
    return healthRepository.save(row);
  }

  public List<AiDeviceHealth> listAll() {
    return healthRepository.findAllByOrderByHealthScoreAsc();
  }

  public Optional<AiDeviceHealth> findByDeviceCode(String deviceCode) {
    return healthRepository.findByDeviceCode(deviceCode);
  }

  public Map<String, Object> toAttachmentMap(AiDeviceHealth s) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("deviceCode", s.getDeviceCode());
    m.put("healthLevel", s.getHealthLevel());
    m.put("healthScore", s.getHealthScore());
    m.put("anomalyScore", s.getAnomalyScore());
    m.put("forecastRisk", s.getForecastRisk());
    m.put("metricCoverage", s.getMetricCoverage());
    m.put("lastAnomalyAt", s.getLastAnomalyAt() == null ? null : s.getLastAnomalyAt().toString());
    m.put("reasoning", readJsonList(s.getReasoningJson()));
    m.put("suggestedActions", readJsonList(s.getSuggestedActionsJson()));
    m.put("confidence", s.getConfidence());
    m.put("model", s.getModel());
    m.put("analyzedAt", s.getAnalyzedAt() == null ? null : s.getAnalyzedAt().toString());
    return m;
  }


  private String toJson(Object o) {
    try {
      return objectMapper.writeValueAsString(o);
    } catch (JsonProcessingException e) {
      log.warn("JSON 序列化失败: {}", e.getMessage());
      return "[]";
    }
  }

  private List<String> readJsonList(String json) {
    if (json == null || json.isBlank()) return List.of();
    try {
      return objectMapper.readValue(json,
          objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    } catch (Exception e) {
      return List.of();
    }
  }
}
