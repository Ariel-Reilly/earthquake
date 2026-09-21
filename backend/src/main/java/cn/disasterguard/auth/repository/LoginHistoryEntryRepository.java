package cn.disasterguard.auth.repository;

import cn.disasterguard.auth.entity.LoginHistoryEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** 登录历史数据访问。主键为应用侧分配的自增 id。 */
@Repository
public interface LoginHistoryEntryRepository extends JpaRepository<LoginHistoryEntry, Long> {

  @Query("select coalesce(max(e.id), 0) from LoginHistoryEntry e")
  long findMaxId();
}
