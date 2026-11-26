package com.chilsuitjo.back.link.dto;

/** 링크 열람 요청. 토큰은 주소가 아니라 본문으로 받는다(접속 로그에 남지 않게, 06 4-0·LINK-2b). */
public record LinkViewRequest(String token) {

  /** 토큰이 로그에 찍히지 않게 숨긴다. */
  @Override
  public String toString() {
    return "LinkViewRequest[token=***]";
  }
}
