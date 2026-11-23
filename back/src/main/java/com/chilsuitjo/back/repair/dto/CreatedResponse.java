package com.chilsuitjo.back.repair.dto;

import java.util.UUID;

/** 만든 기록의 ID(201 응답 본문). 다시 온 요청이어도 처음과 같은 모양이다(06 4-0). */
public record CreatedResponse(UUID id) {}
