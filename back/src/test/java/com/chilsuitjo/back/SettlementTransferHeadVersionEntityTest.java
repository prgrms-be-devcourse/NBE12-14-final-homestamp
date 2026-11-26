package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.Uuids;
import com.chilsuitjo.back.settlement.entity.Settlement;
import com.chilsuitjo.back.settlement.entity.SettlementTransfer;
import com.chilsuitjo.back.settlement.entity.SettlementTransferVersion;
import com.chilsuitjo.back.settlement.rule.TransferDirection;
import com.chilsuitjo.back.settlement.rule.TransferResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;

class SettlementTransferHeadVersionEntityTest extends SchemaTestSupport {
  private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
  private static final LocalDate PAID_ON = LocalDate.of(2026, 11, 25);

  @PersistenceContext private EntityManager entityManager;

  @Test
  void 송금_머리와_첫_판을_한_트랜잭션에서_저장하고_다시_읽는다() {
    SettlementTransfer transfer = newTransfer();
    SettlementTransferVersion first = firstVersion(transfer);
    assertThat(first.isNew()).isTrue();

    // save가 persist를 선택하면 넘긴 인스턴스를 그대로 돌려준다.
    assertThat(new SimpleJpaRepository<>(SettlementTransfer.class, entityManager).save(transfer))
        .isSameAs(transfer);
    assertThat(
            new SimpleJpaRepository<>(SettlementTransferVersion.class, entityManager).save(first))
        .isSameAs(first);
    entityManager.flush();
    assertThat(first.isNew()).isFalse();
    // 테스트가 끝날 때 롤백하므로 커밋 시점의 FK 검사를 여기서 실행한다.
    jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
    entityManager.clear();

    SettlementTransfer loaded = entityManager.find(SettlementTransfer.class, transfer.getId());
    assertThat(loaded.getVersion()).isZero();
    assertThat(loaded.getDirection()).isEqualTo(TransferDirection.REFUND);
    assertThat(loaded.getRecordedBySide()).isEqualTo(PartySide.LANDLORD);
    assertThat(loaded.getResponse()).isEqualTo(TransferResponse.NONE);
    assertThat(loaded.getCurrentVersionId()).isEqualTo(first.getId());
    SettlementTransferVersion loadedFirst =
        entityManager.find(SettlementTransferVersion.class, loaded.getCurrentVersionId());
    assertThat(loadedFirst.isNew()).isFalse();
    assertThat(loadedFirst.getSettlementTransferId()).isEqualTo(loaded.getId());
    assertThat(loadedFirst.getAmount()).isEqualTo(9_845_000);
    assertThat(loadedFirst.getPaidOn()).isEqualTo(PAID_ON);
  }

  @Test
  void 첫_판이_없으면_지연_외래_키_검사에_실패한다() {
    entityManager.persist(newTransfer());
    entityManager.flush();

    assertThatThrownBy(() -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasStackTraceContaining("fk_settlement_transfer_current_version");
  }

  @Test
  void 첫_판부터_저장하면_즉시_외래_키_검사에_실패한다() {
    SettlementTransfer transfer = newTransfer();
    entityManager.persist(firstVersion(transfer));
    entityManager.persist(transfer);

    assertThatThrownBy(entityManager::flush)
        .isInstanceOf(PersistenceException.class)
        .hasStackTraceContaining("fk_settlement_transfer_version_settlement_transfer_id_room");
  }

  private SettlementTransfer newTransfer() {
    UUID roomId = room();
    Settlement settlement = new Settlement(roomId, NOW);
    entityManager.persist(settlement);
    return new SettlementTransfer(
        Uuids.v7(),
        roomId,
        settlement.getId(),
        TransferDirection.REFUND,
        Uuids.v7(),
        PartySide.LANDLORD,
        null,
        NOW);
  }

  private SettlementTransferVersion firstVersion(SettlementTransfer transfer) {
    return new SettlementTransferVersion(
        transfer.getCurrentVersionId(),
        transfer.getRoomRecordId(),
        transfer.getId(),
        1,
        9_845_000,
        PAID_ON,
        null,
        NOW);
  }
}
