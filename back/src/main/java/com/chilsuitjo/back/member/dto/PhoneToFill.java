package com.chilsuitjo.back.member.dto;

import java.util.UUID;

/**
 * 번호 HMAC을 채울 회원 행(#462): 회원 ID와 그때 읽은 번호. 채우기(auth)가 이 번호로 HMAC을 계산하고, 번호가 그대로일 때만 채운다. 번호는 로그에
 * 남기지 않는다.
 */
public record PhoneToFill(UUID memberId, String phone) {}
