package com.chilsuitjo.back.notification.dto;

import java.util.UUID;

/**
 * 연 요청. 같은 대상·종류에 열린 요청이 이미 있으면 새로 만들지 않고 그 요청을 돌려준다({@code created = false}, 기록당 확인 요청은 하나, 03 "문자
 * 도배 막기").
 */
public record RequestHandle(UUID requestId, boolean created) {}
