package cn.geosentry.notification.repository;

import cn.geosentry.notification.entity.NotificationChannelConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationChannelConfigRepository extends JpaRepository<NotificationChannelConfig, Long> {

  Optional<NotificationChannelConfig> findByDisasterTypeAndLevel(String disasterType, String level);

  List<NotificationChannelConfig> findByDisasterType(String disasterType);

  List<NotificationChannelConfig> findByEnabled(Boolean enabled);

  Optional<NotificationChannelConfig> findByDisasterTypeAndLevelAndEnabled(String disasterType, String level, Boolean enabled);
}
