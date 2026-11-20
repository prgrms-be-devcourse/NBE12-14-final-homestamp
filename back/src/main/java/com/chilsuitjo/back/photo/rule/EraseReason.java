package com.chilsuitjo.back.photo.rule;

/** 사진 파일을 지운 사유({@code photo_visibility.erase_reason}, 05 2-8). 값은 V1 CHECK와 같다. */
public enum EraseReason {
  /** 올린 사람이 개인정보 때문에 지움(06 PHO-5). */
  PRIVACY,
  /** 탈퇴로 지움(05 2-10). */
  WITHDRAWAL,
  /** 사진 속 다른 사람의 요청을 운영 명령으로 처리함(06 PHO-7). */
  THIRD_PARTY
}
