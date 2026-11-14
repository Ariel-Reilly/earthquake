package cn.disasterguard;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 后端集成测试的 PostgreSQL 基类。
 *
 * <p>历史问题：3 个 {@code @SpringBootTest} 用例依赖本机数据库凭据，
 * 在干净机器 / CI 上直接失败（password authentication failed）。
 * 这里用 Testcontainers 启动一次性 PostGIS 容器（本地镜像
 * {@code postgis/postgis:15-3.4-alpine}），并通过 DynamicPropertySource
 * 覆盖 datasource，测试结束容器自动回收。
 *
 * <p>只作用于集成测试；纯 Mockito 单测不继承本类，不启动容器。
 */
public abstract class PostgresTestBase {

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("postgis/postgis:15-3.4-alpine")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("disasterguard")
          .withUsername("disasterguard")
          .withPassword("disasterguard");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }
}
