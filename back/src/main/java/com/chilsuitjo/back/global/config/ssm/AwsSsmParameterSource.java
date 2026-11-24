package com.chilsuitjo.back.global.config.ssm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
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
          throw new IllegalStateException("SSM 앱 설정 페이지 토큰이 반복됐다");
        }
      } while (nextToken != null && !nextToken.isBlank());
    } catch (ApiCallTimeoutException exception) {
      throw new IllegalStateException("운영 앱 SSM 설정 조회 시간이 초과됐다", null);
    } catch (IllegalStateException exception) {
      if ("SSM 앱 설정 페이지 토큰이 반복됐다".equals(exception.getMessage())) {
        throw exception;
      }
      throw new IllegalStateException("운영 앱 SSM 설정을 불러오지 못했다", null);
    } catch (RuntimeException exception) {
      throw new IllegalStateException("운영 앱 SSM 설정을 불러오지 못했다", null);
    }
    return List.copyOf(parameters);
  }

  private static SsmClient createClient() {
    Region region;
    try {
      region = new DefaultAwsRegionProviderChain().getRegion();
    } catch (RuntimeException exception) {
      throw new IllegalStateException("AWS 리전을 확인할 수 없다", null);
    }
    if (region == null) {
      throw new IllegalStateException("AWS 리전이 설정되지 않았다", null);
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
      throw new IllegalStateException("AWS SSM 클라이언트를 만들 수 없다", null);
    }
  }
}
