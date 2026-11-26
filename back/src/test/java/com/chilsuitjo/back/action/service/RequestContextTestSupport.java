package com.chilsuitjo.back.action.service;

import com.chilsuitjo.back.global.common.Uuids;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * pipeline 없이 저장 코드만 시험할 때 {@code save} 안처럼 행동 시각 창구({@link ActionRequestContext})를 연다. 운영과 같은
 * {@code begin}·{@code end}를 그대로 부르고(창구가 이미 열려 있으면 운영처럼 실패), 일이 끝나면 예외가 나도 닫는다. 운영 코드의 접근 범위는 바꾸지
 * 않으려고 같은 패키지의 테스트 쪽에 둔다.
 *
 * <pre>{@code
 * UUID id = RequestContextTestSupport.within(requestContext, at, () -> inviteLinks.issueInvite(...));
 * }</pre>
 *
 * <p>pipeline 전체(호실 잠금·행동 기록)를 함께 보려면 실제 {@link ActionPipeline}을 쓴다.
 */
public final class RequestContextTestSupport {

  private RequestContextTestSupport() {}

  /** {@code at}을 이 요청의 행동 시각으로 두고 {@code work}를 부른다. */
  public static <T> T within(ActionRequestContext context, Instant at, Supplier<T> work) {
    context.begin(Uuids.v7(), at);
    try {
      return work.get();
    } finally {
      context.end();
    }
  }
}
