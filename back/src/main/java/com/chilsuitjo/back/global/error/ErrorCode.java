package com.chilsuitjo.back.global.error;

import org.springframework.http.HttpStatus;

/** 오류 코드 목록. 기준은 docs/planning/06-요구사항-명세.md 4-10이고, 화면은 {@code code}만 보고 처리한다. */
public enum ErrorCode {
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력한 내용을 확인해 주세요"),
  REASON_REQUIRED(HttpStatus.BAD_REQUEST, "사유를 적어 주세요"),
  MOVE_IN_DATE_REQUIRED(HttpStatus.BAD_REQUEST, "입주일을 먼저 적어 주세요. 계약서의 입주일이에요"),
  PHOTO_REQUIRED(HttpStatus.BAD_REQUEST, "사진을 한 장 이상 올려 주세요. 찍기 어려우면 그 부위를 찍어 주세요"),
  AGE_CONFIRM_REQUIRED(HttpStatus.BAD_REQUEST, "만 14세 이상만 가입할 수 있어요"),
  RESIDENT_ID_BLOCKED(HttpStatus.BAD_REQUEST, "주민등록번호 형태는 적을 수 없어요"),
  LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일이나 비밀번호가 맞지 않아요"),
  NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없는 기록이에요"),
  LINK_INVALID(HttpStatus.NOT_FOUND, "더 이상 열 수 없는 링크예요"),
  INVALID_STATE(HttpStatus.CONFLICT, "이미 상태가 바뀌었어요. 다시 불러올게요"),
  VERSION_CONFLICT(HttpStatus.CONFLICT, "다른 쪽에서 먼저 바뀌었어요. 다시 불러올게요"),
  NOT_COUNTERPART_SIDE(HttpStatus.FORBIDDEN, "이 기록에서는 할 수 없는 일이에요"),
  OWN_PROPOSAL(HttpStatus.FORBIDDEN, "이 기록에서는 할 수 없는 일이에요"),
  NOT_UPLOADER(HttpStatus.FORBIDDEN, "이 기록에서는 할 수 없는 일이에요"),
  SUM_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "나눈 금액의 합이 전체 금액과 달라요"),
  DUPLICATE_PAYMENT(HttpStatus.CONFLICT, "같은 돈이 이미 기록돼 있어요. 그 기록을 확인해 주세요"),
  UPLOAD_TICKET_EXPIRED(HttpStatus.UNPROCESSABLE_CONTENT, "이 사진을 다시 올려 주세요"),
  PHOTO_CHECK_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "이 사진을 다시 올려 주세요"),
  ASKED_RECENTLY(HttpStatus.CONFLICT, "○월 ○일부터 다시 물을 수 있어요"),
  RESEND_USED(HttpStatus.CONFLICT, "다시 보내기는 한 번만 할 수 있어요"),
  UPLOAD_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "잠시 뒤에 다시 해 주세요"),
  RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "잠시 뒤에 다시 해 주세요"),
  TERMS_UPDATE_REQUIRED(HttpStatus.FORBIDDEN, "약관이 바뀌었어요. 확인하고 계속해 주세요"),
  ACK_FINAL(HttpStatus.CONFLICT, "맞아요는 바꿀 수 없어요. 덧붙일 말은 진행 상황이나 원인 설명으로 남겨 주세요"),
  EVIDENCE_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "사진이나 설명을 하나 이상 더해 주세요"),
  IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "다시 시도해 주세요"),
  ROOM_ENDED(HttpStatus.CONFLICT, "거주가 끝난 호실이라 새 수리 건을 만들 수 없어요"),
  CODE_INVALID(HttpStatus.BAD_REQUEST, "인증번호가 맞지 않거나 시간이 지났어요. [다시 받기]"),
  CODE_EXPIRED(HttpStatus.BAD_REQUEST, "인증번호가 맞지 않거나 시간이 지났어요. [다시 받기]"),
  EMAIL_TAKEN(HttpStatus.CONFLICT, "이미 가입한 이메일이에요. 로그인해 주세요"),
  TERMS_REQUIRED(HttpStatus.BAD_REQUEST, "이용약관과 처리 안내를 확인해 주세요"),
  PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "지금 비밀번호가 맞지 않아요"),
  REFRESH_REUSED(HttpStatus.UNAUTHORIZED, "보안을 위해 로그아웃했어요. 다시 로그인해 주세요"),
  PHONE_MISMATCH(HttpStatus.CONFLICT, "초대받은 번호와 인증한 번호가 달라요. 초대한 분께 번호를 확인해 달라고 해 주세요"),
  SELF_INVITE(HttpStatus.BAD_REQUEST, "내 번호로는 초대할 수 없어요"),
  CONSENT_REQUIRED(HttpStatus.BAD_REQUEST, "기록 공유에 동의해야 함께 쓸 수 있어요"),
  PHONE_OPTED_OUT(HttpStatus.CONFLICT, "이 번호는 문자를 그만 받기로 했어요. 혼자 기록할 수 있어요"),
  JOIN_NOT_ALLOWED(HttpStatus.CONFLICT, "이 두 기록은 하나로 모을 수 없어요. 따로 남겨 둘게요"),
  SHARING_STOPPED(HttpStatus.CONFLICT, "공유를 그만둔 호실이에요. [다시 공유하기]로 이어 쓸 수 있어요"),
  SOLO_NOT_ALLOWED(HttpStatus.CONFLICT, "아직 상대의 답을 기다리는 중이에요"),
  CLOSED_BY_BOTH(HttpStatus.CONFLICT, "둘 다 확인해 끝난 건이에요. 다시 생기면 새 수리 건으로 남겨 주세요"),
  NOT_CURRENT_PROPOSAL(HttpStatus.CONFLICT, "더 새로운 안이 있어요. 다시 불러올게요"),
  SAME_VALUE(HttpStatus.BAD_REQUEST, "지금 기록과 같은 내용이에요"),
  BASIS_REQUIRED(HttpStatus.BAD_REQUEST, "근거 종류를 골라 주세요"),
  ALREADY_ERASED(HttpStatus.CONFLICT, "이미 지운 사진이에요"),
  SETTLED(HttpStatus.CONFLICT, "정산이 끝난 호실이라 더할 수 없어요"),
  SOURCE_IS_REPAIR(HttpStatus.CONFLICT, "수리 건에서 온 항목은 그 수리 건의 부담 합의에서 바꿔 주세요"),
  INSPECTION_EXISTS(HttpStatus.CONFLICT, "이미 시작된 점검(정산)이 있어요. 그쪽으로 열게요"),
  SETTLEMENT_EXISTS(HttpStatus.CONFLICT, "이미 시작된 점검(정산)이 있어요. 그쪽으로 열게요"),
  INSPECTION_LOCKED(HttpStatus.CONFLICT, "잠긴 점검이에요. 내용은 [고치기]로, 빠뜨린 하자는 [+ 빠뜨린 하자 더하기]로 남겨 주세요"),
  UPLOAD_TICKET_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "이 사진을 다시 올려 주세요");

  private final HttpStatus status;
  private final String message;

  ErrorCode(HttpStatus status, String message) {
    this.status = status;
    this.message = message;
  }

  public HttpStatus status() {
    return status;
  }

  public String message() {
    return message;
  }
}
