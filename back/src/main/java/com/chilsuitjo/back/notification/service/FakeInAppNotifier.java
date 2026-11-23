package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.dto.InfoCommand;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Service;

/**
 * 서비스 안 알림 가짜. 받은 알림을 메모리에 쌓기만 한다.
 *
 * <p>실제 구현(NOTI-7, {@code in_app_notification} 저장)이 들어오면 이 클래스를 지운다.
 */
@Service
public class FakeInAppNotifier implements InAppNotifier {

  private final List<InfoCommand> sent = new CopyOnWriteArrayList<>();

  @Override
  public void notify(InfoCommand command) {
    sent.add(command);
  }

  /** 지금까지 남긴 알림(테스트에서 확인). */
  public List<InfoCommand> sent() {
    return List.copyOf(sent);
  }
}
