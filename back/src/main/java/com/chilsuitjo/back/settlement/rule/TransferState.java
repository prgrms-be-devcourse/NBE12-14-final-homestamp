package com.chilsuitjo.back.settlement.rule;

import com.chilsuitjo.back.action.rule.Answerable;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;

/**
 * 정산 송금 하나의 상태 값({@link TransferTransitions}가 다룬다). 아직 없는 송금이면 {@code recordedBy}가 비어 있다.
 *
 * <p><b>요청할 때만 아는 값</b>({@code sameAsOk})은 재계산({@code Replay.fold})에서 null이고, 그때는 처음 요청 때 서버가 확인했다고
 * 본다.
 *
 * @param direction 반환·추가 송금
 * @param recordedBy 이 기록을 남긴 측. 반대 측이 답한다(보낸 쪽 기록이면 받은 쪽, [받았어요(혼자)]면 보낸 쪽, 03 J)
 * @param response 기록을 남기지 않은 측의 응답. 아직 없는 송금이면 null
 * @param versionNo 지금 판 번호({@code settlement_transfer_version.no})
 * @param sameAsOk 묶으려는 송금({@code sameAsTransferId})이 같은 정산·같은 방향의 보낸 쪽 기록인지. 묶지 않으면 null
 */
public record TransferState(
    TransferDirection direction,
    PartySide recordedBy,
    AckState response,
    Integer versionNo,
    Boolean sameAsOk) {

  /** 재계산의 시작점. */
  public static final TransferState NONE = new TransferState(null, null, null, null, null);

  /** 아직 없는 송금을 기록하기 전 상태. */
  public static TransferState beforeRecord(Boolean sameAsOk) {
    return new TransferState(null, null, null, null, sameAsOk);
  }

  public boolean exists() {
    return recordedBy != null;
  }

  /** 응답 공통 규칙({@code AnswerSupport})에 넘기는 값. 다시 요청(보충)은 송금에 없다. */
  public Answerable answerable() {
    return new Answerable(recordedBy, response, versionNo, null);
  }

  /** 이 방향의 돈을 보내는 측(반환은 집주인, 추가 송금은 세입자, 03 J). */
  public static PartySide sender(TransferDirection direction) {
    return direction == TransferDirection.REFUND ? PartySide.LANDLORD : PartySide.TENANT;
  }

  public TransferState withResponse(AckState next) {
    return new TransferState(direction, recordedBy, next, versionNo, sameAsOk);
  }
}
