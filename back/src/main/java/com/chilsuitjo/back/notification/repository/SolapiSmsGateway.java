package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.notification.dto.DeliveryQuery;
import com.chilsuitjo.back.notification.dto.SmsMessage;
import com.chilsuitjo.back.notification.dto.SmsSendResult;
import com.chilsuitjo.back.notification.rule.DeliveryOutcome;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 * FAILED}(보내지 못한 사실, 받는 사람 탓이 아닌 코드는 error 로그), 공식 표의 발송불가 2xxx({@link #PLATFORM_REJECTED_CODES})도
 * {@code FAILED}, 2000(발송대기: 잔액·한도로 보류)·3000·표에 없는 2xxx·1xxx와 찾지 못한 것과 메시지 ID가 없는 것은 {@code
 * NONE}(04 4-2, {@link DeliveryOutcome}). 업체가 돌려준 사용자 정의 칸의 묶음 ID가 묻는 묶음과 다르면 그 결과를 쓰지 않는다(다른 문자의
 * 결과로 기다림을 시작하지 않게).
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

  /**
   * 결과 조회의 발송불가(2xxx, 2000 제외) 가운데 공식 상태 코드 표에 있는 것. 공식 안내: "이통사 전달 전 플랫폼 내부 검증 단계에서 발송이 실패한
   * 경우"(guides/message-status-codes 발송불가(2XXX)), "검증 단계에서 발송이 실패하는 경우, 상태코드 (2XXX)로 반환되며 발송은 진행되지
   * 않습니다"(message-processing). 그래서 그 문자는 실패다. 2000은 잔액·하루 한도로 보류된 발송대기라 결과 없음이고, 표에 없는 2xxx는 뜻을 몰라
   * 결과 없음으로 둔다.
   */
  static final Set<String> PLATFORM_REJECTED_CODES =
      Set.of("2011", "2012", "2024", "2025", "2061", "2062", "2064", "2065", "2230", "2254");

  /** 발송불가 가운데 받는 사람이 수신을 거부한 것. 나머지는 계정·발신번호·형식·업체 원인이라 운영자가 알아야 한다. */
  private static final Set<String> RECIPIENT_REJECTED_CODES = Set.of("2061", "2065");

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
    Map<String, Integer> unknownCodes = new TreeMap<>();
    for (DeliveryQuery query : queries) {
      String id = query.providerMessageId();
      // 메시지 ID 없이 보낸 문자(결과 모름)는 업체에서 찾을 방법이 정해지지 않아 결과 없음이다(OPS-1)
      results.put(
          query.batchLeadId(),
          id == null ? DeliveryOutcome.NONE : outcome(query, found.get(id), unknownCodes));
    }
    if (!unknownCodes.isEmpty()) {
      // 결과 없음은 다음 주기에 다시 묻는다. 문자마다 남기면 3일 동안 같은 줄이 쌓여 한 번의 조회에 코드별 개수 한 줄만 남긴다(ID·번호 없음)
      log.error("솔라피 결과가 접수 중·처리 중 오류인데 뜻을 몰라 결과 없음으로 둠(계정·발신번호 확인): codes={}", unknownCodes);
    }
    return results;
  }

  private static DeliveryOutcome outcome(
      DeliveryQuery query, SolapiClient.ListedMessage message, Map<String, Integer> unknownCodes) {
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
    if (PLATFORM_REJECTED_CODES.contains(code)) {
      // 결과 조회가 실패를 알 뿐이다. 다시 보내기·혼자 진행은 이 결과로 정하지 않는다(통지에는 원인 칸이 없음, A에 전달). 실패가 되면 더
      // 묻지 않아 이 로그는 문자마다 한 번이다
      if (!RECIPIENT_REJECTED_CODES.contains(code)) {
        log.error("솔라피가 이통사 전달 전 발송불가로 끝냄(계정·발신번호·형식 확인): code={}", code);
      }
      return DeliveryOutcome.FAILED;
    }
    if (code != null
        && code.length() == 4
        && (code.startsWith("1") || code.startsWith("2"))
        && !"2000".equals(code)) {
      // 표에 없는 2xxx와 접수 뒤 조회의 1xxx는 끝난 실패인지 공식 근거가 없어 결과 없음으로 둔다(전달 확인 기한에 실패, 04 6장)
      unknownCodes.merge(code, 1, Integer::sum);
    }
    return DeliveryOutcome.NONE;
  }
}
