package com.chilsuitjo.back.room.dto;

import java.util.UUID;

/**
 * 호실 기록 만들기 결과.
 *
 * @param roomRecordId 만든 호실 기록. 같은 키로 다시 온 요청이면 처음 만든 호실
 * @param replayed 같은 키로 다시 온 요청이면 true(06 4-0 {@code Idempotent-Replayed})
 */
public record RoomCreated(UUID roomRecordId, boolean replayed) {}
