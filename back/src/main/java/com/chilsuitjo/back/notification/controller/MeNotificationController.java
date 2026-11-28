package com.chilsuitjo.back.notification.controller;

import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.notification.dto.InAppNotificationView;
import com.chilsuitjo.back.notification.dto.UnreadCount;
import com.chilsuitjo.back.notification.service.InAppNotificationService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 서비스 안 알림 API(06 4-1 {@code /api/me/notifications}, NOTI-7). */
@RestController
@RequiredArgsConstructor
public class MeNotificationController {

  private final InAppNotificationService notifications;

  /** 내 알림 목록(만료 전, 응답 필요 먼저, 새것부터). 30일 뒤 만료라 쪽 나누기 없이 배열로 준다. */
  @GetMapping("/api/me/notifications")
  public List<InAppNotificationView> list(@LoginMember UUID memberId) {
    return notifications.list(memberId);
  }

  @GetMapping("/api/me/notifications/unread-count")
  public UnreadCount unreadCount(@LoginMember UUID memberId) {
    return notifications.unreadCount(memberId);
  }

  /** 읽음(응답이 아님, 기다림 타이머는 그대로). 남의 알림이면 404. */
  @PostMapping("/api/me/notifications/{id}/read")
  public ResponseEntity<Void> read(@PathVariable UUID id, @LoginMember UUID memberId) {
    notifications.read(memberId, id);
    return ResponseEntity.noContent().build();
  }
}
