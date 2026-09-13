package cn.geosentry.notification.repository;

import cn.geosentry.notification.entity.NotificationReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationReceiptRepository extends JpaRepository<NotificationReceipt, Long> {

  List<NotificationReceipt> findByRecordId(Long recordId);

  List<NotificationReceipt> findByRecordIdAndChannel(Long recordId, String channel);

  List<NotificationReceipt> findByStatus(String status);

  long countByRecordIdAndStatus(Long recordId, String status);

  long countByChannelAndStatus(String channel, String status);
}
