package com.chilsuitjo.back.room.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.dto.PartyFacts;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.rule.PartyPresence;
import com.chilsuitjo.back.notification.rule.RoomPhase;
import com.chilsuitjo.back.notification.service.CounterpartReader;
import com.chilsuitjo.back.room.rule.PartyStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

/** 자리 상태 읽기(09 3장 "기다림·통지"). 진짜 {@code room_record}·{@code party}·{@code member}를 읽는다. */
class JpaCounterpartReaderTest extends RoomReadersTestSupport {

  private static final Instant STOPPED = Instant.parse("2026-11-01T00:00:00Z");

  @Autowired private CounterpartReader reader;

  @Test
  void 진짜_구현이_쓰인다() {
    assertThat(reader).isInstanceOf(JpaCounterpartReader.class);
  }

  @Test
  void 행동하는_측이_mine이고_반대_측이_counterpart다() {
    UUID room = room("LIVING");
    UUID tenant = member(true);
    UUID tenantParty = party(room, "TENANT", tenant, "CONNECTED", null);
    UUID landlordParty = party(room, "LANDLORD", null, "INVITED", null);

    RoomParties fromTenant = reader.read(room, PartySide.TENANT);

    assertThat(fromTenant.phase()).isEqualTo(RoomPhase.LIVING);
    assertThat(fromTenant.mine())
        .isEqualTo(
            new PartyFacts(tenantParty, tenant, PartyPresence.CONNECTED, null, true, false, null));
    assertThat(fromTenant.counterpart())
        .isEqualTo(
            new PartyFacts(landlordParty, null, PartyPresence.INVITED, null, false, false, null));

    RoomParties fromLandlord = reader.read(room, PartySide.LANDLORD);
    assertThat(fromLandlord.mine().partyId()).isEqualTo(landlordParty);
    assertThat(fromLandlord.counterpart().partyId()).isEqualTo(tenantParty);
  }

  @Test
  void 행이_없는_측은_빈_자리다() {
    UUID room = room("LIVING");
    party(room, "TENANT", member(true), "CONNECTED", null);

    assertThat(reader.read(room, PartySide.TENANT).counterpart()).isEqualTo(PartyFacts.empty());
  }

  @Test
  void 공유_중단은_연결된_자리에_중단_시각이_있는_것이다() {
    UUID room = room("LIVING");
    party(room, "TENANT", member(true), "CONNECTED", null);
    party(room, "LANDLORD", member(true), "SHARING_STOPPED", STOPPED);

    RoomParties parties = reader.read(room, PartySide.TENANT);

    assertThat(parties.counterpart().presence()).isEqualTo(PartyPresence.CONNECTED);
    assertThat(parties.counterpart().sharingStoppedAt()).isEqualTo(STOPPED);
    assertThat(parties.counterpartSharingStoppedApplies()).isTrue();
  }

  @Test
  void 탈퇴한_자리는_회원도_문자_설정도_없고_공유_중단_제한도_보지_않는다() {
    UUID room = room("LIVING");
    party(room, "TENANT", member(true), "SHARING_STOPPED", STOPPED);
    // member_id·공유 중단 시각이 남아 있어도 자리 상태(WITHDRAWN)로 판단한다(03 B)
    party(room, "LANDLORD", member(true), "WITHDRAWN", STOPPED);

    RoomParties parties = reader.read(room, PartySide.TENANT);

    assertThat(parties.counterpart().presence()).isEqualTo(PartyPresence.WITHDRAWN);
    assertThat(parties.counterpart().memberId()).isNull();
    assertThat(parties.counterpart().smsEnabled()).isFalse();
    assertThat(parties.counterpartSharingStoppedApplies()).isFalse();
    // 상대가 탈퇴하면 내 공유 중단 제한도 풀린다(06 CONS-3p)
    assertThat(parties.mySharingStoppedApplies()).isFalse();
  }

  @Test
  void 문자를_끈_회원은_문자_설정이_거짓이다() {
    UUID room = room("LIVING");
    party(room, "TENANT", member(false), "CONNECTED", null);

    assertThat(reader.read(room, PartySide.TENANT).mine().smsEnabled()).isFalse();
  }

  @Test
  void 호실_단계는_호실_상태를_따른다() {
    assertThat(reader.read(room("ENDED"), PartySide.TENANT).phase()).isEqualTo(RoomPhase.ENDED);
    assertThat(reader.read(room("SETTLED"), PartySide.TENANT).phase()).isEqualTo(RoomPhase.SETTLED);
  }

  @Test
  void 수신_거부와_가린_이름은_아직_채우지_않는다() {
    UUID room = room("LIVING");
    party(room, "TENANT", member(true), "CONNECTED", null);

    PartyFacts mine = reader.read(room, PartySide.TENANT).mine();

    // 문자 보내기 기능 PR에서 채운다(JpaCounterpartReader 설명)
    assertThat(mine.optedOut()).isFalse();
    assertThat(mine.maskedName()).isNull();
  }

  @Test
  void 없는_호실은_NOT_FOUND() {
    assertThatThrownBy(() -> reader.read(UUID.randomUUID(), PartySide.TENANT))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
  }

  @ParameterizedTest
  @EnumSource(PartyStatus.class)
  void 모든_자리_상태를_옮길_수_있다(PartyStatus status) {
    PartyPresence presence = JpaCounterpartReader.presence(status);

    assertThat(presence)
        .isEqualTo(
            status == PartyStatus.SHARING_STOPPED
                ? PartyPresence.CONNECTED
                : PartyPresence.valueOf(status.name()));
  }
}
