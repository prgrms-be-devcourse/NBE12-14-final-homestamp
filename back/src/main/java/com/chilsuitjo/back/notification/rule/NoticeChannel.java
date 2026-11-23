package com.chilsuitjo.back.notification.rule;

/** 통지를 보내는 길(05 {@code notice.channel}). 문자를 끈 회원·인증이 풀린 계정은 {@code IN_APP}(2주차). */
public enum NoticeChannel {
  SMS,
  IN_APP
}
