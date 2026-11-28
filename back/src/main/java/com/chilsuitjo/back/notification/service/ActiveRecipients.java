package com.chilsuitjo.back.notification.service;

import java.util.UUID;

/**
 * 서비스 안 알림을 받을 회원이 지금 활동 중인지 보고, 이 트랜잭션이 끝날 때까지 그 상태를 붙잡는다(#424). member가 구현한다.
 *
 * <ul>
 *   <li>부르는 쪽 트랜잭션 안에서만 부른다({@code MANDATORY}). 구현은 회원 행을 {@code FOR SHARE}로 잡아 탈퇴의 쓰기 잠금과 서로 기다린다
 *   <li>알림 저장이 먼저 커밋되면 탈퇴는 그 뒤에 그 회원의 알림을 모두 지운다({@link MemberNotifications#deleteAllOf}). 탈퇴가 먼저
 *       커밋되면 이 호출은 기다렸다가 {@code false}를 돌려줘 알림을 만들지 않는다. 탈퇴 트랜잭션 안에서 부르면 자기 트랜잭션의 탈퇴 상태를 보고 {@code
 *       false}다
 *   <li>잠금 순서는 호실 → 회원 행이다(decisions 2026-11-29 #424). 호실을 잠그는 업무는 호실을 먼저 잠근 뒤 부른다. 탈퇴도 호실 → 회원 행
 *       순서로 잡는다
 * </ul>
 */
public interface ActiveRecipients {

  /** 활동 중인 회원이면 그 행을 공유 잠금하고 {@code true}. 탈퇴했거나 없으면 {@code false}. */
  boolean holdActive(UUID memberId);
}
