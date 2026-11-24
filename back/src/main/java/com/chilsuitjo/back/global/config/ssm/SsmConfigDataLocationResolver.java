package com.chilsuitjo.back.global.config.ssm;

import java.util.List;
import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationNotFoundException;
import org.springframework.boot.context.config.ConfigDataLocationResolver;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.context.config.ConfigDataResourceNotFoundException;

public final class SsmConfigDataLocationResolver
    implements ConfigDataLocationResolver<SsmConfigDataResource> {

  static final String PREFIX = "homestamp-ssm:";

  @Override
  public boolean isResolvable(
      ConfigDataLocationResolverContext context, ConfigDataLocation location) {
    return location.hasPrefix(PREFIX);
  }

  @Override
  public List<SsmConfigDataResource> resolve(
      ConfigDataLocationResolverContext context, ConfigDataLocation location)
      throws ConfigDataLocationNotFoundException, ConfigDataResourceNotFoundException {
    if (!location.getNonPrefixedValue(PREFIX).isEmpty()) {
      throw new IllegalStateException("SSM ConfigData 위치에는 추가 경로를 지정할 수 없다");
    }
    context
        .getBootstrapContext()
        .registerIfAbsent(SsmParameterSource.class, ignored -> new AwsSsmParameterSource());
    return List.of(new SsmConfigDataResource());
  }
}
