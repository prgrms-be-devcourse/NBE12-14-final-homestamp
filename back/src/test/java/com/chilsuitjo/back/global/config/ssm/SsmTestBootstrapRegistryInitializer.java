package com.chilsuitjo.back.global.config.ssm;

import org.springframework.boot.bootstrap.BootstrapRegistry;
import org.springframework.boot.bootstrap.BootstrapRegistryInitializer;

/** Replaces production AWS access with an in-memory source in tests. */
public final class SsmTestBootstrapRegistryInitializer implements BootstrapRegistryInitializer {

  @Override
  public void initialize(BootstrapRegistry registry) {
    registry.registerIfAbsent(SsmParameterSource.class, ignored -> new SsmTestParameterSource());
  }
}
