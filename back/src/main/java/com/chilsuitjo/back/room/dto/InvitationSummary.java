package com.chilsuitjo.back.room.dto;

import com.chilsuitjo.back.global.common.PartySide;
import java.util.UUID;

/**
 * 받은 초대 한 줄(06 4-3 {@code GET /api/invitations}, S-08 "○○동, 정*윤님의 호실에 집주인으로 연결할까요?").
 *
 * <p>연결하기 전이라 호실 기록 ID·별칭·기록은 주지 않는다. 연결은 {@code partyId}로 한다({@code POST
 * /api/invitations/{partyId}/connect}). 초대 번호도 주지 않는다(내 인증 번호와 같은 번호라 다시 보일 필요가 없음, 03 6-2 "최소
 * 공개").
 *
 * @param partyId 초대받은 자리
 * @param side 연결하면 내가 앉는 측
 * @param region 호실의 시·구·동
 * @param inviterName 초대한 사람의 가린 이름(03 6-1 "이름 가리기"). 탈퇴했으면 null
 */
public record InvitationSummary(
    UUID partyId, PartySide side, RoomRegion region, String inviterName) {}
