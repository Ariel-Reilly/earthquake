package cn.disasterguard.event.service;

import cn.disasterguard.auth.entity.SysUser;
import cn.disasterguard.auth.repository.SysUserRepository;
import cn.disasterguard.common.exception.AppException;
import cn.disasterguard.event.entity.EventSign;
import cn.disasterguard.event.entity.EventTransfer;
import cn.disasterguard.event.repository.EventSignRepository;
import cn.disasterguard.event.repository.EventTransferRepository;
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
  /** 用于把当前登录名（auth.getName() = username）映射到 sys_user.id。 */
  private final SysUserRepository userRepository;

  public SignService(EventSignRepository signRepository,
                     EventTransferRepository transferRepository,
                     SysUserRepository userRepository) {
    this.signRepository = signRepository;
    this.transferRepository = transferRepository;
    this.userRepository = userRepository;
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
    // 安全修复（P0）：请求体里的 signerId 不再被直接信任，必须与当前登录身份匹配。
    assertCanActOn(signId, signerId, auth);
    EventSign target = resolveTarget(list, signerId, auth);
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
    // 安全修复（P0）：同 confirm，signerId 必须属于当前登录用户或持有 sign:proxy。
    assertCanActOn(signId, signerId, auth);
    EventSign target = resolveTarget(list, signerId, auth);
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

  /**
   * 会签身份校验（安全修复 P0：原实现只按请求体 signerId 查记录，任何人可冒签他人）。
   *
   * <p>signerId 的既有语义是「发起会签时前端传的会签人标识」，{@code createSign} 原样写入
   * {@code event_sign.signer_id}。由于前端历史实现存在两种写法——专家池传
   * {@code sys_user.id}（见 ConfirmationSignPage.loadExperts），而确认按钮传
   * {@code operator.name}（即 username）——这里同时接受当前登录用户的 username 与
   * {@code sys_user.id} 字符串，避免破坏现有会签流程；两种都不匹配则 403。
   *
   * <p>管理员代签：唯一合法通道是方法级权限码 {@code sign:proxy}（见
   * {@code PermissionRegistry}）。该权限码当前未授予任何默认角色，因此代签默认不可用；
   * 若业务确需保留代签，须由权限表 owner 在 PermissionRegistry 中显式授予指定角色，
   * 并在此留下审计日志（本方法已记录 warn 日志）。
   */
  private void assertCanActOn(String signId, String signerId, Authentication auth) {
    if (auth == null || auth.getName() == null || auth.getName().isBlank()
        || "anonymousUser".equals(auth.getName())) {
      throw new AppException(403, "未认证，无法操作会签");
    }
    String username = auth.getName();
    String currentUserId = resolveUserId(username);
    boolean self = signerId != null && !signerId.isBlank()
        && (signerId.equals(username) || signerId.equals(currentUserId));
    if (!self) {
      if (!hasAuthority(auth, "sign:proxy")) {
        throw new AppException(403, "无权操作他人会签：signerId 与当前登录用户不匹配");
      }
      log.warn("管理员代签 signId={} signerId={} operator={}（持有 sign:proxy）",
          signId, signerId, username);
    }
  }

  /**
   * 定位本次操作要更新的会签行。
   *
   * <p>优先按请求体 signerId 精确匹配；匹配不到且调用人身份已通过校验时，回退到
   * 「当前登录用户名 / sys_user.id」对应的待签行（用于兼容确认接口传 username、
   * 记录里存 userId 的前端历史差异）。只有唯一一条时才回退，避免串行会签误签。
   */
  private EventSign resolveTarget(List<EventSign> list, String signerId, Authentication auth) {
    EventSign byBody = list.stream()
        .filter(x -> signerId != null && signerId.equals(x.getSignerId()))
        .findFirst().orElse(null);
    if (byBody != null) {
      return byBody;
    }
    String username = auth == null ? null : auth.getName();
    String currentUserId = resolveUserId(username);
    List<EventSign> ownPending = list.stream()
        .filter(x -> "PENDING".equals(x.getSignerStatus()))
        .filter(x -> username != null && (username.equals(x.getSignerId()) || (currentUserId != null && currentUserId.equals(x.getSignerId()))))
        .toList();
    if (ownPending.size() == 1) {
      return ownPending.get(0);
    }
    throw new AppException(404, "该会签人不存在");
  }

  /** auth.getName() 是 username；返回对应 sys_user.id 的字符串（DB 无此用户时为 null）。 */
  private String resolveUserId(String username) {
    if (username == null || username.isBlank()) {
      return null;
    }
    SysUser user = userRepository.findByUsername(username).orElse(null);
    return user == null || user.getId() == null ? null : String.valueOf(user.getId());
  }

  private static boolean hasAuthority(Authentication auth, String authority) {
    return auth != null && auth.getAuthorities() != null
        && auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
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
