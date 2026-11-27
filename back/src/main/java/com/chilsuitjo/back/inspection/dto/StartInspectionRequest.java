package com.chilsuitjo.back.inspection.dto;

import com.chilsuitjo.back.inspection.rule.InspectionKind;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 점검 시작 요청(06 4-7 {@code POST /api/rooms/{id}/inspections}).
 *
 * @param kind 점검 종류. 입주({@code MOVE_IN})는 거주 중에만, 퇴거({@code MOVE_OUT}, INSP-3)는 정산 완료 전까지 시작한다
 * @param moveInDate 입주일. 입주 점검인데 호실 입주일이 비어 있으면 필수({@code MOVE_IN_DATE_REQUIRED}), 이미 있거나 퇴거 점검이면
 *     보내지 않는다({@code VALIDATION_FAILED}, 바꾸기는 고치기). 같은 트랜잭션에서 호실에 저장한다(INSP-2b)
 */
public record StartInspectionRequest(@NotNull InspectionKind kind, LocalDate moveInDate) {}
