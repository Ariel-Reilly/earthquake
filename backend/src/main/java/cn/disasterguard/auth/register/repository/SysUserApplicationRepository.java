package cn.disasterguard.auth.register.repository;

import cn.disasterguard.auth.register.entity.SysUserApplication;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * 注册申请单仓储。
 *
 * <p>按 status / keyword 的组合过滤交给 {@code JpaSpecificationExecutor}
 * 提供的 {@code findAll(Specification, Pageable)}，避免为每种组合新增派生方法。
 */
@Repository
public interface SysUserApplicationRepository
    extends JpaRepository<SysUserApplication, Long>,
    JpaSpecificationExecutor<SysUserApplication> {

  /**
   * 判断该用户名是否已有指定状态的申请单。
   *
   * @param username 用户名
   * @param status   申请状态（待审核/已通过/已拒绝）
   * @return 存在返回 true
   */
  boolean existsByUsernameAndStatus(String username, String status);

  /**
   * 取该用户名最新一条申请（按创建时间倒序），用于查询申请状态。
   *
   * @param username 用户名
   * @return 最新申请，不存在返回空
   */
  Optional<SysUserApplication> findFirstByUsernameOrderByCreatedAtDesc(String username);
}
