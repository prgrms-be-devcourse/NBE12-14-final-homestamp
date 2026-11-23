package com.chilsuitjo.back.member.rule;

/** 회원 상태(05 2-1 {@code member.status}). 탈퇴하면 행은 남기고 신원 칸을 지운다. */
public enum MemberStatus {
  ACTIVE,
  WITHDRAWN
}
