package com.chilsuitjo.back.action.rule;

import com.chilsuitjo.back.global.common.PartySide;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 행동 해시({@code action.hash}) 계산(04 4-0 해시 체인, 05 2-3, #72). DB·Spring 없는 순수 함수다.
 *
 * <p>입력 순서는 형식 버전을 맨 앞에 두고, 그다음은 V1 {@code record.action}의 칸 순서다({@code id}·{@code hash}는 빼고, V3의
 * {@code request_id}는 맨 끝). 대상 17칸은 각각 넣는다. 순서는 아래 {@link #compute}에 목록으로 고정하고 DB에서 읽어 오지 않는다.
 *
 * <p>{@code action}에 칸을 더하는 PR은 그 칸을 이 입력에 넣을지 정하고, 넣으면 {@link #VERSION}을 올린다(decisions
 * 2026-11-18).
 */
public final class ActionHash {

  /** 지금 형식 버전. {@code action.hash_version}에 이 값이 저장된다. */
  public static final int VERSION = 1;

  /** 해시·지문 길이(SHA-256). */
  public static final int LENGTH = 32;

  /** 대상 칸. V1 {@code record.action}의 칸 순서와 같고, 해시 입력도 이 순서다. */
  public static final List<String> TARGET_COLUMNS =
      List.of(
          "party_id",
          "link_id",
          "room_part_id",
          "notice_id",
          "repair_case_id",
          "payment_id",
          "inspection_id",
          "inspection_item_id",
          "settlement_id",
          "settlement_item_id",
          "settlement_transfer_id",
          "proposal_id",
          "response_request_id",
          "correction_request_id",
          "cause_note_id",
          "progress_note_id",
          "photo_id");

  private ActionHash() {}

  /** 호실 첫 행동({@code room_seq} 1)의 {@code prev_hash}. 32바이트 0. */
  public static byte[] start() {
    return new byte[LENGTH];
  }

  /**
   * 행동 해시를 계산한다.
   *
   * @throws IllegalArgumentException 지원하지 않는 형식 버전, 길이가 틀린 해시·지문, 알 수 없는 대상 칸, 마이크로초보다 작은 자리가 있는
   *     시각, payload에 소수·쓸 수 없는 값이 있을 때
   */
  public static byte[] compute(Input in) {
    if (in.hashVersion() != VERSION) {
      throw new IllegalArgumentException("지원하지 않는 해시 형식 버전이에요: " + in.hashVersion());
    }
    requireLength("prev_hash", in.prevHash(), false);
    requireLength("content_digest", in.contentDigest(), true);
    int targetIndex = targetIndex(in.targetColumn(), in.targetId());

    HashEncoder e = new HashEncoder();
    e.int64(in.hashVersion())
        .uuid(in.roomRecordId())
        .int64(in.roomSeq())
        .bytes(in.prevHash())
        .bytes(in.contentDigest())
        .uuid(in.actorMemberId())
        .string(in.actorSide() == null ? null : in.actorSide().name())
        .string(in.actorKey())
        .string(in.type())
        .string(in.idempotencyKey())
        .int64(in.seenVersionNo())
        .string(in.reason())
        .payload(in.payload());
    for (int i = 0; i < TARGET_COLUMNS.size(); i++) {
      e.uuid(i == targetIndex ? in.targetId() : null);
    }
    e.micros(in.createdAt()).uuid(in.requestId());
    return e.sha256();
  }

  private static int targetIndex(String column, UUID id) {
    if (column == null && id == null) {
      return -1;
    }
    if (column == null || id == null) {
      throw new IllegalArgumentException("대상 칸과 대상 ID는 함께 주거나 함께 비워요");
    }
    int index = TARGET_COLUMNS.indexOf(column);
    if (index < 0) {
      throw new IllegalArgumentException("알 수 없는 대상 칸이에요: " + column);
    }
    return index;
  }

  static void requireLength(String name, byte[] value, boolean nullable) {
    if (value == null ? !nullable : value.length != LENGTH) {
      throw new IllegalArgumentException(name + "는 " + LENGTH + "바이트여야 해요");
    }
  }

  /**
   * 행동 해시의 입력. 칸은 {@code record.action}과 같은 뜻이다.
   *
   * @param payload jsonb 값. {@code Map}·{@code List}·글자·정수·참거짓·null로 이뤄진다. DB에서 읽은 값도 같은 해시가 나온다(객체
   *     키 순서와 상관없음). 저장할 때는 도메인이 넘긴 {@code Map}을 그대로 주지 않고, <b>JSON으로 바꿨다가 다시 읽은 값</b>을 준다. {@code
   *     UUID}·날짜·enum처럼 JSON에서 글자가 되는 값을 jsonb에 저장되는 모양과 같게 맞추고, 검증할 때 DB에서 읽은 값과도 같은 입력이 되게 하려는
   *     것이다(05 2-3 「해시 입력 형식」 payload)
   * @param targetColumn 채운 대상 칸 이름({@link #TARGET_COLUMNS}). 호실 기록 자체가 대상이면 null
   * @param createdAt 마이크로초로 자른 시각. 같은 값을 DB에 저장해야 한다
   */
  public record Input(
      int hashVersion,
      UUID roomRecordId,
      long roomSeq,
      byte[] prevHash,
      byte[] contentDigest,
      UUID actorMemberId,
      PartySide actorSide,
      String actorKey,
      String type,
      String idempotencyKey,
      Integer seenVersionNo,
      String reason,
      Object payload,
      String targetColumn,
      UUID targetId,
      Instant createdAt,
      UUID requestId) {}
}
