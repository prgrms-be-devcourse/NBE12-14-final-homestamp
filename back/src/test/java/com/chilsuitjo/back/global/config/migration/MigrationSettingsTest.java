package com.chilsuitjo.back.global.config.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Migration 설정 파일이 정해진 4개 파일만 받고 나머지는 모두 거절하는지(값은 메시지에 나오지 않는지) 본다. */
class MigrationSettingsTest {

  static final String FAKE_PASSWORD = "MIGRATION-TEST-FAKE-PASSWORD";

  @TempDir Path root;
  Path dir;

  @BeforeEach
  void setUp() throws Exception {
    dir = Files.createDirectory(root.resolve("homestamp-migration"));
    rootCert = Files.writeString(root.resolve("rds-ca.crt"), "placeholder").toAbsolutePath();
    write("spring.datasource.url", url("sslmode=verify-full&sslrootcert=" + rootCert) + "\n");
    write("spring.datasource.username", "homestamp_migration");
    write("spring.datasource.password", FAKE_PASSWORD + "\n");
    write("app.migration.owner-role", "homestamp_owner");
  }

  Path rootCert;

  static String url(String query) {
    return "jdbc:postgresql://127.0.0.1:1/homestamp?" + query;
  }

  void write(String name, String value) throws Exception {
    Files.writeString(dir.resolve(name), value, StandardCharsets.UTF_8);
  }

  MigrationSettings.Values load() {
    return new MigrationSettings(dir).load();
  }

  void assertRejected(String messagePart) {
    assertThatThrownBy(this::load)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(messagePart)
        .message()
        .doesNotContain(FAKE_PASSWORD);
  }

  @Test
  void 정상_4개_파일만_읽고_끝의_줄바꿈만_뗀다() {
    MigrationSettings.Values values = load();
    assertThat(values.url()).isEqualTo(url("sslmode=verify-full&sslrootcert=" + rootCert));
    assertThat(values.username()).isEqualTo("homestamp_migration");
    assertThat(values.password()).isEqualTo(FAKE_PASSWORD);
    assertThat(values.ownerRole()).isEqualTo("homestamp_owner");
    assertThat(values.toString()).doesNotContain(FAKE_PASSWORD, "homestamp_migration");
  }

  @Test
  void 디렉터리가_없으면_실패() throws Exception {
    assertThatThrownBy(() -> new MigrationSettings(root.resolve("missing")).load())
        .hasMessageContaining("디렉터리가 없다");
  }

  @Test
  void 디렉터리가_심볼릭_링크면_실패() throws Exception {
    Path link = Files.createSymbolicLink(root.resolve("link-dir"), dir);
    assertThatThrownBy(() -> new MigrationSettings(link).load()).hasMessageContaining("디렉터리가 없다");
  }

  @Test
  void 파일_하나가_없으면_실패() throws Exception {
    Files.delete(dir.resolve("app.migration.owner-role"));
    assertRejected("필수 Migration 설정이 없다");
  }

  @Test
  void 빈_파일이면_실패() throws Exception {
    write("spring.datasource.password", "");
    assertRejected("비어 있다");
  }

  @Test
  void 공백만_있으면_실패() throws Exception {
    write("spring.datasource.password", "   ");
    assertRejected("비어 있다");
  }

  @Test
  void 목록_밖_파일이_있으면_실패() throws Exception {
    write("spring.datasource.hikari.jdbc-url", "jdbc:postgresql://attacker/x");
    assertRejected("허용하지 않는 항목");
  }

  @Test
  void 숨김_파일이_있어도_실패() throws Exception {
    write(".hidden", "x");
    assertRejected("허용하지 않는 항목");
  }

  @Test
  void 하위_디렉터리가_있으면_실패() throws Exception {
    Files.createDirectory(dir.resolve("spring"));
    assertRejected("허용하지 않는 항목");
  }

  @Test
  void 허용된_이름이어도_디렉터리면_실패() throws Exception {
    Files.delete(dir.resolve("spring.datasource.username"));
    Files.createDirectory(dir.resolve("spring.datasource.username"));
    assertRejected("일반 파일이 아니다");
  }

  @Test
  void 허용된_이름이어도_FIFO면_실패() throws Exception {
    Files.delete(dir.resolve("spring.datasource.username"));
    Process mkfifo =
        new ProcessBuilder("mkfifo", dir.resolve("spring.datasource.username").toString()).start();
    assumeThat(mkfifo.waitFor()).isZero();
    assertRejected("일반 파일이 아니다");
  }

  @Test
  void 디렉터리_밖을_가리키는_심볼릭_링크는_따라가지_않고_실패() throws Exception {
    Path outside = Files.writeString(root.resolve("outside-secret"), "OUTSIDE-TEST-VALUE");
    Files.delete(dir.resolve("spring.datasource.password"));
    Files.createSymbolicLink(dir.resolve("spring.datasource.password"), outside);
    assertThatThrownBy(this::load)
        .hasMessageContaining("일반 파일이 아니다")
        .message()
        .doesNotContain("OUTSIDE-TEST-VALUE");
  }

  @Test
  void 디렉터리_안을_가리키는_심볼릭_링크도_실패() throws Exception {
    Path target = Files.writeString(root.resolve("inside-copy"), FAKE_PASSWORD);
    Files.delete(dir.resolve("spring.datasource.password"));
    Files.createSymbolicLink(dir.resolve("spring.datasource.password"), target);
    assertRejected("일반 파일이 아니다");
  }

  @Test
  void 주소_형식이_틀리면_실패() throws Exception {
    write("spring.datasource.url", "jdbc:mysql://127.0.0.1/homestamp");
    assertRejected("주소 형식");
  }

  @Test
  void 주소에_호스트가_없으면_실패() throws Exception {
    // pgjdbc는 호스트가 비면 PGHOST 같은 기본값으로 채울 수 있다
    write("spring.datasource.url", "jdbc:postgresql:///homestamp");
    assertRejected("주소 형식");
  }

  @Test
  void owner_역할_이름_형식이_틀리면_실패() throws Exception {
    write("app.migration.owner-role", "owner\"; DROP ROLE x; --");
    assertRejected("식별자 형식");
  }

  @Test
  void owner_역할은_bootstrap과_같은_소문자_규칙만_받는다() throws Exception {
    for (String bad :
        new String[] {"Homestamp_Owner", "pg_owner", "1owner", "owner-x", "\"owner\""}) {
      write("app.migration.owner-role", bad);
      assertRejected("owner-role 식별자 형식");
    }
  }

  @Test
  void 주소에_추가_경로나_공백이_있으면_실패() throws Exception {
    for (String bad :
        new String[] {
          "jdbc:postgresql://db:5432/a/b", "jdbc:postgresql://db:5432/a b", "jdbc:mysql://db/a"
        }) {
      write("spring.datasource.url", bad);
      assertRejected("주소 형식");
    }
  }

  // ---- JDBC URL: pgjdbc는 URL 속성을 파일의 user·password보다 우선하므로 쿼리는 TLS 두 키만 받는다

  void assertUrlRejected(String query, String messagePart) throws Exception {
    write("spring.datasource.url", url(query));
    assertThatThrownBy(this::load)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(messagePart)
        .message()
        .doesNotContain(FAKE_PASSWORD, "127.0.0.1", query);
  }

  @Test
  void URL로_계정이나_비밀번호를_바꾸는_속성은_거절한다() throws Exception {
    String tls = "sslmode=verify-full&sslrootcert=" + rootCert;
    for (String extra :
        new String[] {"user=postgres", "password=x", "USER=postgres", "service=prod"}) {
      assertUrlRejected(tls + "&" + extra, "허용하지 않는 연결 속성");
      assertUrlRejected(extra + "&" + tls, "허용하지 않는 연결 속성");
    }
  }

  @Test
  void 초기화_SQL_소켓_SSL_팩토리_같은_드라이버_옵션은_거절한다() throws Exception {
    String tls = "sslmode=verify-full&sslrootcert=" + rootCert;
    for (String extra :
        new String[] {
          "options=-c role=postgres",
          "socketFactory=x.Y",
          "sslfactory=org.postgresql.ssl.NonValidatingFactory",
          "sslhostnameverifier=x.Y",
          "authenticationPluginClassName=x.Y",
          "loggerFile=/tmp/x",
          "currentSchema=public",
          "ApplicationName=x"
        }) {
      assertUrlRejected(tls + "&" + extra, "허용하지 않는 연결 속성");
    }
  }

  @Test
  void 중복_빈_토큰_등호_없음_빈_값은_거절한다() throws Exception {
    String cert = "sslrootcert=" + rootCert;
    assertUrlRejected("sslmode=verify-full&sslmode=disable&" + cert, "중복");
    assertUrlRejected("sslmode=verify-full&" + cert + "&" + cert, "중복");
    assertUrlRejected("sslmode=verify-full&&" + cert, "형식");
    assertUrlRejected("sslmode=verify-full&" + cert + "&", "형식");
    assertUrlRejected("sslmode&" + cert, "형식");
    assertUrlRejected("sslmode=&" + cert, "형식");
    assertUrlRejected("=verify-full&" + cert, "형식");
  }

  @Test
  void URL_인코딩으로_키나_값을_숨기면_거절한다() throws Exception {
    String cert = "sslrootcert=" + rootCert;
    assertUrlRejected("sslmode=verify%2Dfull&" + cert, "인코딩");
    assertUrlRejected("%73slmode=verify-full&" + cert, "인코딩");
    assertUrlRejected("sslmode=verify-full&" + cert + "&us%65r=postgres", "인코딩");
    assertUrlRejected("sslmode=verify+full&" + cert, "인코딩");
  }

  @Test
  void sslmode는_verify_full만_받는다() throws Exception {
    String cert = "sslrootcert=" + rootCert;
    for (String mode :
        new String[] {
          "disable", "allow", "prefer", "require", "verify-ca", "VERIFY-FULL", "verify-full "
        }) {
      assertUrlRejected("sslmode=" + mode + "&" + cert, "verify-full");
    }
    assertUrlRejected(cert, "verify-full");
  }

  @Test
  void sslrootcert는_읽을_수_있는_절대_경로의_일반_파일이어야_한다() throws Exception {
    String mode = "sslmode=verify-full&";
    assertUrlRejected("sslmode=verify-full", "sslrootcert");
    assertUrlRejected(mode + "sslrootcert=rds-ca.crt", "절대 경로");
    assertUrlRejected(
        mode + "sslrootcert=" + root + "/../" + root.getFileName() + "/rds-ca.crt", "절대 경로");
    assertUrlRejected(mode + "sslrootcert=" + root + "/./rds-ca.crt", "절대 경로");
    assertUrlRejected(mode + "sslrootcert=" + root + "/missing.crt", "읽을 수 없다");
    assertUrlRejected(mode + "sslrootcert=" + root, "읽을 수 없다");
  }

  @Test
  void 실행_사용자가_읽을_수_없는_인증서는_거절한다() throws Exception {
    assumeThat(System.getProperty("user.name")).isNotEqualTo("root");
    Files.setPosixFilePermissions(rootCert, PosixFilePermissions.fromString("---------"));
    try {
      assertUrlRejected("sslmode=verify-full&sslrootcert=" + rootCert, "읽을 수 없다");
    } finally {
      Files.setPosixFilePermissions(rootCert, PosixFilePermissions.fromString("rw-------"));
    }
  }

  @Test
  void 값_가운데_줄바꿈이나_제어_문자가_있으면_실패() throws Exception {
    write("spring.datasource.password", FAKE_PASSWORD + "\nsecond-line");
    assertRejected("제어 문자");
  }

  @Test
  void UTF8이_아니면_실패() throws Exception {
    Files.write(dir.resolve("spring.datasource.username"), new byte[] {(byte) 0xC3, (byte) 0x28});
    assertRejected("UTF-8이 아니다");
  }

  @Test
  void 너무_크면_실패() throws Exception {
    write("spring.datasource.password", "a".repeat(5000));
    assertRejected("너무 크다");
  }

  @Test
  void 파일_읽기_권한이_없으면_실패() throws Exception {
    assumeThat(System.getProperty("user.name")).isNotEqualTo("root");
    Path file = dir.resolve("spring.datasource.password");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
    try {
      assertRejected("읽을 권한이 없다");
    } finally {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
  }

  @Test
  void 디렉터리_읽기_권한이_없으면_실패() throws Exception {
    assumeThat(System.getProperty("user.name")).isNotEqualTo("root");
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("---------"));
    try {
      assertRejected("권한이 없다");
    } finally {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    }
  }
}
