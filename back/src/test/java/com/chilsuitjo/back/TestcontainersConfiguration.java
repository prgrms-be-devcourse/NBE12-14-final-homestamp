package com.chilsuitjo.back;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트 DB(postgres). 컨테이너는 <b>JVM당 하나</b>이고 모든 테스트 컨텍스트가 같이 쓴다(#319).
 *
 * <p>테스트마다 설정(테스트 시계·{@code @MockitoSpyBean}·{@code properties})이 달라 컨텍스트가 여러 번 뜨는데, 예전에는 그때마다
 * 컨테이너를 새로 켜고 Flyway를 처음부터 돌려 테스트 시간의 절반을 썼다. 같이 쓰면 두 번째 컨텍스트부터는 Flyway가 이미 맞는 스키마를 확인만 한다.
 *
 * <p>같은 DB를 여러 컨텍스트가 쓰므로 테스트는 다른 테스트가 남긴 행이 있어도 깨지지 않아야 한다(테스트마다 새 호실을 만들거나, 표 전체를 세는 테스트는 시작 전에 그
 * 표를 비운다). 여러 JVM으로 나눠 돌리면({@code maxParallelForks}) JVM마다 컨테이너가 따로라 JVM끼리는 섞이지 않는다.
 *
 * <p>컨테이너는 이 클래스를 처음 읽을 때 켜고, 끄는 것은 JVM이 끝날 때 Testcontainers(Ryuk)가 맡는다. 한 컨텍스트가 닫혀도 다른 컨텍스트가 쓰고 있을
 * 수 있어 빈이 끄지 않게 한다({@code destroyMethod = ""}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  /**
   * 동시 접속 한도. 컨텍스트마다 접속 풀(Hikari 기본 10개)을 잡아서, 기본값 100이면 컨텍스트가 열 개를 넘을 때 {@code too many clients}로
   * 접속을 기다리다 멈춘다. 컨텍스트 캐시 한도(32개) × 10에 여유를 둔다.
   */
  private static final int MAX_CONNECTIONS = 400;

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))
          .withCommand("postgres", "-c", "max_connections=" + MAX_CONNECTIONS);

  static {
    POSTGRES.start();
  }

  @Bean(destroyMethod = "")
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    return POSTGRES;
  }
}
