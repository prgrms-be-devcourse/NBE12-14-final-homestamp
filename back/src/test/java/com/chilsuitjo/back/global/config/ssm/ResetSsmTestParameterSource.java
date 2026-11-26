package com.chilsuitjo.back.global.config.ssm;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Resets the static {@link SsmTestParameterSource} before and after every test.
 *
 * <p>The source is registered for every Spring context in the test JVM (test {@code
 * spring.factories}), so a prod context started by any other test class adds to {@link
 * SsmTestParameterSource#calls()} and may leave parameters or a failure behind. Resetting only
 * after a test is not enough: the first test of a class would see what earlier classes left.
 * Classes that set parameters or count calls per test use {@code @ExtendWith} with this.
 */
public final class ResetSsmTestParameterSource implements BeforeEachCallback, AfterEachCallback {

  @Override
  public void beforeEach(ExtensionContext context) {
    SsmTestParameterSource.reset();
  }

  @Override
  public void afterEach(ExtensionContext context) {
    SsmTestParameterSource.reset();
  }
}
