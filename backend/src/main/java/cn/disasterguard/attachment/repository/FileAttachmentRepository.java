package cn.disasterguard.attachment.repository;

import cn.disasterguard.attachment.entity.FileAttachment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileAttachmentRepository extends JpaRepository<FileAttachment, Long> {
  List<FileAttachment> findByBusinessTypeAndBusinessCodeOrderByCreatedAtDesc(String businessType, String businessCode);
}
