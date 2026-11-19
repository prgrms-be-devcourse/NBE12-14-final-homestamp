package com.chilsuitjo.back.member.rule;

import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 이름 가리기(03 K "이름 가리기", 06 ROOM-2c). 문자 본문·연결 확인 화면·상대 쪽 회원 화면이 모두 이 규칙 하나를 쓴다.
 *
 * <p>공백은 세지 않고 자리만 지킨다. 한 글자는 그대로, 두 글자는 끝을 {@code *}로, 세 글자 이상은 첫 글자와 끝 글자만 남긴다.
 *
 * <p>글자는 화면에 보이는 한 글자(유니코드 문자소 묶음, UAX #29)로 센다. 가입·이름 변경이 입력을 정규화한다는 보장이 없어서, 같은 이름이 조합형(정서윤)으로 오든
 * 자모가 풀린 분리형으로 오든, 악센트가 붙은 글자(é)로 오든 글자와 결합 부호로 나뉘어 오든 같은 글자 수로 센다. 먼저 NFC로 맞추고, NFC로 한 코드 포인트가 되지
 * 않는 결합 문자도 문자소 경계로 한 글자로 묶는다. 가린 글자는 결합 부호까지 통째로 {@code *} 하나가 된다.
 */
public final class NameMask {

  private NameMask() {}

  public static String mask(String name) {
    String normalized = Normalizer.normalize(name, Normalizer.Form.NFC);
    List<String> graphemes = graphemes(normalized);
    int letters = (int) graphemes.stream().filter(g -> !isWhitespace(g)).count();
    StringBuilder masked = new StringBuilder();
    int index = 0;
    for (String grapheme : graphemes) {
      if (isWhitespace(grapheme)) {
        masked.append(grapheme);
        continue;
      }
      boolean keep = letters == 1 || index == 0 || (letters >= 3 && index == letters - 1);
      masked.append(keep ? grapheme : "*");
      index++;
    }
    return masked.toString();
  }

  private static List<String> graphemes(String text) {
    BreakIterator boundary = BreakIterator.getCharacterInstance(Locale.ROOT);
    boundary.setText(text);
    List<String> graphemes = new ArrayList<>();
    for (int start = boundary.first(), end = boundary.next();
        end != BreakIterator.DONE;
        start = end, end = boundary.next()) {
      graphemes.add(text.substring(start, end));
    }
    return graphemes;
  }

  private static boolean isWhitespace(String grapheme) {
    return grapheme.codePoints().allMatch(Character::isWhitespace);
  }
}
