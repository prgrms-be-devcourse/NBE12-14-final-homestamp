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
 * <p>상대 번호는 선택이다(03 3장 "호실 기록 만들기", 06 S-05). 넣으면 상대 자리를 번호만({@code NUMBER_ONLY})으로 저장하고 초대·문자는 하지
 * 않는다(첫 문자는 초대할 때, 03 B "상대 초대하기"). 번호 모양과 내 번호 여부는 서비스가 본다.
 *
 * @param side 이 호실에서 내 쪽(임차인 / 임대인)
 * @param regionCode 법정동 코드 10자리. 코드표에 없거나 고를 수 없는 코드는 서비스가 {@code VALIDATION_FAILED}로 막는다
 * @param alias 호실 별칭(예: 302호)
 * @param moveInDate 입주일. 비워 둘 수 있다(06 ROOM-1a)
 * @param counterpartPhone 상대 번호. 비워 둘 수 있다. 하이픈·공백이 있어도 되고 숫자만 남겨 저장한다(06 4-0 "전화번호")
 */
public record CreateRoomRequest(
    @NotNull PartySide side,
    @NotNull @Pattern(regexp = "[0-9]{10}") String regionCode,
    @NotBlank @Size(max = 50) String alias,
    LocalDate moveInDate,
    String counterpartPhone) {}
