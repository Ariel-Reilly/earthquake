package cn.geosentry.hazard.repository;

import cn.geosentry.hazard.entity.HazardPoint;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HazardPointRepository extends JpaRepository<HazardPoint, Long>, JpaSpecificationExecutor<HazardPoint> {
  boolean existsByHazardCode(String hazardCode);
  List<HazardPoint> findByRegionCode(String regionCode);

  /** 视口内灾害点（PostGIS GiST 加速，bbox: minLon,minLat,maxLon,maxLat） */
  @Query(value = "SELECT * FROM hazard_point WHERE geom && ST_MakeEnvelope(:minLon, :minLat, :maxLon, :maxLat, 4326)", nativeQuery = true)
  List<HazardPoint> findInViewport(
      @Param("minLon") double minLon,
      @Param("minLat") double minLat,
      @Param("maxLon") double maxLon,
      @Param("maxLat") double maxLat);

  /** 某点 radiusMeters 范围内的灾害点（geography 按球面距离计算） */
  @Query(value = "SELECT * FROM hazard_point WHERE ST_DWithin(geom::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radiusMeters)", nativeQuery = true)
  List<HazardPoint> findWithinDistance(
      @Param("lon") double lon,
      @Param("lat") double lat,
      @Param("radiusMeters") double radiusMeters);
}
