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
 * @param uploadTickets 1~10장. 목록 순서가 사진 번호({@code photoIndex})다
 * @param quota 부르는 도메인이 정하는 대상 한도. 없으면 {@link PhotoQuota#none()}
 * @param at 행동 시각({@code ActionRequestContext.at()}). {@code photo.created_at}이 된다(불변식 10)
 */
public record PhotoAttachCommand(
    RoomAccess uploader,
    PhotoTarget target,
    PhotoStage stage,
    List<String> uploadTickets,
    PhotoQuota quota,
    Instant at) {}
