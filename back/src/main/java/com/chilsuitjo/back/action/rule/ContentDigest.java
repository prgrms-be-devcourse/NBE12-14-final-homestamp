package com.chilsuitjo.back.action.rule;

import com.chilsuitjo.back.action.dto.DigestField;
import com.chilsuitjo.back.action.dto.DigestInput;
import java.util.List;

/**
 * 내용 지문({@code action.content_digest}) 계산(05 2-3 "내용 지문과 행동 해시는 나눠 계산한다", #72). 당시 본 판의 글 내용을 묶는다.
 * 사진 지문은 넣지 않고 행동 payload에 남긴다(#141, 05 2-3). DB·Spring 없는 순수 함수다.
 *
 * <p>입력 순서: 형식 버전 → 판 종류 → 판 ID → 판 번호 → 칸 수 → 칸마다 이름·값. 이어 붙이는 규칙은 행동 해시와 같다. 칸 순서도 형식의 일부라 부르는 쪽이
 * 도메인이 정한 순서로 준다.
 */
public final class ContentDigest {

  /** 지금 형식 버전. 행동 해시의 {@code hash_version}과 함께 올린다. */
  public static final int VERSION = ActionHash.VERSION;

  private ContentDigest() {}

  /** 각 도메인이 {@link DigestInput}으로 담아 보낸 판의 지문. */
  public static byte[] compute(DigestInput input) {
    if (input == null || input.kind() == null || input.versionId() == null) {
      throw new IllegalArgumentException("판 종류와 판 ID가 있어야 해요");
    }
    // 없음과 빈 것은 다른 입력이다. 목록이 null이면 부르는 쪽 실수라 빈 목록으로 바꾸지 않고 거절한다.
    if (input.fields() == null) {
      throw new IllegalArgumentException("칸 목록은 비어 있어도 null이 아니어야 해요");
    }
    List<DigestField> fields = input.fields();
    HashEncoder e = new HashEncoder();
    e.int64(VERSION)
        .string(input.kind())
        .uuid(input.versionId())
        .int64(input.versionNo())
        .int64(fields.size());
    for (DigestField field : fields) {
      if (field.name() == null) {
        throw new IllegalArgumentException("칸 이름이 비었어요");
      }
      e.string(field.name()).string(field.value());
    }
    return e.sha256();
  }
}
