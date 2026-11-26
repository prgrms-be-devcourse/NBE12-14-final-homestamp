package com.chilsuitjo.back.inspection.dto;

import com.chilsuitjo.back.inspection.rule.InspectionKind;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 점검 시작 요청(06 4-7 {@code POST /api/rooms/{id}/inspections}).
 *
 * @param kind 점검 종류. 현재 구현 범위의 한계로 입주 점검({@code MOVE_IN})만 시작할 수 있다. 퇴거 점검({@code MOVE_OUT},
 *     INSP-3, B 담당)은 만들기 전까지 {@code INVALID_STATE}다
 * @param moveInDate 입주일. 호실 입주일이 비어 있으면 필수({@code MOVE_IN_DATE_REQUIRED}), 이미 있으면 보내지 않는다({@code
 *     VALIDATION_FAILED}, 바꾸기는 고치기). 같은 트랜잭션에서 호실에 저장한다(INSP-2b)
 */
public record StartInspectionRequest(@NotNull InspectionKind kind, LocalDate moveInDate) {}
