package cn.disasterguard.governance.repository;

import cn.disasterguard.governance.entity.DataSource;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 数据源注册表数据访问，对应 {@code data_source} 表。 */
@Repository
public interface DataSourceRepository extends JpaRepository<DataSource, Long> {

  Optional<DataSource> findByDsCode(String dsCode);

  List<DataSource> findByOrderByIdAsc();
}
