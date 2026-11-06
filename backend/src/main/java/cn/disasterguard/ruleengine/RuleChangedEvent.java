package cn.disasterguard.ruleengine;

/**
 * 规则变更事件
 */
public class RuleChangedEvent extends org.springframework.context.ApplicationEvent {

  private final Long ruleId;
  private final String action; // CREATED, UPDATED, DELETED, TOGGLED

  public RuleChangedEvent(Object source, Long ruleId, String action) {
    super(source);
    this.ruleId = ruleId;
    this.action = action;
  }

  public Long getRuleId() { return ruleId; }
  public String getAction() { return action; }
}
