package com.chilsuitjo.back;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Starts only the database and Flyway context when the executable JAR receives {@code --migrate}.
 */
public final class MigrationMode {

  private static final String MIGRATE_ARGUMENT = "--migrate";
  private static final String MIGRATE_PROFILE = "migrate";
  private static final String LEGACY_MIGRATION_PROFILE = "migration";
  private static final String MIGRATION_CONFIG_LOCATION =
      "--spring.config.location=optional:classpath:/migration.properties";
  private static final List<String> FORCED_MIGRATION_SETTINGS =
      List.of(
          MIGRATION_CONFIG_LOCATION,
          "--spring.config.additional-location=",
          "--spring.config.import=homestamp-ssm:migration",
          "--spring.profiles.active=migrate",
          "--spring.profiles.include=",
          "--spring.flyway.enabled=true",
          "--spring.flyway.locations=classpath:db/migration");

  private MigrationMode() {}

  public static boolean isRequested(String[] args) {
    return Arrays.asList(args).contains(MIGRATE_ARGUMENT)
        || requestedProfileNames(args).stream().anyMatch(MigrationMode::isMigrationProfile);
  }

  /** Runs migrations and returns a process exit code without terminating the caller's JVM. */
  public static int execute(String[] args) {
    try (ConfigurableApplicationContext ignored = start(args)) {
      return 0;
    } catch (RuntimeException exception) {
      return 1;
    }
  }

  static ConfigurableApplicationContext start(String[] args) {
    if (!isRequested(args)) {
      throw new IllegalArgumentException(
          "Migration mode requires the explicit --migrate argument.");
    }
    rejectIncompatibleProfiles(args);
    rejectMigrationSettingOverrides(args);
    rejectSeparateFlywayConnection(args);
    SpringApplication application = new SpringApplication(MigrationConfiguration.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setRegisterShutdownHook(false);
    return application.run(sanitizedArguments(args));
  }

  private static void rejectMigrationSettingOverrides(String[] args) {
    rejectMigrationSettingOverrides(System.getenv(), System.getProperties(), args);
  }

  static void rejectMigrationSettingOverrides(
      Map<String, String> environment, Properties systemProperties, String[] args) {
    List<String> settingOverrides =
        List.of(
            "SPRING_DATASOURCE_URL",
            "SPRING_DATASOURCE_USERNAME",
            "SPRING_DATASOURCE_PASSWORD",
            "APP_MIGRATION_OWNER_ROLE");
    if (settingOverrides.stream().anyMatch(name -> isPresent(environment.get(name)))) {
      throw new IllegalArgumentException(
          "Migration database and owner settings must come from the Migration SSM path.");
    }

    List<String> propertyNames =
        List.of(
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "app.migration.owner-role");
    if (propertyNames.stream().anyMatch(name -> isPresent(systemProperties.getProperty(name)))) {
      throw new IllegalArgumentException(
          "Migration database and owner settings must come from the Migration SSM path.");
    }

    for (String argument : args) {
      if (argument.startsWith("--spring.datasource.url")
          || argument.startsWith("--spring.datasource.username")
          || argument.startsWith("--spring.datasource.password")
          || argument.startsWith("--app.migration.owner-role")) {
        throw new IllegalArgumentException(
            "Migration database and owner settings must come from the Migration SSM path.");
      }
    }
  }

  private static boolean isPresent(String value) {
    return value != null;
  }

  private static String[] sanitizedArguments(String[] args) {
    List<String> result = new ArrayList<>();
    for (int index = 0; index < args.length; index++) {
      String argument = args[index];
      if (argument.equals(MIGRATE_ARGUMENT)) {
        continue;
      }
      if (argument.startsWith("--spring.config.")
          || argument.startsWith("--spring.profiles.")
          || argument.startsWith("--spring.flyway.")) {
        if (!argument.contains("=")
            && index + 1 < args.length
            && !args[index + 1].startsWith("--")) {
          index++;
        }
        continue;
      }
      result.add(argument);
    }
    result.addAll(FORCED_MIGRATION_SETTINGS);
    return result.toArray(String[]::new);
  }

  private static List<String> requestedProfileNames(String[] args) {
    List<String> profileSources = new ArrayList<>();
    profileSources.add(System.getenv("SPRING_PROFILES_ACTIVE"));
    profileSources.add(System.getProperty("spring.profiles.active"));
    for (int index = 0; index < args.length; index++) {
      String argument = args[index];
      if (argument.startsWith("--spring.profiles.active=")) {
        profileSources.add(argument.substring("--spring.profiles.active=".length()));
      } else if (argument.equals("--spring.profiles.active") && index + 1 < args.length) {
        profileSources.add(args[index + 1]);
      }
    }
    return profileSources.stream()
        .filter(value -> value != null && !value.isBlank())
        .flatMap(value -> Arrays.stream(value.split(",")))
        .map(String::trim)
        .map(profile -> profile.toLowerCase(Locale.ROOT))
        .toList();
  }

  private static boolean isMigrationProfile(String profile) {
    return profile.equals(MIGRATE_PROFILE) || profile.equals(LEGACY_MIGRATION_PROFILE);
  }

  private static void rejectIncompatibleProfiles(String[] args) {
    List<String> incompatibleProfiles =
        requestedProfileNames(args).stream()
            .filter(profile -> !isMigrationProfile(profile))
            .toList();
    if (!incompatibleProfiles.isEmpty()) {
      throw new IllegalArgumentException(
          "Migration mode accepts only the migrate profile; do not combine it with application profiles.");
    }
  }

  private static void rejectSeparateFlywayConnection(String[] args) {
    List<String> separateConnectionValues = new ArrayList<>();
    separateConnectionValues.add(System.getenv("SPRING_FLYWAY_URL"));
    separateConnectionValues.add(System.getenv("SPRING_FLYWAY_USER"));
    separateConnectionValues.add(System.getenv("SPRING_FLYWAY_PASSWORD"));
    separateConnectionValues.add(System.getProperty("spring.flyway.url"));
    separateConnectionValues.add(System.getProperty("spring.flyway.user"));
    separateConnectionValues.add(System.getProperty("spring.flyway.password"));
    for (int index = 0; index < args.length; index++) {
      String argument = args[index];
      if (argument.startsWith("--spring.flyway.url=")) {
        separateConnectionValues.add(argument.substring("--spring.flyway.url=".length()));
      } else if (argument.equals("--spring.flyway.url") && index + 1 < args.length) {
        separateConnectionValues.add(args[index + 1]);
      } else if (argument.startsWith("--spring.flyway.user=")) {
        separateConnectionValues.add(argument.substring("--spring.flyway.user=".length()));
      } else if (argument.equals("--spring.flyway.user") && index + 1 < args.length) {
        separateConnectionValues.add(args[index + 1]);
      } else if (argument.startsWith("--spring.flyway.password=")) {
        separateConnectionValues.add(argument.substring("--spring.flyway.password=".length()));
      } else if (argument.equals("--spring.flyway.password") && index + 1 < args.length) {
        separateConnectionValues.add(args[index + 1]);
      }
    }
    if (separateConnectionValues.stream().anyMatch(value -> value != null && !value.isBlank())) {
      throw new IllegalArgumentException(
          "Migration mode uses only the configured migration DataSource connection.");
    }
  }
}
