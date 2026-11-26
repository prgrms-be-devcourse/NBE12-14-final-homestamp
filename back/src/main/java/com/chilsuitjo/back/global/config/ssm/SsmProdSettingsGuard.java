package com.chilsuitjo.back.global.config.ssm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.PropertySource;

/**
 * Checks, after every property source is in place and before any bean is created, that prod really
 * runs on the values read from Parameter Store (docs/rules/04 6장).
 *
 * <p>Spring's precedence is kept as it is. Imported config data ranks below command line arguments,
 * system properties, OS environment variables and {@code config/} files, so any of those can
 * silently replace an SSM value. Instead of reordering, prod refuses to start when:
 *
 * <ul>
 *   <li>the prod profile is active but the SSM source was never loaded (for example {@code
 *       spring.config.location} skipped {@code application.yml} and its import);
 *   <li>the value Spring resolves for an SSM key, the same way {@code @Value} and {@code
 *       ConfigurationProperties} binding resolve it, differs from the SSM value;
 *   <li>any source sets a database connection setting next to the three SSM ones. Hikari and Flyway
 *       settings such as {@code jdbc-url}, {@code data-source-properties.sslfactory} or {@code
 *       spring.flyway.url} would open a connection that bypasses the URL check.
 * </ul>
 *
 * Messages name keys only, never values.
 */
public final class SsmProdSettingsGuard implements EnvironmentPostProcessor, Ordered {

  static final String SSM_SOURCE = "homestampSsmApp";

  private static final ConfigurationPropertyName DATASOURCE =
      ConfigurationPropertyName.of("spring.datasource");

  private static final List<ConfigurationPropertyName> ALLOWED_DATASOURCE =
      List.of(
          ConfigurationPropertyName.of("spring.datasource.url"),
          ConfigurationPropertyName.of("spring.datasource.username"),
          ConfigurationPropertyName.of("spring.datasource.password"));

  /** Flyway settings that make Flyway open its own connection instead of the app DataSource. */
  private static final List<ConfigurationPropertyName> FLYWAY_CONNECTION =
      List.of(
          ConfigurationPropertyName.of("spring.flyway.url"),
          ConfigurationPropertyName.of("spring.flyway.user"),
          ConfigurationPropertyName.of("spring.flyway.password"),
          ConfigurationPropertyName.of("spring.flyway.driver-class-name"),
          ConfigurationPropertyName.of("spring.flyway.jdbc-properties"));

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (!environment.acceptsProfiles(Profiles.of("prod"))) {
      return;
    }
    PropertySource<?> ssm = environment.getPropertySources().get(SSM_SOURCE);
    if (!(ssm instanceof EnumerablePropertySource<?> ssmSource)) {
      throw new SsmConfigException("prod 프로필인데 SSM 앱 설정을 불러오지 않았다");
    }
    Binder binder = Binder.get(environment);
    for (String key : new TreeSet<>(List.of(ssmSource.getPropertyNames()))) {
      Object expected = ssmSource.getProperty(key);
      String resolved = environment.getProperty(key);
      String bound = binder.bind(key, String.class).orElse(null);
      if (!Objects.equals(expected, resolved) || !Objects.equals(expected, bound)) {
        throw new SsmConfigException("운영 SSM 설정을 다른 설정 출처가 덮었다: " + key);
      }
    }
    List<String> forbidden = new ArrayList<>();
    for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
      if (!(source instanceof IterableConfigurationPropertySource iterable)) {
        continue;
      }
      iterable.stream()
          .filter(SsmProdSettingsGuard::isForbidden)
          .map(ConfigurationPropertyName::toString)
          .forEach(forbidden::add);
    }
    if (!forbidden.isEmpty()) {
      throw new SsmConfigException("운영에서는 SSM 밖의 DB 연결 설정을 받지 않는다: " + new TreeSet<>(forbidden));
    }
  }

  private static boolean isForbidden(ConfigurationPropertyName name) {
    if (DATASOURCE.isAncestorOf(name)) {
      return ALLOWED_DATASOURCE.stream().noneMatch(name::equals);
    }
    return FLYWAY_CONNECTION.stream()
        .anyMatch(flyway -> flyway.equals(name) || flyway.isAncestorOf(name));
  }
}
