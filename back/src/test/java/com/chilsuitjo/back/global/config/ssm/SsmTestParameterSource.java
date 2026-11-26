package com.chilsuitjo.back.global.config.ssm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory source for Spring context tests; it never creates an AWS client. */
public final class SsmTestParameterSource implements SsmParameterSource {

  private static volatile List<SsmConfigParameter> parameters = List.of();
  private static final AtomicInteger calls = new AtomicInteger();
  private static volatile RuntimeException failure;

  /** A readable placeholder file: the mapper checks that sslrootcert is a readable regular file. */
  public static final String ROOT_CERT = createRootCertPlaceholder();

  private static String createRootCertPlaceholder() {
    try {
      Path file = Files.createTempFile("homestamp-test-root", ".crt");
      file.toFile().deleteOnExit();
      return file.toRealPath().toString();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

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

  /**
   * The full production contract with fake values. This is the one list tests use: a PR that adds a
   * production setting adds it here, to {@code SsmAppParameterMapper} and to docs/rules/04 6장.
   *
   * <p>The five keys that test {@code config/application.yml} also sets use the same values as that
   * file. It ranks above the SSM import, and {@code SsmProdSettingsGuard} stops prod when any
   * source resolves a value different from SSM.
   */
  public static List<SsmConfigParameter> validParameters() {
    String prefix = SsmAppParameterMapper.PARAMETER_PREFIX;
    return List.of(
        new SsmConfigParameter(
            prefix + "spring.datasource.url",
            "jdbc:postgresql://db.example:5432/homestamp?sslmode=verify-full&sslrootcert="
                + ROOT_CERT,
            "String"),
        new SsmConfigParameter(prefix + "spring.datasource.username", "app-user", "SecureString"),
        new SsmConfigParameter(
            prefix + "spring.datasource.password", "db-password-secret", "SecureString"),
        new SsmConfigParameter(
            prefix + "app.keys.verify-code", "test-only-verify-code-key", "SecureString"),
        new SsmConfigParameter(
            prefix + "app.keys.verify-ticket", "test-only-verify-ticket-key", "SecureString"),
        new SsmConfigParameter(
            prefix + "app.keys.phone-hmac", "test-only-phone-hmac-key", "SecureString"),
        new SsmConfigParameter(
            prefix + "app.keys.upload-ticket",
            "test-only-photo-upload-ticket-signing-key",
            "SecureString"),
        new SsmConfigParameter(
            prefix + "app.keys.link-token",
            "test-only-link-token-hmac-key-32bytes",
            "SecureString"),
        new SsmConfigParameter(
            prefix + "app.keys.access-token",
            "access-token-secret-which-is-long-enough-0001",
            "SecureString"),
        new SsmConfigParameter(
            prefix + "app.keys.refresh-token",
            "refresh-token-secret-which-is-long-enough-0002",
            "SecureString"),
        new SsmConfigParameter(prefix + "app.origin", "https://homestamp.example", "String"),
        new SsmConfigParameter(prefix + "photo.s3.bucket", "homestamp-photos-test", "String"));
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
