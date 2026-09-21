package cn.disasterguard.backup.repository;

import cn.disasterguard.backup.entity.BackupStrategy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * 备份策略数据访问。
 *
 * <p>{@code id} 由 Controller 侧计数器手动分配，{@code findMaxId} 用于
 * 应用启动时把计数器恢复到历史最大 id 之后，保证重启后 id 不回退、不冲突。
 */
public interface BackupStrategyRepository extends JpaRepository<BackupStrategy, Long> {

  @Query("select coalesce(max(e.id), 0) from BackupStrategy e")
  long findMaxId();
}
