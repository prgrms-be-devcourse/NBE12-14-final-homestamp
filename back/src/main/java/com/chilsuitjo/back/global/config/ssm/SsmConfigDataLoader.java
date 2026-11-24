package com.chilsuitjo.back.global.config.ssm;

import java.io.IOException;
import java.util.List;
import org.springframework.boot.context.config.ConfigData;
import org.springframework.boot.context.config.ConfigDataLoader;
import org.springframework.boot.context.config.ConfigDataLoaderContext;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;

public final class SsmConfigDataLoader implements ConfigDataLoader<SsmConfigDataResource> {

  @Override
  public ConfigData load(ConfigDataLoaderContext context, SsmConfigDataResource resource)
      throws IOException {
    try {
      SsmParameterSource source = context.getBootstrapContext().get(SsmParameterSource.class);
      return new ConfigData(
          List.of(
              new RedactedSsmPropertySource(
                  SsmAppParameterMapper.toProperties(source.loadAppParameters()))));
    } catch (RuntimeException exception) {
      if (exception instanceof ApiCallTimeoutException) {
        throw new IllegalStateException("운영 앱 SSM 설정 조회 시간이 초과됐다");
      }
      if (exception instanceof IllegalStateException stateException
          && isSanitized(stateException.getMessage())) {
        throw stateException;
      }
      throw new IllegalStateException("운영 앱 SSM 설정을 불러오거나 검증하지 못했다");
    }
  }

  private static boolean isSanitized(String message) {
    return message != null
        && (message.startsWith("허용하지 않는 SSM")
            || message.startsWith("필수 SSM")
            || message.startsWith("SSM 앱 설정")
            || message.startsWith("운영 데이터베이스")
            || message.startsWith("운영 화면 주소")
            || message.startsWith("운영 토큰 키")
            || message.startsWith("운영 액세스 토큰")
            || message.startsWith("운영 휴대폰 인증")
            || message.startsWith("AWS 리전")
            || message.startsWith("AWS SSM 클라이언트")
            || message.startsWith("운영 앱 SSM 설정 조회 시간이 초과됐다")
            || message.startsWith("운영 앱 SSM 설정을 불러오지 못했다"));
  }
}
