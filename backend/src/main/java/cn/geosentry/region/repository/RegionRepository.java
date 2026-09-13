package cn.geosentry.region.repository;

import cn.geosentry.region.entity.Region;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 行政区划 Repository（v1.7.0）。
 *
 * <p>支持按 level / path 前缀过滤，支持只查询"可监控"区域（town/village 且 geo_type 非 PLAIN）。
 */
@Repository
public interface RegionRepository extends JpaRepository<Region, Long> {

  Optional<Region> findByRegionCode(String regionCode);

  /**
   * 按行政级别查询（如 town/village）。
   */
  List<Region> findByRegionLevel(String regionLevel);

  /**
   * 按父节点查询下级区域。
   */
  List<Region> findByParentId(Long parentId);

  /**
   * 按 path 前缀查询（支持省-市-区县树形遍历）。
   * 例如 pathStartingWith = "100000/100100" 返回龙安镇及其下级。
   */
  @Query("SELECT r FROM Region r WHERE r.path LIKE CONCAT(:pathPrefix, '%')")
  List<Region> findByPathStartingWith(@Param("pathPrefix") String pathPrefix);

  /**
   * 只查询可监控区域（级别为 town/village 且 geo_type 非 PLAIN）。
   * 排除低风险平原区域，减少 AI 计算量。
   */
  @Query("SELECT r FROM Region r WHERE r.regionLevel IN ('town', 'village') AND r.geoType != 'PLAIN'")
  List<Region> findMonitorableRegions();

  /**
   * 按父节点查询可监控的下级区域。
   */
  @Query("SELECT r FROM Region r WHERE r.parentId = :parentId AND r.regionLevel IN ('town', 'village') AND r.geoType != 'PLAIN'")
  List<Region> findMonitorableByParentId(@Param("parentId") Long parentId);

  /**
   * 查询全部可监控区域（支持传入 geoType 白名单过滤）。
   */
  @Query("SELECT r FROM Region r WHERE r.regionLevel IN ('town', 'village') AND r.geoType IN :geoTypes")
  List<Region> findByRegionLevelInAndGeoTypeIn(
      @Param("geoTypes") List<String> geoTypes
  );
}
