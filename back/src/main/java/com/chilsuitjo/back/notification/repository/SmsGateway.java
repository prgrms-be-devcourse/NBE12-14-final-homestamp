package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.notification.dto.DeliveryQuery;
import com.chilsuitjo.back.notification.dto.SmsMessage;
import com.chilsuitjo.back.notification.dto.SmsSendResult;
import com.chilsuitjo.back.notification.rule.DeliveryOutcome;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 문자 업체 호출 부품(04 4-2 "외부 호출 부품은 {@code notification/repository/}"). 솔라피 구현은 {@link
 * SolapiSmsGateway}(NOTI-11, {@code app.sms.mode=solapi}), local·test는 {@link FakeSmsGateway}다.
 *
 * <p>번호·본문을 로그·예외 문구에 남기지 않는다. 결과는 예외가 아니라 값으로 돌려준다: 연결 거부처럼 업체에 닿지 않은 게 확실하면 {@code RETRY}, 시간
 * 제한처럼 닿았는지 모르면 {@code UNKNOWN}(보낸 것으로 봄). 작업자는 던진 예외를 모두 {@code UNKNOWN}으로 다루므로, 다시 보내도 되는 실패를 예외로
 * 던지면 다시 보내지 않는다.
 */
public interface SmsGateway {

  SmsSendResult send(SmsMessage message);

  /**
   * 보낸 문자들의 전달 결과를 묻는다(04 4-2 "전달 결과는 조회 API로", 웹훅 없음). 묻는 문자마다 결과 하나를 {@code batchLeadId}를 키로
   * 돌려주고, 아직 결과가 없거나 업체에서 찾지 못한 문자는 {@link DeliveryOutcome#NONE}으로 넣는다(빠뜨리지 않음).
   *
   * <p>업체 조회 자체가 실패하면(연결 실패, 업체 오류) 예외를 던진다. 결과 없음({@code NONE})과 달리 그때는 아무 결과도 알 수 없다. 보내기와 달리 조회는
   * 다시 해도 문자가 두 번 가지 않으므로 예외로 알린다.
   *
   * <p>업체 결과 코드를 {@link DeliveryOutcome}으로 바꾸는 것과 어떤 키({@code providerMessageId}, 묶음 ID 등)로 업체에
   * 물을지는 실제 업체 구현(NOTI-11)이 정한다. 솔라피 구현은 메시지 ID로만 묻고, 메시지 ID가 없는 문자는 결과 없음이다(찾는 방법은 OPS-1).
   */
  Map<UUID, DeliveryOutcome> queryResults(List<DeliveryQuery> queries);
}
