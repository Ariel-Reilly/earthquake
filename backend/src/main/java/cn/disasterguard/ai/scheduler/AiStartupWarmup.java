package cn.disasterguard.ai.scheduler;

import cn.disasterguard.ai.service.AiDeviceHealthService;
import cn.disasterguard.ai.service.AiInsightService;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Spring 启动完成事件监听 —— 触发 AI 调度"冷启动立即跑一次"。
 *
 * <p>设计动机：项目 v1.5.0 起 demo 数据全部入库，但默认 @Scheduled(cron="0 *\/5 * * * *")
 * 要等满 5 分钟才跑第一轮。前端访问 /ai-insight 会得到空缓存。
 *
 * <p>解决：在 ApplicationReadyEvent 异步触发 refreshAllNow()（不是 cron 路径，
 * 跳过 enabled 校验，强制跑一次）。后台 Executor 隔离避免阻塞应用就绪。
 *
 * <p>顺序：先综合分析（hazard 维度），再设备健康（device 维度）—— 因为 device_health
 * 依赖 ai_comprehensive 的 factors 做特征补充。
 *
 * <p>关掉：{@code disasterguard.ai.startup-warmup-enabled=false}
 */
@Component
public class AiStartupWarmup {

  private static final Logger log = LoggerFactory.getLogger(AiStartupWarmup.class);

  private final AiInsightService insightService;
  private final AiDeviceHealthService deviceHealthService;
  private final AiHazardMonitorScheduler hazardMonitor;
  private final boolean enabled;

  /** 单线程异步：避免和后续 web 请求抢线程 */
  private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "ai-startup-warmup");
    t.setDaemon(true);
    return t;
  });

  public AiStartupWarmup(AiInsightService insightService,
                          AiDeviceHealthService deviceHealthService,
                          AiHazardMonitorScheduler hazardMonitor,
                          @Value("${disasterguard.ai.startup-warmup-enabled:true}") boolean enabled) {
    this.insightService = insightService;
    this.deviceHealthService = deviceHealthService;
    this.hazardMonitor = hazardMonitor;
    this.enabled = enabled;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Order(50)  // 比 web 监听早一些，确保 AI 数据先就绪
  public void onReady() {
    if (!enabled) {
      log.info("AI 启动 warmup 已禁用（startup-warmup-enabled=false）");
      return;
    }
    log.info("Spring 启动完成，3s 后异步触发 AI warmup（综合分析 + 设备健康 + 灾害监测）");
    executor.submit(this::warmupComprehensive);
  }

  private void warmupComprehensive() {
    try {
      Thread.sleep(3000);  // 给 PostgreSQL/Redis 完全就绪留点缓冲
      long t0 = System.currentTimeMillis();
      var r1 = insightService.refreshAllNow();
      log.info("AI 综合分析 warmup 完成: ok={} fail={} 耗时 {}ms",
          r1.get("ok"), r1.get("fail"), System.currentTimeMillis() - t0);
      // 再触发设备健康（device 维度）
      long t1 = System.currentTimeMillis();
      var r2 = deviceHealthService.refreshAllNow();
      log.info("AI 设备健康 warmup 完成: ok={} fail={} 耗时 {}ms",
          r2.get("ok"), r2.get("fail"), System.currentTimeMillis() - t1);
      // v1.6.0：AI 灾害监测首次冷启动出首批点
      long t2 = System.currentTimeMillis();
      var r3 = hazardMonitor.tick();
      log.info("AI 灾害监测 warmup 完成: ok={} grid_points={} ai_detections={} 耗时 {}ms",
          r3.get("ok"), r3.get("grid_points"), r3.get("ai_detections"), System.currentTimeMillis() - t2);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    } catch (Exception ex) {
      log.warn("AI warmup 失败（不影响启动）: {}", ex.getMessage());
    }
  }

  /**
   * 优雅关闭：避免 JVM 退出阶段 warmup 还在 sleep / 调外部 AI，延迟进程退出。
   */
  @PreDestroy
  public void shutdown() {
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
        log.warn("AI startup warmup executor 未能在 2s 内停止");
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }
}