package com.chilsuitjo.back.action.dto;

/**
 * {@code StateStore.save}의 결과. 저장해 봐야 알 수 있는 값을 pipeline에 돌려준다.
 *
 * @param target 새로 만든 대상(REGISTER 등). 호실을 만드는 요청의 첫 단계면 새 호실({@code ActionTarget.roomRecord}). 원래
 *     대상 그대로면 null
 * @param content 새로 만든 판의 지문 재료(판 ID가 저장 뒤에 생기므로). 바뀌지 않았으면 null
 */
public record SavedState(ActionTarget target, DigestInput content) {

  public static SavedState unchanged() {
    return new SavedState(null, null);
  }

  public static SavedState created(ActionTarget target) {
    return new SavedState(target, null);
  }
}
