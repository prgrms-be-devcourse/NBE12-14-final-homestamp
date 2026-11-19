package com.chilsuitjo.back.action.dto;

/**
 * {@code ActionPipeline.execute}(행동 하나)의 결과. 컨트롤러는 {@code state}로 응답을 만들고, {@code
 * action.replayed()}면 {@code Idempotent-Replayed} 머리를 붙인다.
 *
 * @param state 저장한 뒤 상태. 다시 온 요청이면 다시 읽은 지금 상태
 */
public record PipelineResult<S>(S state, ActionResult action) {}
