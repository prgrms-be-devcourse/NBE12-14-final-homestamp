package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.global.common.Uuids;
import com.chilsuitjo.back.notification.repository.OutboxRepository;
import com.chilsuitjo.back.notification.rule.OutboxKind;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 고아 사진 파일 확인 줄({@code outbox} ORPHAN_CHECK, 05 불변식 17, 04 5-1 "고아 파일 확인"). 사진을 {@code photos/}로
 * 복사하기 전에 파일마다 한 줄을 커밋하고, 카드를 저장하는 트랜잭션에서 지운다. 남은 줄만 확인 작업(OPS-2, 별도 PR)이 돌며, 줄을 잠근 채 카드가 정말 없는지 다시
 * 보고 파일을 지운다.
 *
 * <p>중복 방지 키는 {@code orphan-check:<photos/ 원본 키>}다. payload에는 지울 두 키만 둔다.
 */
@Service
public class OrphanCheckOutbox {

  private static final String KEY_PREFIX = "orphan-check:";
  private static final JsonMapper JSON = new JsonMapper();

  private final OutboxRepository outbox;

  OrphanCheckOutbox(OutboxRepository outbox) {
    this.outbox = outbox;
  }

  /**
   * 정식 사진 파일 한 쌍.
   *
   * @param originalKey {@code photos/…/original.jpg}
   * @param thumbKey {@code photos/…/thumb.jpg}
   */
  public record PhotoFiles(String originalKey, String thumbKey) {}

  /**
   * 확인 줄을 <b>새 트랜잭션에서 커밋</b>한다. 부르는 쪽이 복사 뒤에 멈추거나 롤백돼도 줄은 남아야 하기 때문이다. 같은 키의 줄이 남아 있으면(앞선 시도가 멈춤)
   * 시각을 새로 정하고, 확인 작업이 그 줄을 잡고 있으면 끝날 때까지 기다린다.
   *
   * @param checkAt 확인 작업이 돌 시각({@code orphan.check-after} 뒤)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void schedule(List<PhotoFiles> files, Instant now, Instant checkAt) {
    for (PhotoFiles file : files) {
      Map<String, String> payload = new LinkedHashMap<>();
      payload.put("originalKey", file.originalKey());
      payload.put("thumbKey", file.thumbKey());
      outbox.upsertOrphanCheck(
          Uuids.v7(),
          dedupeKey(file.originalKey()),
          JSON.writeValueAsString(payload),
          checkAt,
          now);
    }
  }

  /** 카드를 저장하는 트랜잭션에서 확인 줄을 지운다. 확인 작업이 잡고 있으면 끝날 때까지 기다린다. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void clear(Collection<String> originalKeys) {
    if (originalKeys.isEmpty()) {
      return;
    }
    outbox.deleteByKindAndDedupeKeys(
        OutboxKind.ORPHAN_CHECK, originalKeys.stream().map(OrphanCheckOutbox::dedupeKey).toList());
  }

  private static String dedupeKey(String originalKey) {
    return KEY_PREFIX + originalKey;
  }
}
