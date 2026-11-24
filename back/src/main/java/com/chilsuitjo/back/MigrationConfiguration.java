package com.chilsuitjo.back;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** Minimal auto-configuration imports for a one-shot database migration process. */
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
public class MigrationConfiguration {

  @Bean
  FlywayConfigurationCustomizer migrationOwnerRole(Environment environment) {
    String ownerRole = environment.getRequiredProperty("app.migration.owner-role").trim();
    if (!ownerRole.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
      throw new IllegalStateException("app.migration.owner-role must be a PostgreSQL role name.");
    }
    String quotedRole = "\"" + ownerRole + "\"";
    return (FluentConfiguration configuration) -> configuration.initSql("SET ROLE " + quotedRole);
  }
}
