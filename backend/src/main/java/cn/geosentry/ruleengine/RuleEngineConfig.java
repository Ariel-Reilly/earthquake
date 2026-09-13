package cn.geosentry.ruleengine;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 规则变更事件与发布器配置
 */
@Configuration
public class RuleEngineConfig {

  @Bean
  public ApplicationEventPublisher applicationEventPublisher() {
    return new org.springframework.context.ApplicationEventPublisher() {
      @Override
      public void publishEvent(Object event) {
        // 简单实现，实际应委托给 Spring 容器
      }

      @Override
      public void publishEvent(org.springframework.context.ApplicationEvent event) {
        // 简单实现
      }
    };
  }
}
