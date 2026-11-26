package com.chilsuitjo.back.repair.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.notification.dto.PartyFacts;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.rule.PartyPresence;
import com.chilsuitjo.back.notification.rule.RoomPhase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 수리 기록이 보는 사람에게 어디까지 보이나(03 3-1·6-6 9번·6-4, 불변식 18, #288). */
class RepairSightTest {

  private static final Instant MINE = Instant.parse("2026-11-26T01:00:00Z");
  private static final Instant THEIRS = MINE.plusSeconds(30);
  private static final Instant AFTER = THEIRS.plusSeconds(30);

  @Test
  void 상대가_연결돼_있으면_이른_공유_중단_시각_뒤의_상대와_서버_기록은_안_보인다() {
    RepairSight sight = sight(MINE, THEIRS, PartyPresence.CONNECTED);

    assertThat(sight).isEqualTo(new RepairSight(PartySide.TENANT, MINE, null));
    assertThat(sight.asOf()).isEqualTo(MINE);
    assertThat(sight.sees(PartySide.LANDLORD, AFTER)).isFalse();
    assertThat(sight.sees(null, AFTER)).isFalse();
    assertThat(sight.sees(PartySide.LANDLORD, MINE)).isTrue();
    assertThat(sight.sees(PartySide.TENANT, AFTER)).isTrue();
  }

  @Test
  void 상대가_탈퇴하면_제한은_풀려도_중단_뒤_그_측_기록은_계속_안_보인다() {
    RepairSight sight = sight(MINE, THEIRS, PartyPresence.WITHDRAWN);

    assertThat(sight).isEqualTo(new RepairSight(PartySide.TENANT, null, MINE));
    assertThat(sight.asOf()).isEqualTo(MINE);
    assertThat(sight.sees(PartySide.LANDLORD, AFTER)).isFalse();
    assertThat(sight.sees(PartySide.LANDLORD, MINE)).isTrue();
    // 남은 사람의 기록과 서버 기록은 풀린 기준을 따른다
    assertThat(sight.sees(PartySide.TENANT, AFTER)).isTrue();
    assertThat(sight.sees(null, AFTER)).isTrue();
  }

  @Test
  void 탈퇴한_상대만_그만뒀어도_그_시각이_기준이다() {
    assertThat(sight(null, THEIRS, PartyPresence.WITHDRAWN))
        .isEqualTo(new RepairSight(PartySide.TENANT, null, THEIRS));
  }

  @Test
  void 공유_중단이_없으면_모두_보인다() {
    RepairSight sight = sight(null, null, PartyPresence.WITHDRAWN);

    assertThat(sight.asOf()).isNull();
    assertThat(sight.sees(PartySide.LANDLORD, AFTER)).isTrue();
    assertThat(sight.sees(null, AFTER)).isTrue();
  }

  private static RepairSight sight(Instant mine, Instant theirs, PartyPresence counterpart) {
    RoomParties room =
        new RoomParties(
            RoomPhase.values()[0],
            new PartyFacts(
                UUID.randomUUID(),
                UUID.randomUUID(),
                PartyPresence.CONNECTED,
                mine,
                false,
                false,
                null),
            new PartyFacts(UUID.randomUUID(), null, counterpart, theirs, false, false, null));
    return RepairSight.of(room, PartySide.TENANT);
  }
}
