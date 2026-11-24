package com.chilsuitjo.back.global.config.ssm;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory source for Spring context tests; it never creates an AWS client. */
public final class SsmTestParameterSource implements SsmParameterSource {

  private static volatile List<SsmConfigParameter> parameters = List.of();
  private static final AtomicInteger calls = new AtomicInteger();
  private static volatile RuntimeException failure;

  public static void setParameters(List<SsmConfigParameter> newParameters) {
    parameters = List.copyOf(newParameters);
    failure = null;
  }

  public static void setFailure(RuntimeException newFailure) {
    failure = newFailure;
  }

  public static void reset() {
    parameters = List.of();
    failure = null;
    calls.set(0);
  }

  public static int calls() {
    return calls.get();
  }

  public static List<SsmConfigParameter> validParameters() {
    String prefix = SsmAppParameterMapper.PARAMETER_PREFIX;
    return List.of(
        new SsmConfigParameter(
            prefix + "spring.datasource.url",
            "jdbc:postgresql://db.example:5432/homestamp?sslmode=verify-full",
            "String"),
        new SsmConfigParameter(prefix + "spring.datasource.username", "app-user", "SecureString"),
        new SsmConfigParameter(
            prefix + "spring.datasource.password", "db-password-secret", "SecureString"),
        new SsmConfigParameter(
            prefix + "app.keys.verify-code", "verify-code-secret", "SecureString"),
        new SsmConfigParameter(
            prefix + "app.keys.verify-ticket", "verify-ticket-secret", "SecureString"),
        new SsmConfigParameter(prefix + "app.keys.phone-hmac", "phone-hmac-secret", "SecureString"),
        new SsmConfigParameter(
            prefix + "app.keys.access-token",
            "access-token-secret-which-is-long-enough-0001",
            "SecureString"),
        new SsmConfigParameter(
            prefix + "app.keys.refresh-token",
            "refresh-token-secret-which-is-long-enough-0002",
            "SecureString"),
        new SsmConfigParameter(prefix + "app.origin", "https://homestamp.example", "String"));
  }

  @Override
  public List<SsmConfigParameter> loadAppParameters() {
    calls.incrementAndGet();
    if (failure != null) {
      throw failure;
    }
    return new ArrayList<>(parameters);
  }
}
