package cn.geosentry.auth.repository;

import cn.geosentry.auth.entity.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SysUserRepository extends JpaRepository<SysUser, Long> {
  SysUser findByUsername(String username);
  boolean existsByUsername(String username);
}
