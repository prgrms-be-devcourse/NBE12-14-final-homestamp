package com.chilsuitjo.back.action.dto;

/** 내용 지문에 들어가는 칸 하나. value가 null이면 정해진 표시로 바꿔 넣는다(형식은 해시 체인을 만들 때 고정). */
public record DigestField(String name, String value) {}
