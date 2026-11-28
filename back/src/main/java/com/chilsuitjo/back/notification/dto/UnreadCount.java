package com.chilsuitjo.back.notification.dto;

/** 안 읽은 알림 수({@code GET /api/me/notifications/unread-count}). 만료된 알림은 세지 않는다. */
public record UnreadCount(long count) {}
