package com.chilsuitjo.back.member.rule;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** ROOM-2c: 이름은 같은 규칙으로 가린다. 예는 06 ROOM-2c 표 그대로다. */
class NameMaskTest {

  @ParameterizedTest
  @CsvSource({"정, 정", "정윤, 정*", "정서윤, 정*윤", "남궁민수, 남**수", "'John Kim', 'J*** **m'"})
  void 이름을_가린다(String name, String masked) {
    assertThat(NameMask.mask(name)).isEqualTo(masked);
  }

  @Test
  void 자모가_풀린_한글도_조합형과_같게_가린다() {
    String decomposed = Normalizer.normalize("정서윤", Normalizer.Form.NFD);
    assertThat(decomposed).hasSizeGreaterThan(3);

    assertThat(NameMask.mask(decomposed)).isEqualTo("정*윤");
    assertThat(NameMask.mask(Normalizer.normalize("정윤", Normalizer.Form.NFD))).isEqualTo("정*");
  }

  @Test
  void 결합_부호로_나뉜_글자도_한_글자로_센다() {
    // e + 결합 악센트 + a: 코드 포인트 셋이지만 화면에는 두 글자(éa)다. 끝 글자 a가 드러나면 안 된다
    assertThat(NameMask.mask("éa")).isEqualTo("é*");
    assertThat(NameMask.mask("éa")).isEqualTo("é*");
  }

  @Test
  void NFC로_합쳐지지_않는_결합_문자도_끝_글자를_드러내지_않는다() {
    // ą́(a + 오고넥 + 악센트)는 NFC로도 한 코드 포인트가 되지 않는다. 가린 글자는 결합 부호까지 통째로 * 하나다
    assertThat(NameMask.mask("b" + "ą́")).isEqualTo("b*");
    // 남기는 글자는 NFC 모양으로 나온다(a + 오고넥 → ą)
    assertThat(NameMask.mask("ą́" + "bc")).isEqualTo("ą́*c");
  }

  @Test
  void 옛한글_자모_묶음도_한_글자로_센다() {
    // 조합형이 없는 옛한글(ᄀ + ᆞ)도 화면에서는 한 글자다
    assertThat(NameMask.mask("김" + "ᄀᆞ")).isEqualTo("김*");
  }
}
