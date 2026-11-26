package com.chilsuitjo.back.global.config.ssm;

import java.io.IOException;
import java.util.List;
import org.apache.commons.logging.Log;
import org.springframework.boot.context.config.ConfigData;
import org.springframework.boot.context.config.ConfigDataLoader;
import org.springframework.boot.context.config.ConfigDataLoaderContext;
import org.springframework.boot.logging.DeferredLogFactory;

public final class SsmConfigDataLoader implements ConfigDataLoader<SsmConfigDataResource> {

  private final Log log;

  /** Spring Boot passes a deferred log factory; the log is replayed once logging is ready. */
  public SsmConfigDataLoader(DeferredLogFactory logFactory) {
    this.log = logFactory.getLog(SsmConfigDataLoader.class);
  }

  @Override
  public ConfigData load(ConfigDataLoaderContext context, SsmConfigDataResource resource)
      throws IOException {
    try {
      SsmParameterSource source = context.getBootstrapContext().get(SsmParameterSource.class);
      SsmAppParameterMapper.Mapped mapped = SsmAppParameterMapper.map(source.loadAppParameters());
      if (!mapped.ignoredKeys().isEmpty()) {
        // Names only. The values of ignored parameters were never read.
        log.warn("허용 목록에 없는 SSM 앱 설정을 적용하지 않았다: " + mapped.ignoredKeys());
      }
      return new ConfigData(List.of(new RedactedSsmPropertySource(mapped.properties())));
    } catch (SsmConfigException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new SsmConfigException(
          "운영 앱 SSM 설정을 불러오거나 검증하지 못했다: " + exception.getClass().getSimpleName());
    }
  }
}
