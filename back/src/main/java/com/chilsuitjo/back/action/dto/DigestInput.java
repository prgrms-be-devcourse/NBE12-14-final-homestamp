package com.chilsuitjo.back.action.dto;

import java.util.List;
import java.util.UUID;

/**
 * 05 {@code action.content_digest}의 재료. action은 도메인을 모르므로 각 도메인이 자기 판을 이 모양으로 담아 보낸다.
 *
 * <p>이 재료로 지문을 계산하는 입력 형식은 실제 행동 기록(해시 체인)을 만들 때 테스트로 고정한다(04 4-0, 05 2-3).
 *
 * @param kind 판 테이블 이름. 예: "repair_content", "payment_version"
 * @param fields 칸 이름 순서대로. 순서도 형식의 일부
 * @param photoSha256s 그 판에 포함된 사진의 original_sha256(32바이트), sort_order 순
 */
public record DigestInput(
    String kind,
    UUID versionId,
    int versionNo,
    List<DigestField> fields,
    List<byte[]> photoSha256s) {}
