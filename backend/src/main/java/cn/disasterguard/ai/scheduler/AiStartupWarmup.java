package cn.disasterguard.ai.scheduler;

import cn.disasterguard.ai.AiServiceClient;
import cn.disasterguard.ai.service.AiDeviceHealthService;
import cn.disasterguard.ai.service.AiInsightService;
import jakarta.annotation.PreDestroy;
import java.util.Map;
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

  private final AiServiceClient aiClient;
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

  public AiStartupWarmup(AiServiceClient aiClient,
                          AiInsightService insightService,
                          AiDeviceHealthService deviceHealthService,
                          AiHazardMonitorScheduler hazardMonitor,
                          @Value("${disasterguard.ai.startup-warmup-enabled:true}") boolean enabled) {
    this.aiClient = aiClient;
    this.insightService = insightService;
    this.deviceHealthService = deviceHealthService;
    this.hazardMonitor = hazardMonitor;
    this.enabled = enabled;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Order(50)  // 比 web 监听早一些，确保 AI 数据先就绪
  public void onReady() {
    // 【修复】检查 ai.enabled 配置，false 时直接返回，避免无效的超时等待
    if (!enabled) {
      log.info("AI 启动 warmup 已禁用（startup-warmup-enabled=false）");
      return;
    }
    if (!aiClient.isEnabled()) {
      log.info("AI 功能已关闭（ai.enabled=false），跳过 warmup");
      return;
    }
    log.info("Spring 启动完成，3s 后异步触发 AI warmup（综合分析 + 设备健康 + 灾害监测）");
    executor.submit(this::warmupComprehensive);
  }

  private void warmupComprehensive() {
    try {
      Thread.sleep(3000);  // 给 PostgreSQL/Redis 完全就绪留点缓冲
      
      // 【超时控制】各项任务分别添加超时，防止卡启动
      long totalStart = System.currentTimeMillis();
      
      // 综合分析：区分正常返回 vs 超时返回
      try {
        long t0 = System.currentTimeMillis();
        var r1 = java.util.concurrent.CompletableFuture.supplyAsync(
            () -> insightService.refreshAllNow(),
            executor
        ).completeOnTimeout(Map.of("ok", 0, "fail", 0), 30, TimeUnit.SECONDS).get();
        long durationMs = System.currentTimeMillis() - t0;
        
        // 判断执行路径：检查返回 Map 的键名
        // normal: 包含 analyzedCount（正常返回），无论是否为0
        // timeout: 包含 ok/fail（超时返回默认值），耗时约30s
        String pathType;
        Object analyzed;
        Object failed;
        
        if (r1.containsKey("analyzedCount")) {
          // 正常返回：区分是否有数据
          pathType = "normal";
          analyzed = r1.get("analyzedCount");
          failed = r1.get("failedCount");
          
          try {
            long analyzedCount = ((Number)analyzed).longValue();
            long failedCount = ((Number)failed).longValue();
            
            if (analyzedCount == 0 && failedCount == 0) {
              log.info("【AI Warmup】综合分析完成: path={} status=empty analyzedCount={} failedCount={} 耗时 {}ms",
                  pathType, analyzedCount, failedCount, durationMs);
            } else {
              log.info("【AI Warmup】综合分析完成: path={} status=done analyzedCount={} failedCount={} 耗时 {}ms",
                  pathType, analyzedCount, failedCount, durationMs);
            }
          } catch (ClassCastException | NullPointerException ex) {
            log.warn("【AI Warmup】综合分析完成: path=error 返回值类型错误 analyzedCount类型={} failedCount类型={} err={}",
                analyzed == null ? "null" : analyzed.getClass().getSimpleName(),
                failed == null ? "null" : failed.getClass().getSimpleName(),
                ex.getMessage());
          }
        } else {
          // 超时返回
          pathType = "timeout";
          analyzed = r1.getOrDefault("ok", 0);
          failed = r1.getOrDefault("fail", 0);
          log.info("【AI Warmup】综合分析完成: path={} analyzedCount={} failedCount={} 耗时 {}ms",
              pathType, analyzed, failed, durationMs);
        }
      } catch (Exception e) {
        log.warn("【AI Warmup】综合分析超时或失败: path=error err={}", e.getMessage());
      }
      
      // 设备健康：同上
      try {
        long t1 = System.currentTimeMillis();
        var r2 = java.util.concurrent.CompletableFuture.supplyAsync(
            () -> deviceHealthService.refreshAllNow(),
            executor
        ).completeOnTimeout(Map.of("ok", 0, "fail", 0), 20, TimeUnit.SECONDS).get();
        long durationMs = System.currentTimeMillis() - t1;
        
        String pathType;
        Object analyzed;
        Object failed;
        
        if (r2.containsKey("analyzedCount")) {
          pathType = "normal";
          analyzed = r2.get("analyzedCount");
          failed = r2.get("failedCount");
          
          try {
            long analyzedCount = ((Number)analyzed).longValue();
            long failedCount = ((Number)failed).longValue();
            
            if (analyzedCount == 0 && failedCount == 0) {
              log.info("【AI Warmup】设备健康完成: path={} status=empty analyzedCount={} failedCount={} 耗时 {}ms",
                  pathType, analyzedCount, failedCount, durationMs);
            } else {
              log.info("【AI Warmup】设备健康完成: path={} status=done analyzedCount={} failedCount={} 耗时 {}ms",
                  pathType, analyzedCount, failedCount, durationMs);
            }
          } catch (ClassCastException | NullPointerException ex) {
            log.warn("【AI Warmup】设备健康完成: path=error 返回值类型错误 analyzedCount类型={} failedCount类型={} err={}",
                analyzed == null ? "null" : analyzed.getClass().getSimpleName(),
                failed == null ? "null" : failed.getClass().getSimpleName(),
                ex.getMessage());
          }
        } else {
          pathType = "timeout";
          analyzed = r2.getOrDefault("ok", 0);
          failed = r2.getOrDefault("fail", 0);
          log.info("【AI Warmup】设备健康完成: path={} analyzedCount={} failedCount={} 耗时 {}ms",
              pathType, analyzed, failed, durationMs);
        }
      } catch (Exception e) {
        log.warn("【AI Warmup】设备健康超时或失败: path=error err={}", e.getMessage());
      }
      
      // 灾害监测：此接口返回结构不同，保持原样但加 path 标识
      try {
        long t2 = System.currentTimeMillis();
        var r3 = java.util.concurrent.CompletableFuture.supplyAsync(
            () -> hazardMonitor.tick(),
            executor
        ).completeOnTimeout(Map.of("ok", false), 15, TimeUnit.SECONDS).get();
        long durationMs = System.currentTimeMillis() - t2;
        
        // hazardMonitor.tick() 的返回结构包含 grid_points/ai_detections，不检查键名
        Object ok = r3.getOrDefault("ok", false);
        Object gridPoints = r3.getOrDefault("grid_points", "unknown");
        Object aiDetections = r3.getOrDefault("ai_detections", "unknown");
        
        log.info("【AI Warmup】灾害监测完成: ok={} grid_points={} ai_detections={} 耗时 {}ms",
            ok, gridPoints, aiDetections, durationMs);
      } catch (Exception e) {
        log.warn("【AI Warmup】灾害监测超时或失败: path=error err={}", e.getMessage());
      }
      
      log.info("【AI Warmup】全部完成，应用继续启动，总耗时 {}ms", 
          System.currentTimeMillis() - totalStart);
          
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      log.warn("【AI Warmup】被中断: {}", ie.getMessage());
    } catch (Exception ex) {
      log.warn("【AI Warmup】失败（不影响启动）: {}", ex.getMessage());
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