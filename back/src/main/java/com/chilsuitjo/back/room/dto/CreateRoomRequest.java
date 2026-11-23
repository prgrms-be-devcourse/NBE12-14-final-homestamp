package com.chilsuitjo.back.room.dto;

import com.chilsuitjo.back.global.common.PartySide;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 호실 기록 만들기 요청(06 4-3 {@code POST /api/rooms}). 길이 상한은 DB 칸 크기(05 {@code room_record}: 별칭 50)다.
 *
 * <p>06 4-3의 "상대 번호 선택"은 초대(ROOM-4)를 만들 때 더한다. 지금은 받지 않는다.
 *
 * @param side 이 호실에서 내 쪽(임차인 / 임대인)
 * @param regionCode 법정동 코드 10자리. 코드표에 없거나 고를 수 없는 코드는 서비스가 {@code VALIDATION_FAILED}로 막는다
 * @param alias 호실 별칭(예: 302호)
 * @param moveInDate 입주일. 비워 둘 수 있다(06 ROOM-1a)
 */
public record CreateRoomRequest(
    @NotNull PartySide side,
    @NotNull @Pattern(regexp = "[0-9]{10}") String regionCode,
    @NotBlank @Size(max = 50) String alias,
    LocalDate moveInDate) {}
