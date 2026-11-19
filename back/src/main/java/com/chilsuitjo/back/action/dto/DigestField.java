package com.chilsuitjo.back.action.dto;

/** 내용 지문에 들어가는 칸 하나. 값이 없으면 value를 null로 둔다(빈 글자와 다른 지문이 된다, 05 2-3 「해시 입력 형식」). */
public record DigestField(String name, String value) {}
