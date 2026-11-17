package com.chilsuitjo.back.global.response;

import java.util.UUID;

/** 만드는 API의 201 응답. 같은 키로 다시 와도 같은 모양으로 돌려준다(06 4-0). */
public record CreatedResponse(UUID id) {}
