package com.chilsuitjo.back;

import org.flywaydb.core.Flyway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * prod 프로필 앱은 Flyway를 돌리지 않는다(#173, 운영은 {@code --migrate}가 성공한 뒤 앱을 시작). 그래서 prod 컨텍스트를 띄우는 테스트는 배포
 * 순서처럼 스키마를 먼저 적용한다. 설정은 {@code application.yml}의 {@code spring.flyway} 값(위치·기본 스키마·스키마 목록)과 같다. 운영
 * Migration의 계정·TLS·Owner 전환 계약은 {@code MigrationRunnerIntegrationTest}가 따로 검증한다.
 */
public final class ProdTestSchema {

  private ProdTestSchema() {}

  /** 빈 DB에 {@code db/migration}을 적용한다. */
  public static void migrate(String url, String username, String password) {
    Flyway.configure()
        .dataSource(url, username, password)
        .locations("classpath:/db/migration")
        .defaultSchema("public")
        .schemas("public", "record", "state", "log")
        .load()
        .migrate();
  }

  /** {@link TestcontainersConfiguration}과 같은 DB를 만들되, 앱이 붙기 전에 스키마를 적용해 둔다. prod 컨텍스트 테스트용. */
  @TestConfiguration(proxyBeanMethods = false)
  public static class MigratedDatabase {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
      PostgreSQLContainer container =
          new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));
      container.start();
      migrate(container.getJdbcUrl(), container.getUsername(), container.getPassword());
      return container;
    }
  }
}
