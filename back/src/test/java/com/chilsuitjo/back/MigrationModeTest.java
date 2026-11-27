package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.config.migration.MigrationSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/** Migration 모드 진입·인자·프로필·종료 코드 계약(DB 없음). */
class MigrationModeTest {

  static final String PASSWORD = "MIGRATION-TEST-FAKE-PASSWORD";

  @TempDir Path root;
  Path dir;

  @BeforeEach
  void files() throws Exception {
    dir = Files.createDirectory(root.resolve("homestamp-migration"));
    Files.writeString(dir.resolve("spring.datasource.url"), MigrationSettingsOverrideTest.URL);
    Files.writeString(dir.resolve("spring.datasource.username"), "homestamp_migration");
    Files.writeString(dir.resolve("spring.datasource.password"), PASSWORD);
    Files.writeString(dir.resolve("app.migration.owner-role"), "homestamp_owner");
  }

  MigrationMode.Prepared prepare(String... args) {
    return MigrationMode.prepare(args, new Properties(), new MigrationSettings(dir));
  }

  @Test
  void 인자나_Migration_프로필이_있으면_Migration_모드로_간다() {
    assertThat(MigrationMode.isRequested(new String[] {"--migrate"})).isTrue();
    assertThat(MigrationMode.isRequested(new String[] {"--spring.profiles.active=migrate"}))
        .isTrue();
    assertThat(MigrationMode.isRequested(new String[] {"--spring.profiles.active", "migration"}))
        .isTrue();
    assertThat(MigrationMode.isRequested(new String[] {"--spring.profiles.active=prod"})).isFalse();
    assertThat(MigrationMode.isRequested(new String[] {})).isFalse();
  }

  @Test
  void 프로필만으로_들어오면_앱으로_돌아가지_않고_실패한다() {
    // 예전에는 프로필만으로 실행됐다. 지금은 --migrate 하나만 받으므로 Migration 모드에서 실패(앱으로 넘어가지 않음)
    assertThatThrownBy(() -> prepare("--spring.profiles.active=migrate"))
        .hasMessage("Migration 실행 인자는 --migrate 하나만 받는다");
  }

  @Test
  void 앱_프로필을_섞으면_실패한다() {
    for (String profiles : new String[] {"prod,migrate", "prod,migration", "test,migrate"}) {
      assertThatThrownBy(() -> prepare("--migrate", "--spring.profiles.active=" + profiles))
          .hasMessage("Migration 실행 인자는 --migrate 하나만 받는다");
    }
  }

  @Test
  @ResourceLock("system-properties")
  void 시스템_속성으로_앱_프로필을_섞어도_실패한다() {
    String saved = System.getProperty("spring.profiles.active");
    System.setProperty("spring.profiles.active", "prod");
    try {
      assertThatThrownBy(() -> prepare("--migrate")).hasMessage("Migration 모드에는 앱 프로필을 함께 쓰지 않는다");
    } finally {
      if (saved == null) {
        System.clearProperty("spring.profiles.active");
      } else {
        System.setProperty("spring.profiles.active", saved);
      }
    }
  }

  @Test
  void owner_역할_파일이_없으면_실패한다() throws Exception {
    Files.delete(dir.resolve("app.migration.owner-role"));
    assertThatThrownBy(() -> prepare("--migrate"))
        .hasMessage("필수 Migration 설정이 없다: app.migration.owner-role");
  }

  @Test
  void 정상이면_Hikari_설정은_파일_값과_고정값만_가진다() {
    MigrationMode.Prepared prepared = prepare("--migrate");
    assertThat(prepared.hikari().getPoolName()).isEqualTo("homestamp-migration");
    assertThat(prepared.hikari().getDriverClassName()).isEqualTo("org.postgresql.Driver");
    assertThat(prepared.hikari().getMaximumPoolSize()).isEqualTo(2);
    assertThat(prepared.ownerRole()).isEqualTo("homestamp_owner");
    assertThat(prepared.toString()).doesNotContain(PASSWORD);
  }

  @Test
  void apply_grants_인자만으로_GRANT_모드로_가고_다른_인자와_섞으면_실패한다() {
    assertThat(MigrationMode.isRequested(new String[] {"--apply-grants"})).isTrue();
    assertThat(prepare("--apply-grants").operation())
        .isEqualTo(MigrationMode.Operation.APPLY_GRANTS);
    assertThat(prepare("--migrate").operation()).isEqualTo(MigrationMode.Operation.MIGRATE);
    for (String[] args :
        new String[][] {
          {"--migrate", "--apply-grants"},
          {"--apply-grants", "--migrate"},
          {"--apply-grants", "--apply-grants"},
          {"--apply-grants", "--debug"},
          {"--apply-grants", "--spring.profiles.active=prod"}
        }) {
      assertThatThrownBy(() -> prepare(args))
          .as(String.join(" ", args))
          .hasMessage("GRANT 실행 인자는 --apply-grants 하나만 받는다");
    }
  }

  @Test
  @ResourceLock("system-properties")
  void apply_grants도_시스템_속성의_앱_프로필과_섞으면_실패한다() {
    String saved = System.getProperty("spring.profiles.active");
    System.setProperty("spring.profiles.active", "prod");
    try {
      assertThatThrownBy(() -> prepare("--apply-grants"))
          .hasMessage("Migration 모드에는 앱 프로필을 함께 쓰지 않는다");
    } finally {
      if (saved == null) {
        System.clearProperty("spring.profiles.active");
      } else {
        System.setProperty("spring.profiles.active", saved);
      }
    }
  }

  @Test
  void apply_grants_실패는_GRANT_문구로_종료_코드_1이다() {
    String error =
        executeAndCaptureError(
            new String[] {"--apply-grants", "--debug"}, new MigrationSettings(dir), 1);
    assertThat(error).isEqualTo("GRANT를 실행하지 못했다: GRANT 실행 인자는 --apply-grants 하나만 받는다");
    // DB에 닿지 못해도 주소·계정·비밀번호는 남지 않는다
    String unreachable =
        executeAndCaptureError(new String[] {"--apply-grants"}, new MigrationSettings(dir), 1);
    assertThat(unreachable).startsWith("GRANT를 실행하지 못했다: ");
    assertThat(unreachable).doesNotContain(PASSWORD, "homestamp_migration", "127.0.0.1", "jdbc:");
  }

  @Test
  void 이미지의_역할_설정은_운영_규칙을_통과하고_Owner와_Migration_로그인과_겹치지_않는다() {
    var roles =
        com.chilsuitjo.back.global.config.migration.GrantRoles.fromImage(
            "homestamp_owner", "homestamp_migration");
    assertThat(roles.app()).isEqualTo("homestamp_app");
    assertThat(roles.purge()).isEqualTo("homestamp_purge");
    assertThat(roles.human()).isEqualTo("homestamp_human_read");
  }

  /** execute가 남긴 오류 로그 한 줄(실패 원인)을 가로챈다. */
  static String executeAndCaptureError(
      String[] args, MigrationSettings settings, int expectedExit) {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MigrationMode.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      assertThat(MigrationMode.execute(args, settings)).isEqualTo(expectedExit);
    } finally {
      logger.detachAppender(appender);
    }
    return String.join(
        "\n",
        appender.list.stream()
            .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
            .toList());
  }

  @Test
  void 설정_디렉터리가_없으면_종료_코드_1과_원인을_남긴다() {
    String error =
        executeAndCaptureError(
            new String[] {"--migrate"}, new MigrationSettings(root.resolve("missing")), 1);
    assertThat(error).isEqualTo("Migration을 실행하지 못했다: Migration 설정 디렉터리가 없다");
  }

  @Test
  void 추가_인자가_있으면_파일을_읽기_전에_종료_코드_1이다() {
    String error =
        executeAndCaptureError(
            new String[] {"--migrate", "--debug"}, new MigrationSettings(dir), 1);
    assertThat(error).isEqualTo("Migration을 실행하지 못했다: Migration 실행 인자는 --migrate 하나만 받는다");
  }

  @Test
  void DB에_닿지_못하면_종료_코드_1이고_값은_남기지_않는다() {
    // 127.0.0.1:1은 열려 있지 않다. 실패 원인은 예외 종류만 남고 주소·계정·비밀번호는 남지 않는다
    String error =
        executeAndCaptureError(new String[] {"--migrate"}, new MigrationSettings(dir), 1);
    assertThat(error).startsWith("Migration을 실행하지 못했다: ");
    assertThat(error).doesNotContain(PASSWORD, "homestamp_migration", "127.0.0.1", "jdbc:");
  }
}
