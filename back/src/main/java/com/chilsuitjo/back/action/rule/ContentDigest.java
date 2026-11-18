package com.chilsuitjo.back.action.rule;

import java.util.List;
import java.util.UUID;

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

  /**
   * @param kind 판 테이블 이름. 예: {@code "repair_content"}
   * @param fields 칸 이름 순서대로. 값이 없으면 value를 null로 둔다(빈 글자와 다르다)
   * @param photoSha256s 그 판에 포함된 사진의 {@code original_sha256}(각 32바이트), {@code sort_order} 순
   */
  public static byte[] compute(
      String kind, UUID versionId, int versionNo, List<Field> fields, List<byte[]> photoSha256s) {
    if (kind == null || versionId == null) {
      throw new IllegalArgumentException("판 종류와 판 ID가 있어야 해요");
    }
    HashEncoder e = new HashEncoder();
    e.int64(VERSION).string(kind).uuid(versionId).int64(versionNo).int64(fields.size());
    for (Field field : fields) {
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

  /** 지문에 들어가는 칸 하나. */
  public record Field(String name, String value) {}
}
