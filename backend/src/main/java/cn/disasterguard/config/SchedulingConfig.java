package cn.disasterguard.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 定时任务线程池配置（R3 P1-11）。
 *
 * <p>修复前项目只在 {@code DisasterguardApplication} 上标注了
 * {@code @EnableScheduling}，却从未定义 {@code TaskScheduler} bean，
 * Spring Boot 会退化为<b>默认单线程</b>调度器：全部 {@code @Scheduled}
 * 任务共享同一个线程。
 *
 * <p>后果是：任何一个任务因为 AI 调用 / HTTP 往返 / 批量写库而阻塞，
 * 都会连带拖死其余所有定时任务（气象拉取、设备离线检查、指标清理、
 * 规则引擎全部停摆），并造成 cron 积压——上一轮没跑完，下一轮直接被跳过。
 *
 * <p>这里显式配置一个多线程调度器，并在关闭时等待在途任务收尾，
 * 避免预警写到一半被中断。
 *
 * <p>注意：{@code @EnableScheduling} 仍在主类上，本配置只负责提供线程池。
 */
@Configuration
public class SchedulingConfig {

  private static final Logger log = LoggerFactory.getLogger(SchedulingConfig.class);

  /** 调度线程数：当前有 10+ 个 @Scheduled，5 条线程足以避免互相阻塞又不至于打满连接池。 */
  private static final int POOL_SIZE = 5;

  @Bean(name = "taskScheduler")
  public ThreadPoolTaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(POOL_SIZE);
    scheduler.setThreadNamePrefix("dg-sched-");
    scheduler.setRemoveOnCancelPolicy(true);
    // 优雅停机：等待在途任务收尾，避免预警落库到一半被中断
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(30);
    scheduler.setErrorHandler(t ->
        log.error("定时任务执行异常: {}", t.getMessage(), t));
    scheduler.initialize();
    return scheduler;
  }
}
