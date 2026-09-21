package cn.disasterguard.notification.repository;

import cn.disasterguard.notification.entity.NoticeTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 通知模板数据访问。
 *
 * <p>列表查询使用原生 SQL，因为渠道过滤需要在 JSONB 列上做键存在判断
 * （{@code channel_contents ->> :channel}），JPQL 无法表达该操作符。
 * 其余三个维度（灾害类型 / 等级 / 启停）走普通等值匹配，全部下推到数据库，
 * 不做全表加载后内存过滤。
 */
@Repository
public interface NoticeTemplateRepository extends JpaRepository<NoticeTemplate, Long> {

  /**
   * 多维过滤 + 分页查询。所有参数可为空，为空表示不过滤该维度。
   *
   * <p>{@code channel} 语义为「模板支持该渠道」：既匹配遗留单渠道列
   * {@code channel}，也匹配 JSONB 映射 {@code channel_contents} 中的键。
   */
  @Query(
      value = "SELECT * FROM notice_template t WHERE " +
             "(:disasterType IS NULL OR t.disaster_type = :disasterType) AND " +
             "(:level IS NULL OR t.level = :level) AND " +
             "(:enabled IS NULL OR t.enabled = :enabled) AND " +
             "(:channel IS NULL OR t.channel = :channel " +
             "  OR (t.channel_contents ->> CAST(:channel AS text)) IS NOT NULL) " +
             "ORDER BY t.updated_at DESC",
      countQuery = "SELECT COUNT(*) FROM notice_template t WHERE " +
             "(:disasterType IS NULL OR t.disaster_type = :disasterType) AND " +
             "(:level IS NULL OR t.level = :level) AND " +
             "(:enabled IS NULL OR t.enabled = :enabled) AND " +
             "(:channel IS NULL OR t.channel = :channel " +
             "  OR (t.channel_contents ->> CAST(:channel AS text)) IS NOT NULL)",
      nativeQuery = true)
  Page<NoticeTemplate> findByFilters(
      @Param("disasterType") String disasterType,
      @Param("level") String level,
      @Param("channel") String channel,
      @Param("enabled") Boolean enabled,
      Pageable pageable);

  /** 按业务编码查询（用于新建时校验重复）。 */
  NoticeTemplate findByTemplateCode(String templateCode);
}
