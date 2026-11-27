package com.chilsuitjo.back.room.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.room.dto.RoomForLink;
import com.chilsuitjo.back.room.dto.RoomRegion;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
import java.lang.reflect.RecordComponent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

/** 링크용 호실 조회(#303, #337). 진짜 {@code room_record}·{@code party}·{@code region}을 읽는다. */
class JpaRoomLinkQueryTest extends RoomReadersTestSupport {

  @Autowired private RoomLinkQuery query;

  @Test
  void 진짜_구현이_쓰인다() {
    assertThat(query).isInstanceOf(JpaRoomLinkQuery.class);
  }

  @Test
  void 거주_중인_호실을_받는_자리에서_본다() {
    UUID room = roomIn(seoulRegion(), "LIVING");
    party(room, "TENANT", member(true), "CONNECTED", null);
    UUID landlordSeat = invitedParty(room, "LANDLORD", "01012345678");

    RoomForLink seen = query.forLink(room, landlordSeat).orElseThrow();

    assertThat(seen)
        .isEqualTo(
            new RoomForLink(
                PartySide.LANDLORD,
                new RoomRegion("9999999981", "테스트시", "테스트구", "테스트동"),
                PartyStatus.INVITED,
                RoomStatus.LIVING,
                false,
                false));
  }

  @Test
  void 세종처럼_시군구가_없는_주소는_null이다() {
    UUID room = room("LIVING");
    UUID seat = party(room, "TENANT", member(true), "CONNECTED", null);

    RoomRegion region = query.forLink(room, seat).orElseThrow().region();

    assertThat(region.sigungu()).isNull();
    assertThat(region.dong()).isEqualTo("테스트동");
  }

  @Test
  void 거주_종료한_호실도_돌려주고_상태만_알린다() {
    UUID room = room("ENDED");
    UUID seat = party(room, "LANDLORD", null, "INVITED", null);

    RoomForLink seen = query.forLink(room, seat).orElseThrow();

    // 거주 종료 뒤 일반 초대 링크를 막는 판단은 링크 종류를 아는 link가 한다
    assertThat(seen.roomStatus()).isEqualTo(RoomStatus.ENDED);
    assertThat(seen.settled()).isFalse();
  }

  @Test
  void 정산_완료한_호실은_settled다() {
    UUID room = room("SETTLED");
    UUID seat = party(room, "LANDLORD", null, "INVITED", null);

    RoomForLink seen = query.forLink(room, seat).orElseThrow();

    assertThat(seen.roomStatus()).isEqualTo(RoomStatus.SETTLED);
    assertThat(seen.settled()).isTrue();
  }

  @Test
  void 합류_전_기록이면_joinedAway다() {
    UUID joinedInto = room("LIVING");
    UUID room = room("LIVING");
    UUID seat = party(room, "TENANT", member(true), "CONNECTED", null);
    jdbc.update(
        "UPDATE state.room_record SET joined_into_id = ?, joined_at = ? WHERE id = ?",
        joinedInto,
        Timestamp.from(Instant.now()),
        room);

    assertThat(query.forLink(room, seat).orElseThrow().joinedAway()).isTrue();
  }

  @ParameterizedTest
  @EnumSource(
      value = PartyStatus.class,
      names = {"REJECTED", "WITHDRAWN", "SHARING_STOPPED"})
  void 링크로_열면_안_되는_자리_상태도_빈_값이_아니라_상태_그대로_준다(PartyStatus status) {
    // 막을지는 링크 종류를 아는 link가 recipientStatus로 정한다(#303). room이 빈 값으로 숨기지 않는다
    UUID room = room("LIVING");
    party(room, "TENANT", member(true), "CONNECTED", null);
    UUID seat =
        party(
            room,
            "LANDLORD",
            status == PartyStatus.SHARING_STOPPED ? member(true) : null,
            status.name(),
            status == PartyStatus.SHARING_STOPPED ? Instant.now() : null);

    assertThat(query.forLink(room, seat).orElseThrow().recipientStatus()).isEqualTo(status);
  }

  @Test
  void 다른_호실의_자리면_빈_값이다() {
    UUID room = room("LIVING");
    party(room, "TENANT", member(true), "CONNECTED", null);
    UUID other = room("LIVING");
    UUID otherSeat = party(other, "LANDLORD", null, "INVITED", null);

    assertThat(query.forLink(room, otherSeat)).isEmpty();
  }

  @Test
  void 없는_호실이나_없는_자리면_빈_값이다() {
    UUID room = room("LIVING");
    UUID seat = party(room, "TENANT", member(true), "CONNECTED", null);

    assertThat(query.forLink(UUID.randomUUID(), seat)).isEmpty();
    assertThat(query.forLink(room, UUID.randomUUID())).isEmpty();
  }

  @Test
  void 응답에_별칭_번호_회원_ID가_없다() {
    // 칸 이름으로 막아 둔다. 칸을 더할 때 링크로 새는 값이 없는지 이 테스트가 다시 묻는다(LINK-2a)
    assertThat(Arrays.stream(RoomForLink.class.getRecordComponents()).map(RecordComponent::getName))
        .containsExactly(
            "recipientSide", "region", "recipientStatus", "roomStatus", "settled", "joinedAway");
    assertThat(Arrays.stream(RoomRegion.class.getRecordComponents()).map(RecordComponent::getName))
        .containsExactly("code", "sido", "sigungu", "dong");
  }

  private String seoulRegion() {
    jdbc.update(
        "INSERT INTO state.region (code, sido, sigungu, dong)"
            + " VALUES ('9999999981', '테스트시', '테스트구', '테스트동') ON CONFLICT DO NOTHING");
    return "9999999981";
  }

  private UUID roomIn(String regionCode, String status) {
    UUID id = room(status);
    jdbc.update("UPDATE state.room_record SET region_code = ? WHERE id = ?", regionCode, id);
    return id;
  }

  private UUID invitedParty(UUID room, String side, String phone) {
    UUID id = party(room, side, null, "INVITED", null);
    jdbc.update("UPDATE state.party SET invite_phone = ? WHERE id = ?", phone, id);
    return id;
  }
}
