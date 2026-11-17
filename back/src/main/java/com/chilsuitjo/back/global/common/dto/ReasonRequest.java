package com.chilsuitjo.back.global.common.dto;

/** 사유만 받는 요청. 비어 있으면 {@code REASON_REQUIRED}. */
public record ReasonRequest(String reason) {}
