package com.chilsuitjo.back.member.rule;

/**
 * 이름 가리기(03 K "이름 가리기", 06 ROOM-2c). 문자 본문·연결 확인 화면·상대 쪽 회원 화면이 모두 이 규칙 하나를 쓴다.
 *
 * <p>공백은 세지 않고 자리만 지킨다. 한 글자는 그대로, 두 글자는 끝을 {@code *}로, 세 글자 이상은 첫 글자와 끝 글자만 남긴다.
 */
public final class NameMask {

  private NameMask() {}

  public static String mask(String name) {
    int letters = (int) name.codePoints().filter(c -> !Character.isWhitespace(c)).count();
    StringBuilder masked = new StringBuilder();
    int index = 0;
    for (int c : name.codePoints().toArray()) {
      if (Character.isWhitespace(c)) {
        masked.appendCodePoint(c);
        continue;
      }
      boolean keep = letters == 1 || index == 0 || (letters >= 3 && index == letters - 1);
      if (keep) {
        masked.appendCodePoint(c);
      } else {
        masked.append('*');
      }
      index++;
    }
    return masked.toString();
  }
}
