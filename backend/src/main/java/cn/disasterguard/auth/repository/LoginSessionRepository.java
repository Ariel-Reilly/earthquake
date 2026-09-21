package cn.disasterguard.auth.repository;

import cn.disasterguard.auth.entity.LoginSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 活跃会话数据访问。主键为会话 token 字符串。 */
@Repository
public interface LoginSessionRepository extends JpaRepository<LoginSession, String> {
}
