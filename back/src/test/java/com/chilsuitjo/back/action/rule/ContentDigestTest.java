package com.chilsuitjo.back.action.rule;

import static com.chilsuitjo.back.action.rule.ActionHashTest.hex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.dto.DigestField;
import com.chilsuitjo.back.action.dto.DigestInput;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 내용 지문 입력 형식을 고정한다(#72). 형식을 바꾸려면 테스트를 고치지 말고 형식 버전을 올린다. 사진 지문은 들어가지 않는다(#141: 출시 전이라 저장된 기록이 없어
 * 버전 1의 정의를 이때 확정함, decisions 2026-11-23).
 */
class ContentDigestTest {

  static final UUID VERSION_ID = UUID.fromString("0193a000-0000-7000-8000-000000000005");

  static byte[] sample(List<DigestField> fields) {
    return digest("repair_content", VERSION_ID, 1, fields);
  }

  static byte[] digest(String kind, UUID versionId, int versionNo, List<DigestField> fields) {
    return ContentDigest.compute(new DigestInput(kind, versionId, versionNo, fields));
  }

  static List<DigestField> fields() {
    return List.of(new DigestField("title", "창문 누수"), new DigestField("description", null));
  }

  /** 기댓값은 Java 코드와 따로 node로 계산했다(형식 버전 1 → 판 종류 → 판 ID → 판 번호 → 칸 수 → 칸마다 이름·값). */
  @Test
  void 테스트_벡터() {
    assertThat(hex(sample(fields())))
        .isEqualTo("5cfd7d4371144768feaae0e4d08bb81656c6f88ea83bc8a97a4d0b75f28f801b");
  }

  @Test
  void 판_종류_ID_번호_칸_하나만_달라도_다르다() {
    byte[] base = sample(fields());

    assertThat(digest("payment_version", VERSION_ID, 1, fields())).isNotEqualTo(base);
    assertThat(digest("repair_content", UUID.randomUUID(), 1, fields())).isNotEqualTo(base);
    assertThat(digest("repair_content", VERSION_ID, 2, fields())).isNotEqualTo(base);
    assertThat(sample(List.of(new DigestField("title", "창문 누수")))).isNotEqualTo(base);
  }

  @Test
  void 칸_순서도_형식이다() {
    List<DigestField> reversed = List.of(fields().get(1), fields().get(0));
    assertThat(sample(reversed)).isNotEqualTo(sample(fields()));
  }

  @Test
  void 없는_값과_빈_글자는_다르다() {
    assertThat(sample(List.of(new DigestField("description", null))))
        .isNotEqualTo(sample(List.of(new DigestField("description", ""))));
  }

  @Test
  void 잘못된_입력은_거절한다() {
    assertThatThrownBy(() -> sample(Arrays.asList(new DigestField(null, "a"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> digest(null, VERSION_ID, 1, fields()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> digest("repair_content", VERSION_ID, 1, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
