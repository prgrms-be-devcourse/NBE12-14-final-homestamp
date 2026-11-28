package com.chilsuitjo.back.repair.dto;

import com.chilsuitjo.back.repair.rule.StaleReason;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 오래 멈춘 수리 건 한 줄(다른 도메인이 읽는 값, 홈 "오래 멈춘 건", 06 REP-6a·6e, #370 B-2). 값은 수리 건 목록({@link
 * RepairCaseList})과 같은 계산이다: 보이는 판·부위 이름·{@code lastActionAt}이 목록·상세와 같다.
 *
 * @param repairCaseId 수리 건
 * @param no 보이는 판 번호
 * @param title 보이는 판의 제목
 * @param partName 부위 이름(목록·상세와 같은 이름)
 * @param reason 멈춘 이유
 * @param lastActionAt 보는 사람에게 보이는 이 건의 마지막 당사자 행동 시각(06 4-6). 홈은 이 값으로 "N일째"를 센다
 */
public record StaleRepairCase(
    UUID repairCaseId,
    int no,
    String title,
    String partName,
    StaleReason reason,
    Instant lastActionAt) {

  public StaleRepairCase {
    Objects.requireNonNull(repairCaseId, "repairCaseId");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(partName, "partName");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(lastActionAt, "lastActionAt");
  }
}
