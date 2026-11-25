package com.chilsuitjo.back.link.repository;

import com.chilsuitjo.back.link.entity.Link;
import com.chilsuitjo.back.link.rule.LinkKind;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface LinkRepository extends JpaRepository<Link, UUID> {

  Optional<Link> findByTokenHash(String tokenHash);

  @Query("select l.roomRecordId from Link l where l.id = :id")
  Optional<UUID> findRoomRecordIdById(@Param("id") UUID id);

  /** 호실 기록을 먼저 잠근 뒤 링크를 잠근다(09 3-1 잠금 순서, 공개 열람과 무효화의 직렬화). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select l from Link l where l.id = :id")
  @Transactional(propagation = Propagation.MANDATORY)
  Optional<Link> findByIdForUpdate(@Param("id") UUID id);

  /**
   * 같은 호실·자리·기록의 아직 유효한 링크(발급 재사용, {@code InviteLinks.issueInvite}). 기록 칸이 {@code null}이면 {@code IS
   * NULL}로 찾는다. 호실을 잠근 뒤 부르므로 같은 조건의 발급끼리는 한 줄로 선다.
   */
  Optional<Link>
      findFirstByRoomRecordIdAndPartyIdAndKindAndRepairCaseIdAndInspectionIdAndInvalidatedAtIsNull(
          UUID roomRecordId, UUID partyId, LinkKind kind, UUID repairCaseId, UUID inspectionId);

  /** 호실을 잠근 뒤 그 호실의 유효한 링크를 한 종류만 잠근다(호실 → 링크, 09 3-1). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select l from Link l where l.roomRecordId = :room and l.kind = :kind"
          + " and l.invalidatedAt is null order by l.id")
  @Transactional(propagation = Propagation.MANDATORY)
  List<Link> lockValid(@Param("room") UUID roomRecordId, @Param("kind") LinkKind kind);

  /** {@link #lockValid}와 같고 한 자리만. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select l from Link l where l.roomRecordId = :room and l.partyId = :party and l.kind = :kind"
          + " and l.invalidatedAt is null order by l.id")
  @Transactional(propagation = Propagation.MANDATORY)
  List<Link> lockValidForParty(
      @Param("room") UUID roomRecordId, @Param("party") UUID partyId, @Param("kind") LinkKind kind);

  /**
   * 링크를 찾았는데 열지 못한 횟수를 하나 올린다(무효 링크, 호실 상태로 막힘, 내용을 못 읽음, pipeline 거절. 05 {@code
   * link.failed_view_count}: 링크를 식별할 수 있는 실패만 센다). 이 횟수만으로 링크를 무효로 하지 않는다. 자기 트랜잭션으로 돌아 열람이 되돌려져도
   * 남는다.
   */
  @Transactional
  @Modifying
  @Query("update Link l set l.failedViewCount = l.failedViewCount + 1 where l.id = :id")
  int countFailedView(@Param("id") UUID id);
}
