package com.chilsuitjo.back.repair.dto;

import java.util.UUID;

/**
 * 원인 설명을 남긴 결과.
 *
 * @param id 새 설명(고쳤으면 새 수정본). 같은 키로 다시 온 요청이면 처음 만든 설명
 * @param replayed 같은 키로 다시 온 요청인지
 */
public record CauseNoteCreated(UUID id, boolean replayed) {}
