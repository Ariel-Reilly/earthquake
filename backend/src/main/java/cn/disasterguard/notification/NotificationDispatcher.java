package cn.disasterguard.notification;

import cn.disasterguard.notification.dto.SendResultDto;
import cn.disasterguard.notification.entity.NotificationReceipt;
import cn.disasterguard.notification.entity.NotificationRecord;
import cn.disasterguard.notification.repository.NotificationReceiptRepository;
import cn.disasterguard.notification.repository.NotificationRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通知分发器：承载所有真正需要 @Async + 新事务的发送 / 重试逻辑。
 *
 * <p>把这一块从 {@link NotificationRouter} 拆出来，是为了避免同类内的自调用绕过 Spring
 * 代理——Spring 的 @Async 与 @Transactional 都基于代理实现，self-call 不会生效。
 * 由 router 通过 Spring 注入引用此 bean，调用会经过代理，@Async / @Transactional 才
 * 会真正开启新线程 / 新事务。
 *
 * <p>注意：本类与 {@link NotificationRouter} 之间存在构造器循环依赖（router 需要 dispatcher
 * 派发异步任务，dispatcher 又需要在 asyncSend 内回调 router 写回执）。通过在 {@code router}
 * 参数上加 {@link Lazy @Lazy} 解决——Spring 会注入一个代理对象，真正的 bean 在首次调用时才被
 * 解析，从而打破循环。否则启动期会抛出
 * {@code BeanCurrentlyInCreationException}。
 */
@Service
public class NotificationDispatcher {

  private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

  private final NotificationRouter router;
  private final NotificationRecordRepository recordRepository;
  private final NotificationReceiptRepository receiptRepository;

  public NotificationDispatcher(
      @Lazy NotificationRouter router,
      NotificationRecordRepository recordRepository,
      NotificationReceiptRepository receiptRepository) {
    this.router = router;
    this.recordRepository = recordRepository;
    this.receiptRepository = receiptRepository;
  }

  /**
   * 异步发送。
   *
   * <p>此方法必须通过 Spring 代理调用才会真正异步。Router.route() 通过注入的 bean
   * 引用调用 dispatcher，因此 @Async 生效。
   */
  @Async
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void asyncSend(NotificationRecord record,
                        cn.disasterguard.notification.dto.NotificationRequestDto request,
                        List<String> channels) {
    List<SendResultDto.ChannelResult> results = new ArrayList<>();

    for (String channel : channels) {
      for (String target : request.targetUsers()) {
        SendResultDto.ChannelResult result = router.sendToChannel(
            record.getId(), channel, target,
            request.title(), request.content(), request.variables());
        results.add(result);
      }
    }

    long successCount = results.stream()
        .filter(r -> "SUCCESS".equals(r.status()))
        .count();
    record.setStatus(successCount > 0 ? "PARTIAL" : "SENDING");
    record.setUpdatedAt(OffsetDateTime.now());
    try {
      recordRepository.save(record);
    } catch (Exception ex) {
      log.warn("更新发送记录状态失败 recordId={}: {}", record.getId(), ex.getMessage());
    }
  }

  /**
   * 同步重试失败回执（仍在新事务内执行；上层调用方若想异步可自行包装 @Async）。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public SendResultDto retry(Long recordId) {
    NotificationRecord record = recordRepository.findById(recordId)
        .orElseThrow(() -> new RuntimeException("记录不存在: " + recordId));

    List<NotificationReceipt> failedReceipts = receiptRepository
        .findByRecordIdAndStatus(recordId, "FAILED");

    if (failedReceipts.isEmpty()) {
      return new SendResultDto(recordId, "NO_FAILED", List.of(), "没有需要重试的记录");
    }

    List<SendResultDto.ChannelResult> results = new ArrayList<>();
    for (NotificationReceipt receipt : failedReceipts) {
      Integer retryCount = receipt.getRetryCount();
      if (retryCount != null && retryCount >= 3) {
        log.warn("回执 {} 已达最大重试次数", receipt.getId());
        continue;
      }

      SendResultDto.ChannelResult result = router.sendToChannel(
          recordId,
          receipt.getChannel(),
          receipt.getTarget(),
          record.getTitle(),
          record.getContent(),
          Map.of()
      );
      results.add(result);

      receipt.setRetryCount(retryCount == null ? 1 : retryCount + 1);
      try {
        receiptRepository.save(receipt);
      } catch (Exception ex) {
        log.warn("更新回执重试次数失败 receiptId={}: {}", receipt.getId(), ex.getMessage());
      }
    }

    return new SendResultDto(recordId, "RETRYING", results, "重试中");
  }
}