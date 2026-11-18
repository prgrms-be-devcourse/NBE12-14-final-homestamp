package com.chilsuitjo.back.action.service;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionEntry;
import java.util.List;
import java.util.UUID;

/**
 * 행동 기록 한 줄 쓰기. {@link ActionPipeline} 안쪽 부품이라 package-private이다. 다른 도메인은 import할 수 없고 pipeline만
 * 부른다 (09 3-1 "가짜").
 *
 * <p>지금은 {@link FakeActionWriter}. 실제 구현은 {@code room_seq} = 마지막 + 1, {@code prev_hash} = 마지막 hash,
 * {@code content_digest}·{@code hash}를 계산해 {@code record.action}에 쓴다(입력 형식은 그때 테스트로 고정, 04 4-0). 같은
 * (주체, 키)가 이미 있으면 {@code UNIQUE(actor_key, idempotency_key)}에 걸려 {@code
 * DataIntegrityViolationException}이 난다.
 */
interface ActionWriter {

  /** 한 줄 쓰고 저장된 모습을 돌려준다. {@code requestId}는 pipeline이 요청마다 하나 만들어 그 요청의 모든 행동에 넣는다(#66). */
  ActionEntry write(ActionCommand command, UUID requestId);

  /**
   * 이 요청으로 이미 남긴 행동, {@code room_seq} 순. 없으면 빈 목록. 첫 행동은 (주체, {@code <키>})로 찾고, 나머지는 그 행동의 {@code
   * requestId}로 모은다. 같은 호실의 다른 회원이 같은 키를 써도 섞이지 않고, 주체가 다른 딸린 행동({@code server})도 빠지지 않는다.
   */
  List<ActionEntry> findRequest(String actorKey, String idempotencyKey);
}
