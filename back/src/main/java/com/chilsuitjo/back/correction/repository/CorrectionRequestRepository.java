package com.chilsuitjo.back.correction.repository;

import com.chilsuitjo.back.correction.entity.CorrectionRequest;
import com.chilsuitjo.back.correction.rule.CorrectionStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 고친 내용({@code state.correction_request}). correction 안에서만 쓴다. */
public interface CorrectionRequestRepository extends JpaRepository<CorrectionRequest, UUID> {

  /** 이 호실의 고친 내용. 다른 호실 것이면 비어 있다(ROOM-2a). */
  Optional<CorrectionRequest> findByIdAndRoomRecordId(UUID id, UUID roomRecordId);

  /** 그 기록·칸에서 지금 기다리는 고친 내용(칸마다 하나, {@code ux_correction_waiting}). */
  Optional<CorrectionRequest> findByTargetKeyAndFieldAndStatus(
      String targetKey, String field, CorrectionStatus status);

  /** 그 기록에 이 상태의 고친 내용이 남았나(기다리는 것이 0개가 되면 확인 요청을 닫음, 05 2-4). */
  boolean existsByTargetKeyAndStatus(String targetKey, CorrectionStatus status);

  /** 이 호실에서 수리 건이 대상인 고친 내용 모두(상태와 상관없이). */
  List<CorrectionRequest> findByRoomRecordIdAndRepairCaseIdNotNull(UUID roomRecordId);

  /** 이 호실에서 이 상태들인 고친 내용 모두(정산 완료로 닫을 때). */
  List<CorrectionRequest> findByRoomRecordIdAndStatusIn(
      UUID roomRecordId, Collection<CorrectionStatus> statuses);

  /** 그 기록에서 이 상태인 고친 내용 모두(처리 완료 등으로 닫을 때). */
  List<CorrectionRequest> findByTargetKeyAndStatus(String targetKey, CorrectionStatus status);

  /** 그 기록의 고친 내용 모두(만든 순서, 같은 시각이면 ID 순). */
  List<CorrectionRequest> findByTargetKeyOrderByCreatedAtAscIdAsc(String targetKey);

  /** 그 기록의 고친 내용 ID 모두(사진 한도를 셀 때처럼 ID만 필요할 때). */
  @Query("SELECT c.id FROM CorrectionRequest c WHERE c.targetKey = :targetKey")
  List<UUID> findIdsByTargetKey(@Param("targetKey") String targetKey);

  /** 그 기록에서 고친 내용을 마지막으로 다시 보낸 시각({@code retry_of_id}가 있는 행의 만든 시각 중 가장 늦은 것). 없으면 null. */
  @Query(
      "SELECT MAX(c.createdAt) FROM CorrectionRequest c"
          + " WHERE c.targetKey = :targetKey AND c.retryOfId IS NOT NULL")
  Instant findLastResubmitAt(@Param("targetKey") String targetKey);
}
