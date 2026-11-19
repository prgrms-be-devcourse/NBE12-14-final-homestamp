package com.chilsuitjo.back.action.dto;

import java.util.List;

/**
 * {@code ActionPipeline.executeAll}의 결과. 남긴 순서(= {@code room_seq} 순서)대로 담는다. 조건({@code when})이 거짓이라
 * 건너뛴 단계는 없고, 딸린 행동은 들어 있다.
 *
 * @param replayed 같은 키로 다시 온 요청이라 처음 행동들을 그대로 돌려줬으면 true
 */
public record BatchResult(List<ActionResult> actions, boolean replayed) {

  public BatchResult {
    actions = List.copyOf(actions);
  }

  public ActionResult first() {
    return actions.getFirst();
  }
}
