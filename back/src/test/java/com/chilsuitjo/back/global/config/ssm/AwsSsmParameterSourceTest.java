package com.chilsuitjo.back.global.config.ssm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterType;

class AwsSsmParameterSourceTest {

  @Test
  void 실제_SSM_클라이언트에_시도_및_전체_호출_시간_상한을_설정한다() {
    try (SsmClient client = AwsSsmParameterSource.createClient(Region.US_EAST_1)) {
      var configuration = client.serviceClientConfiguration().overrideConfiguration();

      assertEquals(Duration.ofSeconds(5), configuration.apiCallAttemptTimeout().orElseThrow());
      assertEquals(Duration.ofSeconds(15), configuration.apiCallTimeout().orElseThrow());
    }
  }

  @Test
  void 앱_경로를_복호화하여_페이지_끝까지_읽는다() {
    SsmClient client = mock(SsmClient.class);
    when(client.getParametersByPath(any(GetParametersByPathRequest.class)))
        .thenReturn(
            GetParametersByPathResponse.builder().nextToken("page-2").build(),
            GetParametersByPathResponse.builder()
                .parameters(
                    parameter(
                        "spring.datasource.url", "jdbc:postgresql://db", ParameterType.STRING),
                    parameter("spring.datasource.password", "secret", ParameterType.SECURE_STRING))
                .build());

    List<SsmConfigParameter> parameters =
        new AwsSsmParameterSource(() -> client).loadAppParameters();

    assertEquals(2, parameters.size());
    assertEquals("/homestamp/prod/app/spring.datasource.url", parameters.getFirst().name());
    assertEquals("SecureString", parameters.getLast().type());
    var requestCaptor = org.mockito.ArgumentCaptor.forClass(GetParametersByPathRequest.class);
    verify(client, org.mockito.Mockito.times(2)).getParametersByPath(requestCaptor.capture());
    assertEquals(AwsSsmParameterSource.APP_PATH, requestCaptor.getAllValues().getFirst().path());
    assertEquals(false, requestCaptor.getAllValues().getFirst().recursive());
    assertEquals(true, requestCaptor.getAllValues().getFirst().withDecryption());
    assertEquals(10, requestCaptor.getAllValues().getFirst().maxResults());
    assertEquals("page-2", requestCaptor.getAllValues().getLast().nextToken());
  }

  @Test
  void 반복된_페이지_토큰은_비밀값을_노출하지_않고_실패한다() {
    SsmClient client = mock(SsmClient.class);
    when(client.getParametersByPath(any(GetParametersByPathRequest.class)))
        .thenReturn(
            GetParametersByPathResponse.builder().nextToken("repeated-token").build(),
            GetParametersByPathResponse.builder().nextToken("repeated-token").build());

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> new AwsSsmParameterSource(() -> client).loadAppParameters());

    assertEquals("SSM 앱 설정 페이지 토큰이 반복됐다", error.getMessage());
    verify(client, org.mockito.Mockito.times(2))
        .getParametersByPath(any(GetParametersByPathRequest.class));
    org.junit.jupiter.api.Assertions.assertEquals(
        false, error.toString().contains("repeated-token"));
  }

  @Test
  void SSM_오류를_비밀값이_없는_실패로_변환한다() {
    SsmClient client = mock(SsmClient.class);
    when(client.getParametersByPath(any(GetParametersByPathRequest.class)))
        .thenThrow(ApiCallTimeoutException.create("simulated timeout with fake-secret", null));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> new AwsSsmParameterSource(() -> client).loadAppParameters());

    assertEquals("운영 앱 SSM 설정 조회 시간이 초과됐다", error.getMessage());
    org.junit.jupiter.api.Assertions.assertNull(error.getCause());
    org.junit.jupiter.api.Assertions.assertEquals(false, error.toString().contains("fake-secret"));
  }

  private static Parameter parameter(String leaf, String value, ParameterType type) {
    return Parameter.builder()
        .name(AwsSsmParameterSource.APP_PATH + leaf)
        .value(value)
        .type(type)
        .build();
  }
}
