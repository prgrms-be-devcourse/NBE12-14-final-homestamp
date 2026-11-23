package com.chilsuitjo.back.action.dto;

import java.util.List;
import java.util.UUID;

/**
 * 05 {@code action.content_digest}의 재료. action은 도메인을 모르므로 각 도메인이 자기 판을 이 모양으로 담아 보낸다.
 *
 * <p>지문은 {@code action.rule.ContentDigest}가 계산한다. 입력 형식(이어 붙이는 순서·길이 표시)은 05 2-3 「해시 입력 형식」과 #72
 * 테스트가 고정한다.
 *
 * <p>사진 지문은 넣지 않는다. 사진은 판을 새로 만들지 않고 붙으므로 행동 payload에 남긴다({@link SavedState#withPhotos}, 05 2-3,
 * #141).
 *
 * @param kind 판 테이블 이름. 예: "repair_content", "payment_version"
 * @param versionId 판 ID
 * @param versionNo 판 번호
 * @param fields 칸 이름 순서대로. 순서도 형식의 일부
 */
public record DigestInput(String kind, UUID versionId, int versionNo, List<DigestField> fields) {}
