package com.chilsuitjo.back.settlement.dto;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.settlement.rule.DepositStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 정산 화면(S-15, 06 4-8 {@code GET /api/settlements/{id}}).
 *
 * <p><b>현재 구현 범위의 한계</b>(06과 다른 점): 보증금 금액, 수리 건 후보, 세 칸, 합의된 항목으로 계산한 금액, 송금 기록은 아직 담지 않는다.
 * 송금(SET-4)·보증금·항목(SET-1·2·3)을 만들 때 칸을 더한다.
 *
 * @param id 정산
 * @param roomRecordId 호실 기록
 * @param mySide 보는 사람의 측
 * @param depositStatus 보증금 상태(화면 말은 "보증금 확인 전"/"둘 다 확인", 06 SET-1b)
 * @param snapshotNo 정산 모습 번호. 끝내기 확인 때 {@code seenVersionNo}로 돌려보낸다(06 SET-5e)
 * @param createdAt 정산을 연 시각
 * @param unansweredForMe 이 호실에서 내가 아직 답하지 않은 확인 요청 수(기다림·미확인, 06 SET-5g). 내가 요청한 쪽인 요청은 세지 않는다
 * @param availableActions 지금 이 사람이 누를 수 있는 행동. 이름은 API 끝 단어와 같다(06 4-0 "할 수 있는 행동")
 */
public record SettlementDetail(
    UUID id,
    UUID roomRecordId,
    PartySide mySide,
    DepositStatus depositStatus,
    int snapshotNo,
    Instant createdAt,
    int unansweredForMe,
    List<String> availableActions) {}
