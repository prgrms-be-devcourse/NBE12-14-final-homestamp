package com.chilsuitjo.back.correction.repository;

import com.chilsuitjo.back.correction.entity.CorrectionRequest;
import com.chilsuitjo.back.correction.rule.CorrectionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
