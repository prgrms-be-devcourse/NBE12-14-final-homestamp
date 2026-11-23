package com.chilsuitjo.back.global.common;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.util.regex.Pattern;

/**
 * 사용자가 적는 글에 주민등록번호 형태가 있으면 막는다(03 6-1 "설명 속 번호", 개인정보 보호법 제24조의2, 06 오류 코드 {@code
 * RESIDENT_ID_BLOCKED}). 가리지 않고 저장 자체를 거절한다. 전화번호 가리기는 화면 몫이라 여기서 하지 않는다.
 *
 * <p>형태: 생년월일 6자리(월 01~12, 일 01~31) + 구분(하이픈·공백 하나 또는 없음) + 첫 자리 1~8인 7자리. 앞뒤가 다른 숫자에 붙어 있으면(더 긴
 * 숫자의 일부) 보지 않는다. 계좌번호 같은 13자리 숫자를 덜 막으려고 날짜와 성별 자리까지 본다.
 *
 * <p>자유 글을 받는 모든 칸(설명·사유·메모·진행 상황 등)에서 요청을 처리하기 전에 부른다.
 */
public final class ResidentIdGuard {

  private static final Pattern RESIDENT_ID =
      Pattern.compile("(?<!\\d)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])[- ]?[1-8]\\d{6}(?!\\d)");

  private ResidentIdGuard() {}

  /** 주민등록번호 형태가 들어 있는지. null이면 거짓이다. */
  public static boolean contains(String text) {
    return text != null && RESIDENT_ID.matcher(text).find();
  }

  /** 하나라도 주민등록번호 형태가 있으면 {@code RESIDENT_ID_BLOCKED}(400). null 칸은 건너뛴다. */
  public static void check(String... texts) {
    for (String text : texts) {
      if (contains(text)) {
        throw new BusinessException(ErrorCode.RESIDENT_ID_BLOCKED);
      }
    }
  }
}
