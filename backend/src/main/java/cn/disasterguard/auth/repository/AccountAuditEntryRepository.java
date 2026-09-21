package cn.disasterguard.auth.repository;

import cn.disasterguard.auth.entity.AccountAuditEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** 账户审计日志数据访问。主键为应用侧分配的自增 id。 */
@Repository
public interface AccountAuditEntryRepository extends JpaRepository<AccountAuditEntry, Long> {

  @Query("select coalesce(max(e.id), 0) from AccountAuditEntry e")
  long findMaxId();
}
