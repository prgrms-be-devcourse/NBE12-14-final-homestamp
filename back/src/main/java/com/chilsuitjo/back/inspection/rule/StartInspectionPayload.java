package com.chilsuitjo.back.inspection.rule;

/**
 * 점검 시작({@code START_INSPECTION})의 payload.
 *
 * @param kind 점검 종류
 * @param moveInDate 이 행동이 채운 입주일(ISO 날짜 글자). 입주일이 비어 있던 호실에서만 있다(06 INSP-2b). 키 이름은 {@code
 *     RoomMoveInDates.FILLED_PAYLOAD_KEY}와 같다
 */
public record StartInspectionPayload(InspectionKind kind, String moveInDate) {}
