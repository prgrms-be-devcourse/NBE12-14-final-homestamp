package com.chilsuitjo.back.action.service;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 지금 pipeline이 처리하는 요청의 행동 시각과 {@code requestId}를 읽는 창구(09 3-1, #84). 그 요청의 {@code
 * StateStore.save}나 그 안에서 동기로 도는 {@code @EventListener}에서만 읽는다. pipeline은 {@code save}를 부르는 동안에만 이
 * 창구를 연다.
 *
 * <p>응답 요청의 기한 시작, 통지 시각처럼 행동 기록과 같은 시각이어야 하는 값은 {@code Clock}을 다시 읽지 말고 이 값을 쓴다. 그래야 행동 기록의 {@code
 * created_at}과 어긋나지 않는다(05 2-3 「해시 입력 형식」 시각, 09 3-1 순서 8).
 *
 * <pre>{@code
 * // notification의 save 안에서 응답 요청을 만들 때
 * Instant startedAt = requestContext.at();
 * }</pre>
 *
 * <p>{@code load}·{@code when}·{@code apply}에서는 읽을 수 없다({@link IllegalStateException}).
 *
 * <ul>
 *   <li>{@code load}는 같은 키로 다시 온 요청에서도 불리는데, 그때는 새 행동이 없어 시각도 없다
 *   <li>시각에 따른 판단(기한이 지났나 등)은 {@code apply}가 넘겨받은 {@code ActionEntry.createdAt()}으로 한다. 그래야 행동 기록으로
 *       다시 계산해도 같은 결과가 나온다(REP-10, 09 2장 {@code rule/})
 * </ul>
 *
 * <p>pipeline 밖(커밋 뒤 리스너, 비동기 리스너, 예약 작업 등)에서 읽어도 {@link IllegalStateException}.
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
          "요청 시각은 StateStore.save(와 그 안에서 동기로 도는 @EventListener)에서만 읽을 수 있어요");
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
