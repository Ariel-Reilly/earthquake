package cn.geosentry.event.controller;

import cn.geosentry.event.EventState;
import cn.geosentry.event.EventTrigger;
import cn.geosentry.event.entity.UnifiedEventEntity;
import cn.geosentry.event.service.EventStateService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一事件状态查询 / 驱动 API（v1.7.0+ P3-4）。
 *
 * 端点：
 *   GET  /api/event/{code}           - 查询单个事件（含当前状态）
 *   POST /api/event/{code}/transition - 触发状态流转
 *   GET  /api/event/active           - 当前活动事件列表
 *   GET  /api/event/stats            - 各状态计数
 *
 * 安全约束：当前未强制鉴权（保留给后续接入 PermissionRegistry 统一注册）；
 * 在生产环境应通过 SecurityConfig 限制到 warnings:dispatch / emergency:dispatch
 * 等具体权限码。
 */
@RestController
@RequestMapping("/api/event")
public class EventStateController {

  private final EventStateService service;

  public EventStateController(EventStateService service) {
    this.service = service;
  }

  @GetMapping("/stats")
  public Map<EventState, Long> stats() {
    return service.countByState();
  }

  @GetMapping("/active")
  public List<UnifiedEventEntity> active() {
    return service.listActive();
  }

  @GetMapping("/{code}")
  public UnifiedEventEntity detail(@PathVariable String code) {
    return service.findByEventCode(code);
  }

  /**
   * 触发状态流转。请求体：
   * <pre>
   *   { "trigger": "CONFIRM", "operator": "张三", "reason": "现场核实确认" }
   * </pre>
   */
  @PostMapping("/{code}/transition")
  public Map<String, Object> transition(
      @PathVariable String code,
      @RequestBody TransitionRequest body
  ) {
    EventTrigger trigger = EventTrigger.valueOf(body.trigger);
    boolean ok = service.transition(code, trigger, body.operator, body.reason);
    return Map.of(
      "ok", ok,
      "currentState", service.getCurrentState(code),
    );
  }

  /** 兼容旧用法：通过 query 触发，便于快速调试 */
  @PostMapping("/{code}/transition/query")
  public Map<String, Object> transitionQuery(
      @PathVariable String code,
      @RequestParam String trigger,
      @RequestParam(required = false, defaultValue = "system") String operator,
      @RequestParam(required = false) String reason
  ) {
    EventTrigger t = EventTrigger.valueOf(trigger);
    boolean ok = service.transition(code, t, operator, reason);
    return Map.of(
      "ok", ok,
      "currentState", service.getCurrentState(code),
    );
  }

  public static class TransitionRequest {
    public String trigger;
    public String operator;
    public String reason;
  }
}
