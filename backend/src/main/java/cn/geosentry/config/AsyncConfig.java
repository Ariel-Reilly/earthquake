package cn.geosentry.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步任务线程池配置。
 *
 * <p>应用通过 {@link org.springframework.scheduling.annotation.Async} 注解异步执行
 * 以下任务：AI 异常检测、TDengine 时序写入、WebSocket 广播等。
 * 默认 SimpleAsyncTaskExecutor 每次新建线程，缺少上限，容易耗尽 CPU。
 * 这里显式配置一个有界核心线程池，拒绝策略为 CallerRunsPolicy（队列满时由调用线程执行），
 * 确保不会因突发流量导致 OOM。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

  private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

  @Bean("taskExecutor")
  public Executor taskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    int cores = Runtime.getRuntime().availableProcessors();
    executor.setCorePoolSize(Math.max(4, cores));
    executor.setMaxPoolSize(Math.max(8, cores * 2));
    executor.setQueueCapacity(512);
    executor.setThreadNamePrefix("geosentry-async-");
    // 队列满时：由调用线程执行（降级但不丢任务），避免 RejectedExecutionException
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    executor.initialize();
    log.info("异步线程池已初始化: core={}, max={}, queue=512",
        executor.getCorePoolSize(), executor.getMaxPoolSize());
    return executor;
  }
}
