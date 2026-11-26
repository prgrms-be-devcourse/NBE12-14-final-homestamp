package com.chilsuitjo.back.global.config.ssm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

  @Test
  void 권한_거절은_오류_코드와_상태만_남긴다() {
    SsmConfigException error =
        failWith(
            software.amazon.awssdk.services.ssm.model.SsmException.builder()
                .message("User arn:aws:iam::123:role/x not authorized fake-secret")
                .statusCode(400)
                .awsErrorDetails(
                    software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                        .errorCode("AccessDeniedException")
                        .errorMessage("fake-secret")
                        .build())
                .build());

    assertEquals(
        "운영 앱 SSM 설정 조회 실패 - 권한 또는 자격 증명 거절(IAM·KMS 정책 확인): code=AccessDeniedException, status=400",
        error.getMessage());
    assertSafe(error);
  }

  @Test
  void KMS_키_오류를_복호화_오류로_구분한다() {
    SsmConfigException error = failWith(serviceError("InvalidKeyId", 400));
    assertTrue(error.getMessage().contains("복호화 키 오류"), error.getMessage());
    assertSafe(error);
  }

  @Test
  void 알_수_없는_오류_코드는_분류하지_않고_코드만_남긴다() {
    SsmConfigException error = failWith(serviceError("InternalServerError", 500));
    assertEquals(
        "운영 앱 SSM 설정 조회 실패 - AWS 서비스 오류: code=InternalServerError, status=500", error.getMessage());
    SsmConfigException odd = failWith(serviceError("bad code\nfake-secret", 400));
    assertTrue(odd.getMessage().contains("code=알 수 없음"), odd.getMessage());
    assertSafe(odd);
  }

  @Test
  void SDK_클라이언트_오류는_원인_클래스_이름만_남긴다() {
    SsmConfigException error =
        failWith(
            software.amazon.awssdk.core.exception.SdkClientException.create(
                "Unable to load credentials fake-secret",
                new java.net.UnknownHostException("fake-secret.internal")));
    assertEquals(
        "운영 앱 SSM 설정 조회 중 AWS SDK 클라이언트 오류(자격 증명·네트워크 등): SdkClientException <- UnknownHostException",
        error.getMessage());
    assertSafe(error);
  }

  @Test
  void 시도_시간_초과도_시간_초과로_구분한다() {
    SsmConfigException error =
        failWith(
            software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException.create(
                "fake-secret", null));
    assertEquals("운영 앱 SSM 설정 조회 시간이 초과됐다", error.getMessage());
    assertSafe(error);
  }

  @Test
  void 리전과_클라이언트_생성_오류는_일반_오류로_바꾸지_않는다() {
    SsmConfigException region =
        assertThrows(
            SsmConfigException.class,
            () ->
                new AwsSsmParameterSource(
                        () -> {
                          throw new SsmConfigException(
                              "AWS 리전을 확인할 수 없다(AWS_REGION 또는 인스턴스 메타데이터 확인)");
                        })
                    .loadAppParameters());
    assertTrue(region.getMessage().startsWith("AWS 리전을 확인할 수 없다"), region.getMessage());
  }

  private static SsmConfigException failWith(RuntimeException sdkError) {
    SsmClient client = mock(SsmClient.class);
    when(client.getParametersByPath(any(GetParametersByPathRequest.class))).thenThrow(sdkError);
    return assertThrows(
        SsmConfigException.class,
        () -> new AwsSsmParameterSource(() -> client).loadAppParameters());
  }

  private static RuntimeException serviceError(String code, int status) {
    return software.amazon.awssdk.services.ssm.model.SsmException.builder()
        .message("fake-secret")
        .statusCode(status)
        .awsErrorDetails(
            software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                .errorCode(code)
                .errorMessage("fake-secret")
                .build())
        .build();
  }

  private static void assertSafe(SsmConfigException error) {
    org.junit.jupiter.api.Assertions.assertNull(error.getCause());
    java.io.StringWriter trace = new java.io.StringWriter();
    error.printStackTrace(new java.io.PrintWriter(trace));
    org.junit.jupiter.api.Assertions.assertFalse(trace.toString().contains("fake-secret"));
    org.junit.jupiter.api.Assertions.assertFalse(trace.toString().contains("arn:aws"));
  }

  private static Parameter parameter(String leaf, String value, ParameterType type) {
    return Parameter.builder()
        .name(AwsSsmParameterSource.APP_PATH + leaf)
        .value(value)
        .type(type)
        .build();
  }
}
