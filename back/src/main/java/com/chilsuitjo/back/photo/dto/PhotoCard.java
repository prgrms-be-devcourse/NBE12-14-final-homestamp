package com.chilsuitjo.back.photo.dto;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.photo.rule.PhotoStage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 등록된 사진을 회원 화면에 보여 줄 정보. A·B·C 응답의 {@code photos} 칸이 모두 이 모양이다. 주소는 없고, 화면이 {@code POST
 * /api/photos/view-urls}로 따로 받는다.
 *
 * @param id 사진 ID
 * @param target 사진이 붙은 대상
 * @param stage 사진을 제출한 단계
 * @param uploadedBy 사진을 올린 측
 * @param sortOrder 같은 대상 안에서의 표시 순서
 * @param createdAt 사진을 등록한 행동의 서버 시각
 * @param deviceTakenAt 화면이 보낸 참고값. 정렬·기한에 쓰지 않는다(06 PHO-1f)
 * @param hiddenOnLink 링크 열람 화면에서 숨겼는지 여부. 회원 화면에는 그대로 보인다
 * @param markedWrongAt 잘못 올린 사진으로 표시한 시각. 표시하지 않았으면 null
 * @param redactedFromPhotoId 가린 사본이면 원본 사진(06 PHO-5d). 아니면 null
 * @param erased 지운 사진이면 흔적. 아니면 null
 * @param availableActions 현재 사용자가 이 사진에 할 수 있는 행동 목록
 */
public record PhotoCard(
    UUID id,
    PhotoTarget target,
    PhotoStage stage,
    PartySide uploadedBy,
    int sortOrder,
    Instant createdAt,
    Instant deviceTakenAt,
    boolean hiddenOnLink,
    Instant markedWrongAt,
    UUID redactedFromPhotoId,
    ErasedPhoto erased,
    List<String> availableActions) {}
