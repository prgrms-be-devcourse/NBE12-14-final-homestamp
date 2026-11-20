package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.dto.InfoCommand;

/**
 * 응답이 필요 없는 서비스 안 알림을 남긴다(03 K "서비스 안 알림": 알려 줌·서비스 안내). 원인 행동의 {@code save} 안에서 부른다. 읽음은 확인이 아니다.
 */
public interface InAppNotifier {

  void notify(InfoCommand command);
}
