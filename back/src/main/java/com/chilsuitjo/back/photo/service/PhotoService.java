package com.chilsuitjo.back.photo.service;

import com.chilsuitjo.back.action.dto.PhotoDigest;
import com.chilsuitjo.back.photo.dto.LinkPhoto;
import com.chilsuitjo.back.photo.dto.PhotoAttachCommand;
import com.chilsuitjo.back.photo.dto.PhotoCard;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.room.dto.RoomAccess;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 다른 도메인이 사진을 붙이고 읽는 창구(09 2장 "repair·inspection·payment·settlement → photo").
 *
 * <p>사진은 따로 확정 API가 없다. 사진이 붙는 행동의 본문에 업로드 표 목록을 넣으면, 그 행동의 트랜잭션 안에서 이 창구로 붙인다(06 4-5). 다른 도메인은 이
 * 인터페이스만 쓴다. 지금은 가짜 구현이 답하고, 실제 구현(검사·S3 복사·주소)은 #112에서 들어온다.
 */
public interface PhotoService {

  /**
   * 업로드 표 목록을 검사하고 대상 하나에 사진 카드를 만든다. 부르는 쪽 행동의 {@code StateStore.save} 안에서 부른다. 이 메서드는 트랜잭션을 따로 열지
   * 않는다. 실제 구현(#112)은 {@code @Transactional(propagation = MANDATORY)}로 호출자의 트랜잭션을 요구한다.
   *
   * <p>실패하면 사진을 한 장도 붙이지 않고 그 사진의 번호({@code uploadTickets}의 0부터 시작하는 위치, {@code photoIndex})와 함께
   * 거절한다. 한 행동에서 여러 대상에 붙일 때는 대상마다 {@code attach}를 부른 결과를 하나로 합친 뒤 {@code SavedState.withPhotos}를 한
   * 번만 부른다. 같은 payload 키를 두 번 더하면 거절되기 때문이다.
   *
   * <ul>
   *   <li>{@code UPLOAD_TICKET_INVALID}: 서명이 다르거나, 남의 표거나, 다른 호실 기록의 표(06 PHO-1b)
   *   <li>{@code UPLOAD_TICKET_EXPIRED}: 표가 {@code upload.ticket.ttl}을 넘음(06 PHO-1c)
   *   <li>{@code PHOTO_CHECK_FAILED}: 서버 검사 실패. 위치 정보가 남은 사진 등(06 PHO-1a)
   * </ul>
   *
   * @return 붙인 사진. {@code (sortOrder, photoId)} 오름차순이다. 부르는 쪽 {@code save}가 {@code
   *     SavedState.withPhotos}로 넘긴다(05 2-3 "사진 지문은 행동 payload에")
   */
  List<PhotoDigest> attach(PhotoAttachCommand command);

  /**
   * 대상들에 붙었고 아직 상대에게 보이지 않은 사진({@link PhotoAttachCommand#released()}가 false였던 사진)을 {@code at}부터
   * 상대에게 보이게 한다(#295). 점검 확인 요청({@code REQUEST_INSPECTION_CONFIRM})의 {@code StateStore.save} 안에서, 그
   * 행동과 같은 트랜잭션으로 부른다. 이미 보이는 사진은 그대로 둔다.
   *
   * <p>공유를 그만둔 뒤 부르면 보이기 시작한 시각이 그만둔 시각보다 뒤라 상대에게는 계속 보이지 않는다(불변식 18, {@code PhotoSight}).
   *
   * @param roomRecordId 대상이 있는 호실 기록
   * @param targets 보이게 할 대상. 이 호실 기록 안의 대상이어야 한다
   * @param at 행동 시각({@code ActionRequestContext.at()})
   */
  void release(UUID roomRecordId, Collection<PhotoTarget> targets, Instant at);

  /**
   * 회원 화면에 보여 줄 대상별 사진 카드. {@code viewer}가 볼 수 있는 사진만 담는다(03 3-1 보이는 범위). 링크에서 숨긴 사진도 회원 화면에는 그대로
   * 보인다(06 PHO-3a). 지운 사진은 빠지지 않고 {@link PhotoCard#erased()}에 흔적을 담는다(불변식 16).
   *
   * @param viewer 권한 검사를 통과한 조회자. 이 호실 기록의 사진만 읽는다
   * @param targets 읽을 대상. 같은 호실 기록 안의 대상이어야 한다
   * @return 넘긴 대상마다 키가 있다. 사진이 없으면 빈 목록이고, 목록은 {@code sortOrder} 오름차순이다
   */
  Map<PhotoTarget, List<PhotoCard>> cards(RoomAccess viewer, Collection<PhotoTarget> targets);

  /**
   * 링크 열람 화면(가입 전 사람)에 보여 줄 대상별 사진. 링크에서 숨긴 사진은 빠지고, 남은 사진에는 5분 주소를 함께 준다. 지운 사진은 주소 없이 흔적만 준다(불변식
   * 16).
   *
   * @param roomRecordId 링크가 가리키는 호실 기록
   * @param targets 읽을 대상. 이 호실 기록 안의 대상이어야 한다
   * @return 넘긴 대상마다 키가 있다. 사진이 없으면 빈 목록이고, 목록은 {@code sortOrder} 오름차순이다
   */
  Map<PhotoTarget, List<LinkPhoto>> linkPhotos(UUID roomRecordId, Collection<PhotoTarget> targets);
}
