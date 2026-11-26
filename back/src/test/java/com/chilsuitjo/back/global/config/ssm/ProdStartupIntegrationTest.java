package com.chilsuitjo.back.global.config.ssm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.BackApplication;
import com.chilsuitjo.back.ProdTestSchema;
import com.chilsuitjo.back.photo.repository.S3UploadPostSigner;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts the real {@link BackApplication} with the prod profile: settings come only from the fake
 * SSM source, and the database is a PostgreSQL container that requires TLS.
 *
 * <p>No AWS call is made. The fake source replaces Parameter Store; the S3 client is created but
 * never used, so no credentials are resolved. It does not use {@code TestcontainersConfiguration}:
 * its {@code @ServiceConnection} would replace the datasource settings and hide what prod reads.
 */
@ExtendWith({ResetSsmTestParameterSource.class, OutputCaptureExtension.class})
class ProdStartupIntegrationTest {

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));

  @TempDir static Path tlsDir;

  static Path trustedRoot;
  static Path otherRoot;

  @BeforeAll
  static void startDatabase() throws Exception {
    TestCertificate server = TestCertificate.generate(tlsDir.resolve("server"));
    trustedRoot = server.root();
    otherRoot = TestCertificate.generate(tlsDir.resolve("other")).root();
    POSTGRES.withCopyToContainer(Transferable.of(server.certificatePem(), 0644), "/tls/server.crt");
    POSTGRES.withCopyToContainer(Transferable.of(server.privateKeyPem(), 0600), "/tls/server.key");
    // The image entrypoint starts as root: hand the key to postgres with 0600, then start postgres.
    POSTGRES.withCreateContainerCmdModifier(cmd ->
        cmd.withEntrypoint("sh", "-c")
            .withCmd(
                "mkdir -p /tmp/pgtls && cp /tls/server.crt /tls/server.key /tmp/pgtls/"
                    + " && chown -R postgres:postgres /tmp/pgtls && chmod 600"
                    + " /tmp/pgtls/server.key && exec docker-entrypoint.sh postgres -c"
                    + " fsync=off -c ssl=on -c ssl_cert_file=/tmp/pgtls/server.crt"
                    + " -c ssl_key_file=/tmp/pgtls/server.key"));
    POSTGRES.start();
    // prod 앱은 Flyway를 돌리지 않는다(#173). 배포 순서처럼 스키마를 먼저 적용해 둔다
    ProdTestSchema.migrate(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @Test
  void prod는_SSM_값만으로_시작하고_DB는_verify_full_TLS로_연결한다() {
    List<SsmConfigParameter> parameters = ssmParameters(trustedRoot);
    SsmTestParameterSource.setParameters(parameters);

    try (ConfigurableApplicationContext context = start()) {
      for (SsmConfigParameter parameter : parameters) {
        String key = parameter.name().substring(SsmAppParameterMapper.PARAMETER_PREFIX.length());
        assertThat(context.getEnvironment().getProperty(key)).as(key).isEqualTo(parameter.value());
      }
      HikariDataSource dataSource = (HikariDataSource) context.getBean(DataSource.class);
      assertThat(dataSource.getJdbcUrl()).isEqualTo(value(parameters, "spring.datasource.url"));
      assertThat(dataSource.getUsername()).isEqualTo(POSTGRES.getUsername());
      assertThat(dataSource.getDataSourceProperties()).isEmpty();
      // The server side shows the app's own connection really is TLS.
      assertThat(
              new JdbcTemplate(dataSource)
                  .queryForObject(
                      "SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()", Boolean.class))
          .isTrue();
      S3UploadPostSigner signer = context.getBean(S3UploadPostSigner.class);
      assertThat(ReflectionTestUtils.getField(signer, "bucket"))
          .isEqualTo(value(parameters, "photo.s3.bucket"));
      assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("prod");
      assertThat(SsmTestParameterSource.calls()).isEqualTo(1);
    }
  }

  @Test
  void 신뢰하지_않는_CA의_인증서면_prod는_시작하지_않는다(CapturedOutput output) {
    SsmTestParameterSource.setParameters(ssmParameters(otherRoot));

    assertThatThrownBy(ProdStartupIntegrationTest::start)
        .satisfies(error -> {
          boolean certificateRejected = false;
          for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            certificateRejected |=
                cause instanceof javax.net.ssl.SSLException
                    || cause instanceof java.security.GeneralSecurityException;
          }
          // prod 앱은 Flyway를 돌리지 않아(#173) Hibernate가 처음 연결한다. Hibernate는 JDBC 메타데이터 연결 실패를 WARN으로
          // 남기고
          // "Dialect를 정할 수 없음"으로 멈춰 원인 사슬에 TLS 예외가 남지 않는다. 그때는 같은 실행의 출력에서 인증서 검증 실패를 확인한다
          certificateRejected |=
              output.getAll().contains("SSLHandshakeException")
                  && output.getAll().contains("PKIX path validation failed");
          assertThat(certificateRejected).as("TLS certificate check failed: %s", error).isTrue();
        });
  }

  private static ConfigurableApplicationContext start() {
    SpringApplication application = new SpringApplication(BackApplication.class);
    application.setAdditionalProfiles("prod");
    application.setDefaultProperties(Map.of("spring.main.banner-mode", "off"));
    return application.run("--server.port=0");
  }

  private static List<SsmConfigParameter> ssmParameters(Path root) {
    List<SsmConfigParameter> parameters = new ArrayList<>(SsmTestParameterSource.validParameters());
    String prefix = SsmAppParameterMapper.PARAMETER_PREFIX;
    String url =
        "jdbc:postgresql://"
            + POSTGRES.getHost()
            + ":"
            + POSTGRES.getMappedPort(5432)
            + "/"
            + POSTGRES.getDatabaseName()
            + "?sslmode=verify-full&sslrootcert="
            + root;
    parameters.replaceAll(parameter ->
        switch (parameter.name().substring(prefix.length())) {
          case "spring.datasource.url" -> new SsmConfigParameter(parameter.name(), url, "String");
          case "spring.datasource.username" ->
              new SsmConfigParameter(parameter.name(), POSTGRES.getUsername(), "SecureString");
          case "spring.datasource.password" ->
              new SsmConfigParameter(parameter.name(), POSTGRES.getPassword(), "SecureString");
          default -> parameter;
        });
    return parameters;
  }

  private static String value(List<SsmConfigParameter> parameters, String key) {
    return parameters.stream()
        .filter(p -> p.name().equals(SsmAppParameterMapper.PARAMETER_PREFIX + key))
        .findFirst()
        .orElseThrow()
        .value();
  }

  /** A self-signed server certificate made with the JDK keytool; it is also the trusted root. */
  record TestCertificate(Path root, String certificatePem, String privateKeyPem) {

    static TestCertificate generate(Path directory) throws Exception {
      Files.createDirectories(directory);
      Path keystore = directory.resolve("server.p12");
      String password = "test-keystore-" + System.nanoTime();
      String host = POSTGRES.getHost();
      String san =
          "dns:localhost,ip:127.0.0.1" + (host.matches("[0-9.]+") ? ",ip:" : ",dns:") + host;
      Process process =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                  "-genkeypair",
                  "-alias",
                  "pg",
                  "-keyalg",
                  "RSA",
                  "-keysize",
                  "2048",
                  "-validity",
                  "2",
                  "-dname",
                  "CN=homestamp-prod-startup-test",
                  "-ext",
                  "SAN=" + san,
                  "-keystore",
                  keystore.toString(),
                  "-storetype",
                  "PKCS12",
                  "-storepass",
                  password,
                  "-keypass",
                  password,
                  "-noprompt")
              .redirectErrorStream(true)
              .start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(process.waitFor()).as("keytool: %s", output).isZero();
      KeyStore store = KeyStore.getInstance("PKCS12");
      try (var in = Files.newInputStream(keystore)) {
        store.load(in, password.toCharArray());
      }
      Base64.Encoder pem = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
      String certificate =
          "-----BEGIN CERTIFICATE-----\n"
              + pem.encodeToString(store.getCertificate("pg").getEncoded())
              + "\n-----END CERTIFICATE-----\n";
      String key =
          "-----BEGIN PRIVATE KEY-----\n"
              + pem.encodeToString(store.getKey("pg", password.toCharArray()).getEncoded())
              + "\n-----END PRIVATE KEY-----\n";
      Path root = Files.writeString(directory.resolve("root.crt"), certificate);
      Files.delete(keystore);
      return new TestCertificate(root.toRealPath(), certificate, key);
    }
  }
}
