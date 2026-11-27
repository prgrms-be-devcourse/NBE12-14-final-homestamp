package com.chilsuitjo.back;

import com.chilsuitjo.back.global.config.migration.MigrationSettings;
import java.nio.file.Path;

/** 별도 JVM에서 환경변수가 걸린 상태로 Migration 설정 객체를 만들고 비교 결과만 출력한다(값은 출력하지 않음). */
final class MigrationSettingsOverrideProbe {

  public static void main(String[] args) {
    boolean envSeen =
        System.getenv("SPRING_DATASOURCE_HIKARI_JDBC_URL") != null
            && System.getenv("SPRING_APPLICATION_JSON") != null
            && System.getenv("FLYWAY_LOCATIONS") != null;
    try {
      MigrationMode.Prepared prepared =
          MigrationMode.prepare(
              new String[] {"--migrate"},
              System.getProperties(),
              new MigrationSettings(Path.of(args[0])));
      String expectedUrl =
          java.nio.file.Files.readString(Path.of(args[0]).resolve("spring.datasource.url"));
      MigrationSettingsOverrideTest.assertTrusted(prepared, expectedUrl);
      System.out.println("env_seen=" + envSeen + " trusted=true");
    } catch (Throwable failure) {
      System.out.println("env_seen=" + envSeen + " trusted=false " + failure.getClass().getName());
      System.exit(2);
    }
  }
}
