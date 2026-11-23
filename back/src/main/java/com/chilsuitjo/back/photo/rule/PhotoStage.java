package com.chilsuitjo.back.photo.rule;

/**
 * 사진 단계({@code photo.stage}, 05 2-8). 값은 V1 CHECK와 같다. 서버가 제출 행동과 대상에서 정하고 화면은 보내지 않는다. 부위 사진의 나란히
 * 보기(06 INSP-3b)는 {@code (room_record_id, room_part_id, stage)}로 모은다.
 */
public enum PhotoStage {
  MOVE_IN,
  BEFORE,
  AFTER,
  MOVE_OUT,
  EVIDENCE,
  BASIS,
  PROGRESS
}
