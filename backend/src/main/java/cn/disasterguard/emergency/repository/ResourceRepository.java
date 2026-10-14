package cn.disasterguard.emergency.repository;

import cn.disasterguard.emergency.entity.Resource;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** 应急资源仓库（对应 {@code resource} 表）。 */
public interface ResourceRepository
    extends JpaRepository<Resource, Long>, JpaSpecificationExecutor<Resource> {

  boolean existsByResCode(String resCode);

  Resource findByResCode(String resCode);

  List<Resource> findByResType(String resType);

  List<Resource> findByStatus(String status);

  /** 带经纬度的资源（用于地图打点）。 */
  List<Resource> findByLongitudeIsNotNullAndLatitudeIsNotNull();
}
