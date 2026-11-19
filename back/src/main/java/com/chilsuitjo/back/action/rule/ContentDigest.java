package com.chilsuitjo.back.action.rule;

import com.chilsuitjo.back.action.dto.DigestField;
import com.chilsuitjo.back.action.dto.DigestInput;
import java.util.List;

/**
 * 내용 지문({@code action.content_digest}) 계산(05 2-3 "내용 지문과 행동 해시는 나눠 계산한다", #72). 당시 본 판의 내용과 그 판에
 * 포함된 사진 지문을 묶는다. DB·Spring 없는 순수 함수다.
 *
 * <p>입력 순서: 형식 버전 → 판 종류 → 판 ID → 판 번호 → 칸 수 → 칸마다 이름·값 → 사진 수 → 사진 지문들. 이어 붙이는 규칙은 행동 해시와 같다. 칸
 * 순서와 사진 순서도 형식의 일부라 부르는 쪽이 정해진 순서로 준다(칸은 도메인이 정한 순서, 사진은 {@code sort_order} 순).
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
    List<DigestField> fields = input.fields() == null ? List.of() : input.fields();
    List<byte[]> photoSha256s = input.photoSha256s() == null ? List.of() : input.photoSha256s();
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
    e.int64(photoSha256s.size());
    for (byte[] photo : photoSha256s) {
      ActionHash.requireLength("사진 지문", photo, false);
      e.bytes(photo);
    }
    return e.sha256();
  }
}
