package cn.geosentry.ai.service;

import cn.geosentry.ai.AiServiceClient;
import cn.geosentry.ai.dto.ForecastResult;
import cn.geosentry.device.entity.MonitoringDevice;
import cn.geosentry.device.repository.DeviceMetricRepository;
import cn.geosentry.device.repository.MonitoringDeviceRepository;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时预测任务：对监测设备批量做未来 horizon 天预测并持久化。
 *
 * <p>默认关闭（geosentry.ai.scheduler-enabled=false），通过配置开启后生效；
 * AI 服务不可用时跳过并记录告警，不影响主流程。
 */
@Component
@EnableScheduling
public class AiForecastScheduler {

  private static final Logger log = LoggerFactory.getLogger(AiForecastScheduler.class);

  private static final String[] FORECAST_METRICS = {"displacement", "rainfall", "crack_width"};

  private final AiServiceClient client;
  private final MonitoringDeviceRepository deviceRepository;
  private final DeviceMetricRepository metricRepository;
  private final AiAnalysisService analysisService;

  private final boolean enabled;
  private final int horizon;
  private final int historyWindow;

  public AiForecastScheduler(
      AiServiceClient client,
      MonitoringDeviceRepository deviceRepository,
      DeviceMetricRepository metricRepository,
      AiAnalysisService analysisService,
      @Value("${geosentry.ai.scheduler-enabled:false}") boolean enabled,
      @Value("${geosentry.ai.forecast-horizon:7}") int horizon,
      @Value("${geosentry.ai.history-window:30}") int historyWindow
  ) {
    this.client = client;
    this.deviceRepository = deviceRepository;
    this.metricRepository = metricRepository;
    this.analysisService = analysisService;
    this.enabled = enabled;
    this.horizon = horizon;
    this.historyWindow = historyWindow;
  }

  @Scheduled(cron = "${geosentry.ai.forecast-cron:0 0 2 * * *}")
  public void runForecastAll() {
    if (!enabled || !client.isEnabled()) {
      return;
    }
    List<MonitoringDevice> devices = deviceRepository.findAll();
    int success = 0;
    int skipped = 0;
    for (MonitoringDevice device : devices) {
      for (String metric : FORECAST_METRICS) {
        try {
          List<Double> history = loadHistory(device.getDeviceCode(), metric);
          if (history.size() < 5) {
            skipped++;
            continue;
          }
          ForecastResult result = client.forecast(device.getDeviceCode(), metric, horizon, history);
          analysisService.saveRecord("forecast", device.getDeviceCode(), result.model(), result);
          success++;
        } catch (Exception e) {
          log.warn("设备 {} metric={} 定时预测失败，跳过: {}", device.getDeviceCode(), metric, e.getMessage());
        }
      }
    }
    log.info("AI 定时预测完成: success={}, skipped={}, total_devices={}",
        success, skipped, devices.size());
  }

  /** 从 device_metric 表查询最近 N 条指标值（按时间降序返回后反向为升序）。 */
  private List<Double> loadHistory(String deviceCode, String metric) {
    List<Double> desc = metricRepository.findRecentValues(deviceCode, metric, historyWindow);
    if (desc == null || desc.isEmpty()) {
      return Collections.emptyList();
    }
    java.util.Collections.reverse(desc);  // 升序，供 LSTM 顺序输入
    return desc;
  }

  /** 供手动触发测试（本地联调用）。 */
  public Map<String, Object> runOnce() {
    if (!client.isEnabled()) {
      return Map.of("skipped", true, "reason", "AI disabled");
    }
    runForecastAll();
    return Map.of("skipped", false, "horizon", horizon, "historyWindow", historyWindow);
  }
}
