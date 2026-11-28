package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.notification.dto.DeliveryQuery;
import com.chilsuitjo.back.notification.dto.SmsMessage;
import com.chilsuitjo.back.notification.dto.SmsSendResult;
import com.chilsuitjo.back.notification.rule.DeliveryOutcome;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 솔라피 문자 발송기(NOTI-11, {@code app.sms.mode=solapi}). 업체 응답을 {@link SmsSendResult}·{@link
 * DeliveryOutcome}으로만 바꾸고 통지·아웃박스·요청 상태는 건드리지 않는다(그것은 {@code OutboxWorker}·{@code
 * DeliveryResultPoller}가 한다).
 *
 * <p>보내기({@link SolapiClient} 구분을 따름):
 *
 * <ul>
 *   <li>접수 → {@code SENT}(메시지 ID)
 *   <li>받았는지 모름(시간 초과·5xx·요청 전체 4xx 등) → {@code UNKNOWN}(보낸 것으로 보고 다시 보내지 않음). 요청 전체 4xx는 접수하지 않았다는
 *       공식 근거가 없어 여기 둔다. 기다림은 전달·열람 증거가 있어야 시작하므로 상대 미확인으로 몰지 않는다
 *   <li>받는 번호 탓으로 확인한 거절(수신번호 형식·080 수신 거부) → {@code REJECTED}
 *   <li>나가지 않음(연결 실패)·호출 한도(429)·등록 실패({@code failedMessageList}의 계정·발신번호·모르는 코드, 공식 문서 "발송되지 않은
 *       메시지") → {@code RETRY}. 계정·잔액·발신번호 문제는 받는 사람의 실패가 아니라서 통지를 바로 실패로 두지 않는다. 다만 지금 계약에는 이 경우만의
 *       결과가 없어 고치지 않으면 다시 시도 기한(3일)이 지나 결국 실패가 된다(A에 전달한 미정)
 * </ul>
 *
 * <p>결과 조회: 메시지 ID가 있는 문자만 업체에 묻는다. 4000이면 {@code DELIVERED}, 3000을 뺀 3xxx면 원인과 상관없이 {@code
 * FAILED}(보내지 못한 사실, 받는 사람 탓이 아닌 코드는 error 로그), 2000(접수 뒤 잔액·한도로 보관될 수 있음)·3000과 찾지 못한 것과 메시지 ID가
 * 없는 것은 {@code NONE}(04 4-2, {@link DeliveryOutcome}). 업체가 돌려준 사용자 정의 칸의 묶음 ID가 묻는 묶음과 다르면 그 결과를 쓰지
 * 않는다(다른 문자의 결과로 기다림을 시작하지 않게).
 */
@Component
@ConditionalOnProperty(name = "app.sms.mode", havingValue = "solapi")
public class SolapiSmsGateway implements SmsGateway {

  private static final Logger log = LoggerFactory.getLogger(SolapiSmsGateway.class);

  /**
   * 결과 조회의 발송 실패 가운데 받는 사람 탓이 아닌 코드(공식 문서 msgstatus): 연동처 서버 오류(3012), 발신번호 형식(3013), 알 수 없는 메시지
   * 상태(3014), 서버 오류(3024), Unknown Code(3048), 변작된 발신번호(3059), 변작으로 등록된 발신번호(3112).
   */
  static final Set<String> OPERATIONAL_RESULT_CODES =
      Set.of("3012", "3013", "3014", "3024", "3048", "3059", "3112");

  private final SolapiClient client;

  public SolapiSmsGateway(SolapiClient client) {
    this.client = client;
  }

  @Override
  public SmsSendResult send(SmsMessage message) {
    SolapiClient.SendOutcome outcome =
        client.send(
            message.phone(),
            message.text(),
            Map.of(SolapiClient.BATCH_FIELD, message.batchLeadId().toString()));
    return switch (outcome) {
      case SolapiClient.Accepted accepted -> SmsSendResult.sent(accepted.messageId());
      case SolapiClient.Unknown unknown -> SmsSendResult.unknown();
      case SolapiClient.NotSent notSent -> SmsSendResult.retry();
      case SolapiClient.Refused refused ->
          refused.kind() == SolapiClient.RefusalKind.MESSAGE
              ? SmsSendResult.rejected()
              : SmsSendResult.retry();
    };
  }

  @Override
  public Map<UUID, DeliveryOutcome> queryResults(List<DeliveryQuery> queries) {
    List<String> ids =
        queries.stream()
            .map(DeliveryQuery::providerMessageId)
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .toList();
    Map<String, SolapiClient.ListedMessage> found = ids.isEmpty() ? Map.of() : client.list(ids);
    Map<UUID, DeliveryOutcome> results = new LinkedHashMap<>();
    for (DeliveryQuery query : queries) {
      String id = query.providerMessageId();
      // 메시지 ID 없이 보낸 문자(결과 모름)는 업체에서 찾을 방법이 정해지지 않아 결과 없음이다(OPS-1)
      results.put(
          query.batchLeadId(), id == null ? DeliveryOutcome.NONE : outcome(query, found.get(id)));
    }
    return results;
  }

  private static DeliveryOutcome outcome(DeliveryQuery query, SolapiClient.ListedMessage message) {
    if (message == null) {
      return DeliveryOutcome.NONE;
    }
    if (message.batchLeadId() != null
        && !message.batchLeadId().equals(query.batchLeadId().toString())) {
      log.warn("솔라피 결과의 묶음 ID가 달라 쓰지 않음: batch={}", query.batchLeadId());
      return DeliveryOutcome.NONE;
    }
    String code = message.statusCode();
    if ("4000".equals(code)) {
      return DeliveryOutcome.DELIVERED;
    }
    if (code != null && code.length() == 4 && code.startsWith("3") && !"3000".equals(code)) {
      // 공식 처리 가이드: 3xxx는 발송 실패(환급 대상)다. 보내지 못했다는 사실이라 원인과 상관없이 실패로 적는다. 다만 발신번호·업체
      // 서버·알 수 없는 상태처럼 받는 사람 탓이 아닌 원인은 운영자가 알아야 해서 따로 남긴다(통지에는 원인 칸이 없음, A에 전달)
      if (OPERATIONAL_RESULT_CODES.contains(code)) {
        log.error("솔라피 발송 실패의 원인이 받는 사람이 아님(설정·업체 확인): code={}", code);
      }
      return DeliveryOutcome.FAILED;
    }
    return DeliveryOutcome.NONE;
  }
}
