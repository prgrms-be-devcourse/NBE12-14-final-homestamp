package com.chilsuitjo.back.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.link.dto.LinkRoom;
import com.chilsuitjo.back.link.dto.LinkViewKind;
import com.chilsuitjo.back.link.dto.LinkViewRequest;
import com.chilsuitjo.back.link.rule.LinkToken;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 최근 열람이 있어 새 줄을 남기지 않는 열람도 잠근 호실의 지금 상태로 막히는지(#89 리뷰). 미리 거르는 {@code content.room}(가짜)은 열린 호실로 두고
 * 실제 호실 행만 바꿔, "미리 거른 뒤 잠그기 전에 정산 완료·합류가 된" 경우를 만든다. pipeline이 트랜잭션을 맡아 테스트에는
 * {@code @Transactional}을 두지 않는다(행은 임의 ID라 다른 테스트와 겹치지 않는다).
 */
@Import({TestcontainersConfiguration.class, FakeLinkContentSource.class})
@SpringBootTest
@ActiveProfiles("test")
class LinkViewRecentAccessTest {

  @Autowired private LinkViewService service;
  @Autowired private FakeLinkContentSource content;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void 최근_열람이_있고_호실이_그대로면_다시_열어도_성공하고_열람은_한_줄이다() {
    Fixture fx = fixture();

    service.view(new LinkViewRequest(fx.token()));
    var again = service.view(new LinkViewRequest(fx.token()));

    assertThat(again.kind()).isEqualTo(LinkViewKind.ROOM_INVITE);
    assertThat(viewCount(fx)).isEqualTo(1);
    assertThat(failedViews(fx)).isZero();
  }

  @Test
  void 최근_열람이_있어도_그_뒤_정산_완료된_호실은_다시_열지_못한다() {
    Fixture fx = fixture();
    service.view(new LinkViewRequest(fx.token()));
    jdbc.update(
        "UPDATE state.room_record SET status = 'SETTLED', ended_at = CURRENT_TIMESTAMP,"
            + " settled_at = CURRENT_TIMESTAMP WHERE id = ?",
        fx.room());

    assertLinkInvalid(fx);
    assertThat(viewCount(fx)).isEqualTo(1);
    assertThat(failedViews(fx)).isEqualTo(1);
  }

  @Test
  void 최근_열람이_있어도_그_뒤_합류_전_기록이_된_호실은_다시_열지_못한다() {
    Fixture fx = fixture();
    service.view(new LinkViewRequest(fx.token()));
    UUID joinedInto = room();
    jdbc.update(
        "UPDATE state.room_record SET joined_into_id = ?, joined_at = CURRENT_TIMESTAMP WHERE id = ?",
        joinedInto,
        fx.room());

    assertLinkInvalid(fx);
    assertThat(viewCount(fx)).isEqualTo(1);
    assertThat(failedViews(fx)).isEqualTo(1);
  }

  @Test
  void 최근_열람이_있어도_그_뒤_무효가_된_링크는_다시_열지_못한다() {
    Fixture fx = fixture();
    service.view(new LinkViewRequest(fx.token()));
    jdbc.update(
        "UPDATE state.link SET invalidated_at = CURRENT_TIMESTAMP, invalidated_reason = 'REINVITED'"
            + " WHERE id = ?",
        fx.link());

    assertLinkInvalid(fx);
    assertThat(viewCount(fx)).isEqualTo(1);
  }

  private void assertLinkInvalid(Fixture fx) {
    assertThatThrownBy(() -> service.view(new LinkViewRequest(fx.token())))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.LINK_INVALID));
  }

  private int viewCount(Fixture fx) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM record.action WHERE type = 'LINK_VIEW' AND link_id = ?",
        Integer.class,
        fx.link());
  }

  private int failedViews(Fixture fx) {
    return jdbc.queryForObject(
        "SELECT failed_view_count FROM state.link WHERE id = ?", Integer.class, fx.link());
  }

  /** 열린 호실 하나와 그 호실의 초대 링크. 가짜 content는 열린 호실로 둔다. */
  private Fixture fixture() {
    UUID room = room();
    UUID party = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    UUID link = UUID.randomUUID();
    String token = LinkToken.newToken();
    jdbc.update(
        "INSERT INTO state.member (id, status, created_at, version)"
            + " VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        member);
    jdbc.update(
        "INSERT INTO state.party (id, room_record_id, side, status, created_at, version)"
            + " VALUES (?, ?, 'LANDLORD', 'EMPTY', CURRENT_TIMESTAMP, 0)",
        party,
        room);
    jdbc.update(
        "INSERT INTO state.link (id, room_record_id, party_id, issued_by_member_id, kind,"
            + " token_hash, created_at, version)"
            + " VALUES (?, ?, ?, ?, 'INVITE', ?, CURRENT_TIMESTAMP, 0)",
        link,
        room,
        party,
        member,
        LinkToken.hash(token));
    content.seedRoom(
        room,
        party,
        new LinkRoom(
            PartySide.LANDLORD,
            "서울특별시",
            "강남구",
            "역삼동",
            false,
            false,
            PartyStatus.INVITED,
            RoomStatus.LIVING));
    return new Fixture(room, link, token);
  }

  private UUID room() {
    UUID id = UUID.randomUUID();
    String region = jdbc.queryForObject("SELECT code FROM state.region LIMIT 1", String.class);
    jdbc.update(
        "INSERT INTO state.room_record (id, region_code, alias, status, created_by_side,"
            + " created_at, version) VALUES (?, ?, '테스트', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)",
        id,
        region);
    return id;
  }

  private record Fixture(UUID room, UUID link, String token) {}
}
