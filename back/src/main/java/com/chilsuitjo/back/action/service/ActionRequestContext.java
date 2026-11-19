package com.chilsuitjo.back.action.service;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 지금 pipeline이 처리하는 요청의 행동 시각과 {@code requestId}를 읽는 창구(09 3-1, #84). 그 요청의 {@code
 * StateStore.load}·{@code save}나 그 안에서 동기로 도는 {@code @EventListener}에서 읽는다.
 *
 * <p>응답 요청의 기한 시작, 통지 시각처럼 행동 기록과 같은 시각이어야 하는 값은 {@code Clock}을 다시 읽지 말고 이 값을 쓴다. 그래야 행동 기록의 {@code
 * created_at}과 어긋나지 않는다(05 2-3 「해시 입력 형식」 시각, 09 3-1 순서 8).
 *
 * <pre>{@code
 * // notification의 save 안에서 응답 요청을 만들 때
 * Instant startedAt = requestContext.at();
 * }</pre>
 *
 * <p>{@code apply}는 이 창구를 쓰지 않는다. 시각은 넘겨받은 {@code ActionEntry.createdAt()}에서 읽는다(순수 함수, 09 2장
 * {@code rule/}). pipeline 밖(커밋 뒤 리스너, 비동기 리스너, 예약 작업 등)에서 읽으면 {@link IllegalStateException}.
 */
@Component
public class ActionRequestContext {

  private record Current(UUID requestId, Instant at) {}

  private final ThreadLocal<Current> current = new ThreadLocal<>();

  /** 이 요청의 서버 시각. 마이크로초로 잘려 있고 행동 기록의 {@code created_at}과 같다. */
  public Instant at() {
    return require().at();
  }

  /** 이 요청으로 남는 행동을 묶는 값(행동 기록의 {@code request_id}, #66). */
  public UUID requestId() {
    return require().requestId();
  }

  private Current require() {
    Current c = current.get();
    if (c == null) {
      throw new IllegalStateException(
          "요청 시각은 pipeline 안(load·save나 그 안에서 동기로 도는 @EventListener)에서만 읽을 수 있어요");
    }
    return c;
  }

  void begin(UUID requestId, Instant at) {
    if (current.get() != null) {
      throw new IllegalStateException("pipeline 안에서 pipeline을 다시 부르지 않아요");
    }
    current.set(new Current(requestId, at));
  }

  void end() {
    current.remove();
  }
}
