package cn.disasterguard.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.disasterguard.event.EventState;
import cn.disasterguard.event.EventStateMachineConfig;
import cn.disasterguard.event.EventTrigger;
import cn.disasterguard.event.entity.UnifiedEventEntity;
import cn.disasterguard.event.repository.UnifiedEventRepository;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.statemachine.config.StateMachineFactory;

/**
 * B5-1（L-14）：统一事件状态机回归测试（2026-11-13）。
 *
 * <p>测试对象是生产代码本身，不是复制品：
 * <ul>
 *   <li>用 {@link AnnotationConfigApplicationContext} 只注册
 *       {@link EventStateMachineConfig}，拿到真实的 {@link StateMachineFactory}；
 *       不启动 Spring Boot 上下文，因此不需要数据库凭据（规避既有 12 个
 *       {@code @SpringBootTest} 环境缺口）。</li>
 *   <li>{@link UnifiedEventRepository} 用 Mockito 打桩，状态推进完全由
 *       {@link EventStateService#transition} 驱动。</li>
 * </ul>
 *
 * <p>覆盖清单 B5-1 验收标准：确认 / 发布预警 / 关闭 / 解除（误报）合法流转、
 * 非法状态跳转被拒绝、重复操作幂等，以及终态原因 / 审计字段写入。
 *
 * <p><b>口径说明</b>：当前 {@code EventStateController#transition} 对非法流转返回
 * HTTP 200 + {@code ok=false}（不是 409）。本测试验证 service 层的 {@code false}
 * 返回值与"不落库"语义；409 属于 API 契约调整，未在本次交付中改动，见交付文档。
 */
@SuppressWarnings("unchecked")
class EventStateMachineTest {

  private static final String CODE = "UFE-TEST-0001";
  private static final String OPERATOR = "tester";
  private static final String REASON = "单元测试原因";

  private AnnotationConfigApplicationContext ctx;
  private UnifiedEventRepository repo;
  private EventStateService service;
  private UnifiedEventEntity entity;

  @BeforeEach
  void setUp() {
    ctx = new AnnotationConfigApplicationContext(EventStateMachineConfig.class);
    StateMachineFactory<EventState, EventTrigger> factory =
        (StateMachineFactory<EventState, EventTrigger>) ctx.getBean(StateMachineFactory.class);
    repo = mock(UnifiedEventRepository.class);
    service = new EventStateService(repo, factory);
    entity = new UnifiedEventEntity();
    entity.setEventCode(CODE);
    entity.setState(EventState.DETECTED);
    when(repo.findByEventCode(CODE)).thenReturn(Optional.of(entity));
  }

  @AfterEach
  void tearDown() {
    if (ctx != null) {
      ctx.close();
    }
  }

  private boolean fire(EventTrigger trigger) {
    return service.transition(CODE, trigger, OPERATOR, REASON);
  }

  @Test
  void 合法全链路_认领_确认_发布预警_触发应急_关闭() {
    assertThat(fire(EventTrigger.START_CONFIRM)).isTrue();
    assertThat(entity.getState()).isEqualTo(EventState.CONFIRMING);

    assertThat(fire(EventTrigger.CONFIRM)).isTrue();
    assertThat(entity.getState()).isEqualTo(EventState.CONFIRMED);

    assertThat(fire(EventTrigger.ISSUE_WARNING)).isTrue();
    assertThat(entity.getState()).isEqualTo(EventState.WARNING_ISSUED);

    assertThat(fire(EventTrigger.TRIGGER_EMERGENCY)).isTrue();
    assertThat(entity.getState()).isEqualTo(EventState.EMERGENCY_ACTIVE);

    assertThat(fire(EventTrigger.CLOSE)).isTrue();
    assertThat(entity.getState()).isEqualTo(EventState.CLOSED);

    verify(repo, times(5)).save(entity);
    assertThat(entity.getLastTrigger()).isEqualTo("CLOSE");
    assertThat(entity.getCloseReason()).isEqualTo(REASON);
    assertThat(entity.getClosedBy()).isEqualTo(OPERATOR);
    assertThat(entity.getLastTransitionAt()).isNotNull();
    assertThat(entity.getCurrentStateSince()).isNotNull();
  }

  @Test
  void 管理员可跳过认领直接确认() {
    assertThat(fire(EventTrigger.CONFIRM)).isTrue();
    assertThat(entity.getState()).isEqualTo(EventState.CONFIRMED);
    // 非终态不应写关闭原因
    assertThat(entity.getCloseReason()).isNull();
  }

  @Test
  void 驳回为终态_重开回到确认中() {
    assertThat(fire(EventTrigger.START_CONFIRM)).isTrue();
    assertThat(fire(EventTrigger.REJECT)).isTrue();
    assertThat(entity.getState()).isEqualTo(EventState.REJECTED);
    assertThat(entity.getCloseReason()).isEqualTo(REASON);

    assertThat(fire(EventTrigger.REOPEN)).isTrue();
    assertThat(entity.getState()).isEqualTo(EventState.CONFIRMING);
  }

  @Test
  void 误报解除为终态_重开回到预警已发布() {
    assertThat(fire(EventTrigger.DISMISS)).isTrue();
    assertThat(entity.getState()).isEqualTo(EventState.CLOSED);

    assertThat(fire(EventTrigger.REOPEN)).isTrue();
    assertThat(entity.getState()).isEqualTo(EventState.WARNING_ISSUED);
  }

  @Test
  void 终态非法流转被拒绝且不落库_幂等() {
    assertThat(fire(EventTrigger.DISMISS)).isTrue();
    assertThat(entity.getState()).isEqualTo(EventState.CLOSED);
    verify(repo, times(1)).save(entity);

    // CLOSED 下 CONFIRM / CLOSE / REJECT 都没有转换，只能 REOPEN
    assertThat(fire(EventTrigger.CONFIRM)).isFalse();
    assertThat(fire(EventTrigger.CLOSE)).isFalse();
    assertThat(fire(EventTrigger.REJECT)).isFalse();
    assertThat(entity.getState()).isEqualTo(EventState.CLOSED);
    // 被拒绝时不得写库（幂等：状态、原因、审计字段都不变）
    verify(repo, times(1)).save(entity);
  }

  @Test
  void 重复触发同一事件被拒绝() {
    assertThat(fire(EventTrigger.CONFIRM)).isTrue();
    assertThat(entity.getState()).isEqualTo(EventState.CONFIRMED);
    verify(repo, times(1)).save(entity);

    // CONFIRMED 下没有 CONFIRM 转换
    assertThat(fire(EventTrigger.CONFIRM)).isFalse();
    assertThat(entity.getState()).isEqualTo(EventState.CONFIRMED);
    verify(repo, times(1)).save(entity);
  }

  @Test
  void 活动态下不合法的跳转被拒绝() {
    // DETECTED 下不能直接发布预警 / 关闭（关闭要用 DISMISS）
    assertThat(fire(EventTrigger.ISSUE_WARNING)).isFalse();
    assertThat(fire(EventTrigger.CLOSE)).isFalse();
    assertThat(entity.getState()).isEqualTo(EventState.DETECTED);
    verify(repo, never()).save(entity);
  }

  @Test
  void 事件不存在时返回false且不落库() {
    when(repo.findByEventCode(CODE)).thenReturn(Optional.empty());
    assertThat(fire(EventTrigger.CONFIRM)).isFalse();
    verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void 状态枚举的终态与可升级判定() {
    assertThat(EventState.CLOSED.isTerminal()).isTrue();
    assertThat(EventState.REJECTED.isTerminal()).isTrue();
    assertThat(EventState.DETECTED.isTerminal()).isFalse();
    assertThat(EventState.WARNING_ISSUED.isTerminal()).isFalse();
    assertThat(EventState.CONFIRMED.canEscalate()).isTrue();
    assertThat(EventState.WARNING_ISSUED.canEscalate()).isTrue();
    assertThat(EventState.CLOSED.canEscalate()).isFalse();
  }

  @Test
  void getCurrentState读取repository中的状态() {
    assertThat(service.getCurrentState(CODE)).isEqualTo(EventState.DETECTED);
    assertThat(fire(EventTrigger.START_CONFIRM)).isTrue();
    assertThat(service.getCurrentState(CODE)).isEqualTo(EventState.CONFIRMING);
  }

  @Test
  void 流转日志记录真实的旧状态与新状态() {
    Logger logger = (Logger) LoggerFactory.getLogger(EventStateService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      assertThat(fire(EventTrigger.START_CONFIRM)).isTrue();
      // 曾因在 setState 之后才取旧值而打印成 "CONFIRMING -> CONFIRMING"
      assertThat(appender.list).anySatisfy(event ->
          assertThat(event.getFormattedMessage())
              .contains("DETECTED -> CONFIRMING")
              .contains("trigger=START_CONFIRM"));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
