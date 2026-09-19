package cn.disasterguard.ruleengine.repository;

import cn.disasterguard.ruleengine.entity.FieldDict;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FieldDictRepository extends JpaRepository<FieldDict, Long> {

  Optional<FieldDict> findByField(String field);

  List<FieldDict> findBySource(String source);

  List<FieldDict> findByType(String type);

  List<FieldDict> findAllByOrderByField();
}
