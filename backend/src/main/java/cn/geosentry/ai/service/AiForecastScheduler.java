package cn.geosentry.ai.service;

import cn.geosentry.ai.AiServiceClient;
import cn.geosentry.ai.dto.ForecastResult;
import cn.geosentry.device.entity.MonitoringDevice;
import cn.geosentry.device.repository.DeviceMetricRepository;
import cn.geosentry.device.repository.MonitoringDeviceRepository;
import jakarta.annotation.PreDestroy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时预测任务：对监测设备批量做未来 horizon 天预测并持久化。
 *
 * <p>使用类级固定线程池复用（daemon 线程），避免每次调度都 new + shutdown 的开销。
 * 完成后使用 CountDownLatch 等待所有任务完成（最多 5 分钟）。
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
  private final int parallelism;
  /** 复用的固定线程池（类级静态，JVM 生命周期内不复用创建销毁）。 */
  private final ExecutorService executor;

  public AiForecastScheduler(
      AiServiceClient client,
      MonitoringDeviceRepository deviceRepository,
      DeviceMetricRepository metricRepository,
      AiAnalysisService analysisService,
      @Value("${geosentry.ai.scheduler-enabled:false}") boolean enabled,
      @Value("${geosentry.ai.forecast-horizon:7}") int horizon,
      @Value("${geosentry.ai.history-window:30}") int historyWindow,
      @Value("${geosentry.ai.forecast-parallelism:8}") int parallelism
  ) {
    this.client = client;
    this.deviceRepository = deviceRepository;
    this.metricRepository = metricRepository;
    this.analysisService = analysisService;
    this.enabled = enabled;
    this.horizon = horizon;
    this.historyWindow = historyWindow;
    this.parallelism = Math.max(2, parallelism);
    this.executor = Executors.newFixedThreadPool(this.parallelism, r -> {
      Thread t = new Thread(r, "ai-forecast-worker");
      t.setDaemon(true);
      return t;
    });
  }

  @Scheduled(cron = "${geosentry.ai.forecast-cron:0 0 2 * * *}")
  public void runForecastAll() {
    if (!enabled || !client.isEnabled()) {
      return;
    }
    List<MonitoringDevice> devices = deviceRepository.findAll();
    if (devices.isEmpty()) return;

    AtomicInteger success = new AtomicInteger();
    AtomicInteger skipped = new AtomicInteger();
    AtomicInteger failed = new AtomicInteger();

    try {
      List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
      for (MonitoringDevice device : devices) {
        for (String metric : FORECAST_METRICS) {
          futures.add(CompletableFuture.runAsync(() -> {
            try {
              List<Double> history = loadHistory(device.getDeviceCode(), metric);
              if (history.size() < 5) {
                skipped.incrementAndGet();
                return;
              }
              ForecastResult result = client.forecast(device.getDeviceCode(), metric, horizon, history);
              analysisService.saveRecord("forecast", device.getDeviceCode(), result.model(), result);
              success.incrementAndGet();
            } catch (Exception e) {
              failed.incrementAndGet();
              log.warn("设备 {} metric={} 定时预测失败: {}", device.getDeviceCode(), metric, e.getMessage());
            }
          }, executor));
        }
      }
      // 等待全部完成（最多 5 分钟，避免 AI 服务不可用时永久阻塞）
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
          .orTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
          .join();
    } catch (Exception e) {
      log.error("AI 定时预测任务异常终止: {}", e.getMessage());
    }

    log.info("AI 定时预测完成: success={}, skipped={}, failed={}, total_devices={}, parallelism={}",
        success.get(), skipped.get(), failed.get(), devices.size(), parallelism);
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

  /** 应用关闭时优雅关闭线程池，等待正在执行的 AI 任务最多 30s。 */
  @PreDestroy
  public void shutdown() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        log.warn("AI 预测线程池未在 30s 内正常退出，已强制关闭");
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
    log.info("AI 预测线程池已关闭");
  }
}
