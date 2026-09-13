package cn.geosentry.event.service;

import cn.geosentry.common.exception.AppException;
import cn.geosentry.event.entity.EventSign;
import cn.geosentry.event.entity.EventTransfer;
import cn.geosentry.event.repository.EventSignRepository;
import cn.geosentry.event.repository.EventTransferRepository;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P0-4：会签与转派服务。
 * - 每个会签 session 用 signId 关联多个 signer 行。
 * - 并行：所有 signer 共同 PENDING，全部 CONFIRMED 后置为 COMPLETED。
 * - 串行：按 order_index 顺序，前一人未 CONFIRMED 则后一人不会签（这里简化为：串行模式下，仅暴露当前 order 的 signer 给前端）。
 * - 超时动作由上层调度器触发（生产里可以集成 Spring Scheduler；此处仅持久化配置）。
 */
@Service
public class SignService {

  private static final Logger log = LoggerFactory.getLogger(SignService.class);
  private final EventSignRepository signRepository;
  private final EventTransferRepository transferRepository;

  public SignService(EventSignRepository signRepository, EventTransferRepository transferRepository) {
    this.signRepository = signRepository;
    this.transferRepository = transferRepository;
  }

  @Transactional
  public Map<String, Object> createSign(String eventId,
                                        List<Map<String, String>> signers,
                                        String mode,
                                        Integer timeout,
                                        String timeoutAction,
                                        String comment,
                                        Authentication auth) {
    if (signers == null || signers.isEmpty()) {
      throw new AppException(400, "至少选择一名会签人");
    }
    if (signers.size() > 5) {
      throw new AppException(400, "会签人最多 5 人");
    }
    String signId = "sign-" + UUID.randomUUID().toString().substring(0, 8);
    int order = 0;
    for (Map<String, String> s : signers) {
      EventSign e = new EventSign();
      e.setSignId(signId);
      e.setEventId(eventId);
      e.setMode(mode == null ? "PARALLEL" : mode);
      e.setTimeoutHours(timeout == null ? 24 : timeout);
      e.setTimeoutAction(timeoutAction == null ? "ESCALATE" : timeoutAction);
      e.setComment(comment);
      e.setInitiator(auth == null ? "system" : auth.getName());
      e.setStatus("RUNNING");
      e.setSignerId(s.get("id"));
      e.setSignerName(s.get("name"));
      e.setSpecialty(s.get("specialty"));
      e.setOrderIndex("SEQUENTIAL".equalsIgnoreCase(mode) ? order++ : 0);
      e.setSignerStatus("PENDING");
      e.setCreatedAt(OffsetDateTime.now());
      signRepository.save(e);
    }
    log.info("已创建会签 signId={} eventId={} mode={} signers={}", signId, eventId, mode, signers.size());
    return Map.of("signId", signId, "eventId", eventId, "mode", mode, "status", "RUNNING");
  }

  @Transactional
  public Map<String, Object> confirm(String signId, String signerId, String opinion, Authentication auth) {
    List<EventSign> list = signRepository.findBySignId(signId);
    if (list.isEmpty()) throw new AppException(404, "会签不存在");
    EventSign target = list.stream().filter(x -> x.getSignerId().equals(signerId)).findFirst()
        .orElseThrow(() -> new AppException(404, "该会签人不存在"));
    target.setSignerStatus("CONFIRMED");
    target.setOpinion(opinion == null ? "" : opinion);
    target.setSignedAt(OffsetDateTime.now());
    signRepository.save(target);
    // 同步整体状态
    updateOverallStatus(list);
    Map<String, Object> resp = new HashMap<>();
    resp.put("signId", signId);
    resp.put("signerStatus", "CONFIRMED");
    resp.put("overallStatus", list.get(0).getStatus());
    return resp;
  }

  @Transactional
  public Map<String, Object> reject(String signId, String signerId, String opinion, Authentication auth) {
    List<EventSign> list = signRepository.findBySignId(signId);
    if (list.isEmpty()) throw new AppException(404, "会签不存在");
    EventSign target = list.stream().filter(x -> x.getSignerId().equals(signerId)).findFirst()
        .orElseThrow(() -> new AppException(404, "该会签人不存在"));
    target.setSignerStatus("REJECTED");
    target.setOpinion(opinion == null ? "" : opinion);
    target.setSignedAt(OffsetDateTime.now());
    signRepository.save(target);
    list.forEach(x -> { x.setStatus("REJECTED"); x.setCompletedAt(OffsetDateTime.now()); signRepository.save(x); });
    Map<String, Object> resp = new HashMap<>();
    resp.put("signId", signId);
    resp.put("signerStatus", "REJECTED");
    resp.put("overallStatus", "REJECTED");
    return resp;
  }

  public List<EventSign> listByEvent(String eventId) {
    return signRepository.findByEventId(eventId);
  }

  @Transactional
  public Map<String, Object> transfer(String eventId, String toUser, String reason, Authentication auth) {
    EventTransfer t = new EventTransfer();
    t.setEventId(eventId);
    t.setFromUser(auth == null ? null : auth.getName());
    t.setToUser(toUser);
    t.setReason(reason);
    t.setOperator(auth == null ? null : auth.getName());
    t.setTransferredAt(OffsetDateTime.now());
    transferRepository.save(t);
    log.info("事件 {} 已转派给 {}，操作人：{}", eventId, toUser, auth == null ? "system" : auth.getName());
    return Map.of(
        "transferId", String.valueOf(t.getId()),
        "eventId", eventId,
        "toUser", toUser,
        "transferredAt", t.getTransferredAt().toString());
  }

  private void updateOverallStatus(List<EventSign> list) {
    boolean allConfirmed = list.stream().allMatch(x -> "CONFIRMED".equals(x.getSignerStatus()));
    boolean anyRejected = list.stream().anyMatch(x -> "REJECTED".equals(x.getSignerStatus()));
    String newStatus;
    OffsetDateTime completed = null;
    if (anyRejected) {
      newStatus = "REJECTED";
      completed = OffsetDateTime.now();
    } else if (allConfirmed) {
      newStatus = "COMPLETED";
      completed = OffsetDateTime.now();
    } else {
      newStatus = "RUNNING";
    }
    final OffsetDateTime finalCompleted = completed;
    list.forEach(x -> {
      x.setStatus(newStatus);
      x.setCompletedAt(finalCompleted);
      signRepository.save(x);
    });
  }
}
