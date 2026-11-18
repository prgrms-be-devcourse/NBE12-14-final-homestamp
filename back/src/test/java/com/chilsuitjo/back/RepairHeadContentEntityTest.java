package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.Uuids;
import com.chilsuitjo.back.repair.entity.RepairCase;
import com.chilsuitjo.back.repair.entity.RepairContent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;

/**
 * 수리 건(머리)과 첫 판은 서로의 ID가 필요하다. 앱이 두 ID를 먼저 만들어 한 트랜잭션에 저장할 수 있는지 본다(05 0장 {@code id}, PR #63 리뷰).
 *
 * <p>테스트는 끝나면 되돌리므로, 커밋 때 하는 지연 FK 검사는 {@code SET CONSTRAINTS ALL IMMEDIATE}로 그 자리에서 당겨 한다. 판의
 * {@code repair_case_id} FK는 지연이 아니라서 머리를 먼저 저장한다.
 */
class RepairHeadContentEntityTest extends SchemaTestSupport {

  private static final Instant NOW = Instant.parse("2026-11-18T00:00:00Z");

  @PersistenceContext private EntityManager entityManager;

  @Test
  void 앱이_만든_ID로_수리_건과_첫_판을_한_트랜잭션에_저장한다() {
    UUID room = room();
    UUID caseId = Uuids.v7();
    UUID contentId = Uuids.v7();
    RepairCase repairCase = new RepairCase(caseId, room, contentId, PartySide.TENANT, null, NOW);
    RepairContent content = firstContent(contentId, room, caseId);

    // save가 merge로 가면 복사본을 돌려준다. 같은 객체면 SELECT 없이 persist(INSERT 한 번)로 갔다.
    RepairCase savedCase =
        new SimpleJpaRepository<>(RepairCase.class, entityManager).save(repairCase);
    RepairContent savedContent =
        new SimpleJpaRepository<>(RepairContent.class, entityManager).save(content);
    entityManager.flush();

    assertThat(savedCase).isSameAs(repairCase);
    assertThat(savedContent).isSameAs(content);
    assertDoesNotThrow(() -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
    assertThat(
            jdbc.queryForObject(
                "SELECT current_content_id FROM repair_case WHERE id = ?", UUID.class, caseId))
        .isEqualTo(contentId);
  }

  @Test
  void 첫_판_없이_수리_건만_저장하면_커밋_때_지연_FK가_거절한다() {
    UUID room = room();
    RepairCase repairCase =
        new RepairCase(Uuids.v7(), room, Uuids.v7(), PartySide.TENANT, null, NOW);
    entityManager.persist(repairCase);
    entityManager.flush();

    var error =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
    assertThat(error.getMessage()).contains("fk_repair_case_current_content");
  }

  @Test
  void 저장한_뒤와_다시_읽은_판은_새_행이_아니다() {
    UUID room = room();
    UUID caseId = Uuids.v7();
    UUID contentId = Uuids.v7();
    entityManager.persist(new RepairCase(caseId, room, contentId, PartySide.TENANT, null, NOW));
    RepairContent content = firstContent(contentId, room, caseId);
    assertThat(content.isNew()).isTrue();

    entityManager.persist(content);
    entityManager.flush();
    assertThat(content.isNew()).isFalse();

    entityManager.clear();
    assertThat(entityManager.find(RepairContent.class, contentId).isNew()).isFalse();
  }

  private RepairContent firstContent(UUID contentId, UUID room, UUID caseId) {
    return new RepairContent(
        contentId, room, caseId, 1, part(room), "천장 누수", null, null, PartySide.TENANT, NOW);
  }
}
