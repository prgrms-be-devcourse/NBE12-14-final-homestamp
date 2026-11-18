package com.chilsuitjo.back.action.rule;

import static com.chilsuitjo.back.action.rule.ActionHashTest.filled;
import static com.chilsuitjo.back.action.rule.ActionHashTest.hex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.rule.ContentDigest.Field;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 내용 지문 입력 형식을 고정한다(#72). 형식을 바꾸려면 테스트를 고치지 말고 형식 버전을 올린다. */
class ContentDigestTest {

  static final UUID VERSION_ID = UUID.fromString("0193a000-0000-7000-8000-000000000005");

  static byte[] sample(List<Field> fields, List<byte[]> photos) {
    return ContentDigest.compute("repair_content", VERSION_ID, 1, fields, photos);
  }

  static List<Field> fields() {
    return List.of(new Field("title", "창문 누수"), new Field("description", null));
  }

  @Test
  void 테스트_벡터() {
    assertThat(hex(sample(fields(), List.of(filled(0xaa)))))
        .isEqualTo("f75cd1cfd3ee926b8a4d144cb720fdc097bf3090150f9da0298aa0cfd51e7026");
  }

  @Test
  void 판_종류_ID_번호_칸_사진_하나만_달라도_다르다() {
    byte[] base = sample(fields(), List.of(filled(0xaa)));

    assertThat(ContentDigest.compute("payment_version", VERSION_ID, 1, fields(), List.of()))
        .isNotEqualTo(base);
    assertThat(ContentDigest.compute("repair_content", UUID.randomUUID(), 1, fields(), List.of()))
        .isNotEqualTo(base);
    assertThat(ContentDigest.compute("repair_content", VERSION_ID, 2, fields(), List.of()))
        .isNotEqualTo(base);
    assertThat(sample(List.of(new Field("title", "창문 누수")), List.of(filled(0xaa))))
        .isNotEqualTo(base);
    assertThat(sample(fields(), List.of(filled(0xab)))).isNotEqualTo(base);
    assertThat(sample(fields(), List.of())).isNotEqualTo(base);
  }

  @Test
  void 칸_순서와_사진_순서도_형식이다() {
    List<Field> reversed = List.of(fields().get(1), fields().get(0));
    assertThat(sample(reversed, List.of())).isNotEqualTo(sample(fields(), List.of()));
    assertThat(sample(fields(), List.of(filled(1), filled(2))))
        .isNotEqualTo(sample(fields(), List.of(filled(2), filled(1))));
  }

  @Test
  void 없는_값과_빈_글자는_다르다() {
    assertThat(sample(List.of(new Field("description", null)), List.of()))
        .isNotEqualTo(sample(List.of(new Field("description", "")), List.of()));
  }

  @Test
  void 잘못된_입력은_거절한다() {
    assertThatThrownBy(() -> sample(fields(), List.of(new byte[31])))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> sample(Arrays.asList(new Field(null, "a")), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ContentDigest.compute(null, VERSION_ID, 1, fields(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
