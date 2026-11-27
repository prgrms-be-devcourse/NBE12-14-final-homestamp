package com.chilsuitjo.back.global.config.ssm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.Parameter;

/** Reads the production app path through the SDK default credential chain. */
final class AwsSsmParameterSource implements SsmParameterSource {

  static final String APP_PATH = "/homestamp/prod/app/";
  private static final int PAGE_SIZE = 10;
  static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(5);
  static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(15);

  private final Supplier<SsmClient> clientSupplier;

  AwsSsmParameterSource() {
    this(AwsSsmParameterSource::createClient);
  }

  AwsSsmParameterSource(Supplier<SsmClient> clientSupplier) {
    this.clientSupplier = clientSupplier;
  }

  @Override
  public List<SsmConfigParameter> loadAppParameters() {
    List<SsmConfigParameter> parameters = new ArrayList<>();
    Set<String> seenNextTokens = new HashSet<>();
    try (SsmClient client = clientSupplier.get()) {
      String nextToken = null;
      do {
        var requestBuilder =
            GetParametersByPathRequest.builder()
                .path(APP_PATH)
                .recursive(false)
                .withDecryption(true)
                .maxResults(PAGE_SIZE);
        if (nextToken != null) {
          requestBuilder.nextToken(nextToken);
        }
        var response = client.getParametersByPath(requestBuilder.build());
        for (Parameter parameter : response.parameters()) {
          parameters.add(
              new SsmConfigParameter(
                  parameter.name(), parameter.value(), parameter.typeAsString()));
        }
        nextToken = response.nextToken();
        if (nextToken != null && !nextToken.isBlank() && !seenNextTokens.add(nextToken)) {
          throw new SsmConfigException("SSM 앱 설정 페이지 토큰이 반복됐다");
        }
      } while (nextToken != null && !nextToken.isBlank());
    } catch (SsmConfigException exception) {
      throw exception;
    } catch (ApiCallTimeoutException | ApiCallAttemptTimeoutException exception) {
      throw new SsmConfigException("운영 앱 SSM 설정 조회 시간이 초과됐다");
    } catch (AwsServiceException exception) {
      throw new SsmConfigException(describeServiceError(exception));
    } catch (SdkClientException exception) {
      throw new SsmConfigException(
          "운영 앱 SSM 설정 조회 중 AWS SDK 클라이언트 오류(자격 증명·네트워크 등): " + causeNames(exception));
    } catch (RuntimeException exception) {
      throw new SsmConfigException("운영 앱 SSM 설정을 불러오지 못했다: " + causeNames(exception));
    }
    return List.copyOf(parameters);
  }

  private static SsmClient createClient() {
    Region region;
    try {
      region = new DefaultAwsRegionProviderChain().getRegion();
    } catch (RuntimeException exception) {
      throw new SsmConfigException("AWS 리전을 확인할 수 없다(AWS_REGION 또는 인스턴스 메타데이터 확인)");
    }
    if (region == null) {
      throw new SsmConfigException("AWS 리전이 설정되지 않았다");
    }
    return createClient(region);
  }

  static SsmClient createClient(Region region) {
    try {
      return SsmClient.builder()
          .region(region)
          .credentialsProvider(DefaultCredentialsProvider.create())
          .overrideConfiguration(
              ClientOverrideConfiguration.builder()
                  .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)
                  .apiCallTimeout(API_CALL_TIMEOUT)
                  .build())
          .build();
    } catch (RuntimeException exception) {
      throw new SsmConfigException("AWS SSM 클라이언트를 만들 수 없다: " + causeNames(exception));
    }
  }

  private static final java.util.regex.Pattern SAFE_CODE =
      java.util.regex.Pattern.compile("[A-Za-z0-9.]{1,64}");

  /** Error code and HTTP status only; the SDK message can carry request details. */
  static String describeServiceError(AwsServiceException exception) {
    String code =
        exception.awsErrorDetails() == null ? null : exception.awsErrorDetails().errorCode();
    String safeCode = code != null && SAFE_CODE.matcher(code).matches() ? code : "알 수 없음";
    String kind =
        switch (safeCode) {
          case "AccessDeniedException", "UnrecognizedClientException", "ExpiredTokenException" ->
              "권한 또는 자격 증명 거절(IAM·KMS 정책 확인)";
          case "InvalidKeyId" -> "복호화 키 오류(KMS 키 확인)";
          case "ThrottlingException" -> "요청 제한";
          default -> "AWS 서비스 오류";
        };
    return "운영 앱 SSM 설정 조회 실패 - "
        + kind
        + ": code="
        + safeCode
        + ", status="
        + exception.statusCode();
  }

  /** Simple class names of the cause chain (at most 4); never messages. */
  static String causeNames(Throwable exception) {
    List<String> names = new ArrayList<>();
    for (Throwable t = exception; t != null && names.size() < 4; t = t.getCause()) {
      names.add(t.getClass().getSimpleName());
      if (t.getCause() == t) {
        break;
      }
    }
    return String.join(" <- ", names);
  }
}
