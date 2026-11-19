package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.dto.PartyFacts;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.rule.PartyPresence;
import com.chilsuitjo.back.notification.rule.RoomPhase;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FakeCounterpartReaderTest {

  private final FakeCounterpartReader reader = new FakeCounterpartReader();
  private final UUID roomRecordId = UUID.randomUUID();

  @Test
  void 행동하는_측을_mine으로_반대_측을_counterpart로_돌려준다() {
    PartyFacts tenant =
        new PartyFacts(
            UUID.randomUUID(),
            UUID.randomUUID(),
            PartyPresence.CONNECTED,
            null,
            true,
            false,
            "정*윤");
    reader.seed(roomRecordId, PartySide.TENANT, tenant);

    RoomParties fromLandlord = reader.read(roomRecordId, PartySide.LANDLORD);

    assertThat(fromLandlord.counterpart()).isEqualTo(tenant);
    assertThat(fromLandlord.mine().presence()).isEqualTo(PartyPresence.EMPTY);
  }

  @Test
  void 단계를_정하지_않으면_거주_중이고_정하면_그_단계다() {
    reader.seed(roomRecordId, PartySide.TENANT, PartyFacts.empty());

    assertThat(reader.read(roomRecordId, PartySide.TENANT).phase()).isEqualTo(RoomPhase.LIVING);

    reader.seedPhase(roomRecordId, RoomPhase.ENDED);

    assertThat(reader.read(roomRecordId, PartySide.TENANT).phase()).isEqualTo(RoomPhase.ENDED);
  }

  @Test
  void 없는_호실은_NOT_FOUND() {
    assertThatThrownBy(() -> reader.read(UUID.randomUUID(), PartySide.TENANT))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
  }
}
