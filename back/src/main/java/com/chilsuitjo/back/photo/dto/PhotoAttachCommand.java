package com.chilsuitjo.back.photo.dto;

import com.chilsuitjo.back.photo.rule.PhotoStage;
import com.chilsuitjo.back.room.dto.RoomAccess;
import java.time.Instant;
import java.util.List;

/**
 * 사진 붙이기({@code PhotoService.attach}). 부르는 쪽 행동의 {@code StateStore.save} 안에서, 그 행동과 같은 트랜잭션으로 넘긴다.
 *
 * @param uploader 표 주인 확인, 올린 측, 한도를 셀 때의 보이는 범위
 * @param target 무엇에 붙나
 * @param stage 부르는 도메인이 행동·대상으로 정한다(05 2-8 "서버가 정함", 화면이 보내지 않음)
 * @param uploadTickets 1~10장. 0부터 시작하는 목록 위치가 사진 번호({@code photoIndex})다
 * @param quota 부르는 도메인이 정하는 대상 한도. 없으면 {@link PhotoQuota#none()}
 * @param at 행동 시각({@code ActionRequestContext.at()}). {@code photo.created_at}이 된다(불변식 10)
 * @param released 붙이는 순간 상대에게 보이나({@code photo_visibility.released_at = at}). 기준 측이 작성 중 점검에 붙이는
 *     사진만 false이고, 확인 요청 때 {@code PhotoService.release}로 보이게 한다(#295, 03 3-1 "작성 중 점검")
 */
public record PhotoAttachCommand(
    RoomAccess uploader,
    PhotoTarget target,
    PhotoStage stage,
    List<String> uploadTickets,
    PhotoQuota quota,
    Instant at,
    boolean released) {

  /** 붙이는 순간 상대에게 보이는 보통 사진. */
  public PhotoAttachCommand(
      RoomAccess uploader,
      PhotoTarget target,
      PhotoStage stage,
      List<String> uploadTickets,
      PhotoQuota quota,
      Instant at) {
    this(uploader, target, stage, uploadTickets, quota, at, true);
  }
}
