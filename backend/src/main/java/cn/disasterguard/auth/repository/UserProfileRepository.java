package cn.disasterguard.auth.repository;

import cn.disasterguard.auth.entity.UserProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
  Optional<UserProfile> findByUsername(String username);
}
