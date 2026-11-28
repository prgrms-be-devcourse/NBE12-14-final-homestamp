package com.chilsuitjo.back.settlement.dto;

import com.chilsuitjo.back.settlement.rule.TransferDirection;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 송금 기록(06 4-8 {@code POST /api/settlements/{id}/transfers}). 보내는 측이 보내면 보낸 기록, 받는 측이 보내면
 * [받았어요(혼자)]다(03 J). 누가 보냈는지는 로그인한 사람의 측으로 정한다.
 *
 * @param direction 반환({@code REFUND}, 집주인 → 세입자) · 추가 송금({@code EXTRA}, 세입자 → 집주인)
 * @param amount 원 단위 금액(1 이상)
 * @param paidOn 보낸(받은) 날
 * @param sameAsTransferId 받은 쪽이 상대 기록에 금액 이견을 단 뒤 실제 받은 금액을 적을 때 그 기록(SET-4e). 없으면 null
 */
public record TransferRequest(
    TransferDirection direction, Long amount, LocalDate paidOn, UUID sameAsTransferId) {}
