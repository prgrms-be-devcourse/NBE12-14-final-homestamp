package com.chilsuitjo.back.settlement.dto;

import com.chilsuitjo.back.correction.dto.CorrectionView;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.settlement.rule.DepositStatus;
import com.chilsuitjo.back.settlement.rule.TransferDirection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 정산 화면(S-15, 06 4-8 {@code GET /api/settlements/{id}}).
 *
 * <p><b>현재 구현 범위의 한계</b>(06과 다른 점): 보증금 금액, 수리 건 후보, 세 칸, 합의된 항목으로 계산한 금액(과 "차이 ○원")은 아직 담지 않는다.
 * 보증금·항목(SET-1·2·3)을 만들 때 칸을 더한다.
 *
 * @param id 정산
 * @param roomRecordId 호실 기록
 * @param mySide 보는 사람의 측
 * @param depositStatus 보증금 상태(화면 말은 "보증금 확인 전"/"둘 다 확인", 06 SET-1b)
 * @param snapshotNo 정산 모습 번호. 끝내기 확인 때 {@code seenVersionNo}로 돌려보낸다(06 SET-5e)
 * @param createdAt 정산을 연 시각
 * @param settled 정산 완료인지(확인·혼자 끝내기·자동 마감)
 * @param finishRequestedBy 지금 열린(기다림·미확인) 정산 끝내기 요청을 보낸 측. 없으면 null
 * @param unansweredForMe 이 호실에서 내가 아직 답하지 않은 확인 요청 수(기다림·미확인, 06 SET-5g). 내가 요청한 쪽인 요청은 세지 않는다
 * @param transfers 송금 기록, 기록한 순서대로(SET-4)
 * @param confirmedRefund 상대가 확인한 반환 합계(같은 송금으로 묶인 기록은 한 번만, SET-4b·4e)
 * @param confirmedExtra 상대가 확인한 추가 송금 합계(같은 규칙)
 * @param availableActions 지금 이 사람이 누를 수 있는 행동. 이름은 API 끝 단어와 같다(06 4-0 "할 수 있는 행동")
 */
public record SettlementDetail(
    UUID id,
    UUID roomRecordId,
    PartySide mySide,
    DepositStatus depositStatus,
    int snapshotNo,
    Instant createdAt,
    boolean settled,
    PartySide finishRequestedBy,
    int unansweredForMe,
    List<Transfer> transfers,
    long confirmedRefund,
    long confirmedExtra,
    List<String> availableActions) {

  /**
   * 송금 기록 하나.
   *
   * @param id 송금
   * @param direction 반환·추가 송금
   * @param recordedBySide 기록한 측. 보내는 측이면 보낸 기록, 받는 측이면 [받았어요(혼자)]
   * @param amount 지금 판의 금액
   * @param paidOn 지금 판의 날짜
   * @param versionNo 지금 판 번호. 응답 때 {@code seenVersionNo}로 보낸다
   * @param response 기록을 남기지 않은 측의 응답(PENDING이면 "○○ 님 확인 기다림")
   * @param confirmedAmount 상대가 확인한 판의 금액. 확인 전이면 null
   * @param sameAsTransferId 같은 송금으로 묶인 보낸 쪽 기록. 없으면 null
   * @param receiptConfirmedAt 상대가 맞아요를 누른 시각. 없으면 null
   * @param createdAt 기록한 시각
   * @param corrections 이 송금의 고친 내용(확인 뒤 고치면 원래 기록과 나란히, SET-4d)
   * @param availableActions 이 송금에 지금 누를 수 있는 행동({@code respond}, 기록한 측의 {@code corrections})
   */
  public record Transfer(
      UUID id,
      TransferDirection direction,
      PartySide recordedBySide,
      long amount,
      LocalDate paidOn,
      int versionNo,
      AckState response,
      Long confirmedAmount,
      UUID sameAsTransferId,
      Instant receiptConfirmedAt,
      Instant createdAt,
      List<CorrectionView> corrections,
      List<String> availableActions) {}
}
