package com.chilsuitjo.back.photo.rule;

import static com.chilsuitjo.back.global.common.PartySide.LANDLORD;
import static com.chilsuitjo.back.global.common.PartySide.TENANT;
import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.global.common.PartySide;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 회원에게 사진이 보이나(03 3-1 "사진", 불변식 18). 칸 하나가 한 줄이다. */
class PhotoSightTest {

  private static final Instant STOPPED = Instant.parse("2026-11-20T00:00:00Z");

  @ParameterizedTest(name = "{0}이 {1}에 올린 사진, 중단 {2} → {3}")
  @CsvSource({
    "TENANT, 2026-11-21T00:00:00Z, true, true", // 내 사진은 그만둔 뒤에도 보인다
    "LANDLORD, 2026-11-21T00:00:00Z, false, true", // 공유 중단이 없으면 상대 사진도 보인다
    "LANDLORD, 2026-11-19T00:00:00Z, true, true", // 그만두기 전 상대 사진
    "LANDLORD, 2026-11-20T00:00:00Z, true, true", // 그만둔 시각과 같은 시각은 보인다(내려받기와 같은 경계)
    "LANDLORD, 2026-11-20T00:00:00.000001Z, true, false", // 그만둔 뒤 상대 사진
  })
  @Tag("invariant-18")
  void 공유를_그만둔_뒤_상대가_올린_사진만_안_보인다(
      PartySide uploadedBy, Instant createdAt, boolean stopped, boolean visible) {
    assertThat(PhotoSight.visibleTo(uploadedBy, createdAt, TENANT, stopped ? STOPPED : null))
        .isEqualTo(visible);
  }

  /**
   * #295: 두 번째 인자는 상대에게 보이기 시작한 시각({@code photo_visibility.released_at})이다. 작성 중 점검의 사진은 아직 비어
   * 있어서(null) 상대에게 안 보이고, 올린 사람에게는 늘 보인다(03 3-1 "작성 중 점검", INSP-1a).
   */
  @ParameterizedTest(name = "{0}이 올린 아직 안 보인 사진, 중단 {1} → {2}")
  @CsvSource({
    "TENANT, false, true", // 내 작성 중 사진은 내게 보인다
    "TENANT, true, true", // 공유를 그만둔 뒤에도 내 사진은 보인다
    "LANDLORD, false, false", // 상대의 작성 중 사진은 공유 중이어도 안 보인다
    "LANDLORD, true, false",
  })
  void 상대에게_아직_보이지_않은_사진은_올린_사람에게만_보인다(PartySide uploadedBy, boolean stopped, boolean visible) {
    assertThat(PhotoSight.visibleTo(uploadedBy, null, TENANT, stopped ? STOPPED : null))
        .isEqualTo(visible);
  }

  @ParameterizedTest
  @CsvSource({"TENANT", "LANDLORD"})
  void 보는_측이_바뀌어도_같은_규칙이다(PartySide viewer) {
    PartySide other = viewer == TENANT ? LANDLORD : TENANT;
    Instant after = STOPPED.plusSeconds(1);

    assertThat(PhotoSight.visibleTo(viewer, after, viewer, STOPPED)).isTrue();
    assertThat(PhotoSight.visibleTo(other, after, viewer, STOPPED)).isFalse();
  }
}
