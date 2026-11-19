package com.chilsuitjo.back.link.dto;

import com.chilsuitjo.back.global.common.PartySide;

/**
 * 링크 열람 응답(06 4-4 {@code links/view}, LINK-1·2). 위치는 시·구·동까지만 담고 호실 별칭·건물명·상세 주소·연락처·이름은 담지
 * 않는다(LINK-2a, 03 6-1).
 *
 * @param sigungu 시·군·구. 세종처럼 없는 곳은 null(#65)
 * @param inviterMaskedName 초대한 사람의 가린 이름(03 K). 탈퇴했으면 null
 * @param yourSide 링크를 받은 쪽의 측(화면 문구용)
 * @param record 기록 링크일 때만. 호실 초대·정산 초대면 null
 * @param hasMoney 공유한 기록에 돈 기록이 있는지(금액·낸 사람·날짜는 없음, LINK-1d). 기록 링크가 아니면 false
 */
public record LinkViewResponse(
    LinkViewKind kind,
    String sido,
    String sigungu,
    String dong,
    String inviterMaskedName,
    PartySide yourSide,
    LinkRecordResponse record,
    boolean hasMoney) {}
