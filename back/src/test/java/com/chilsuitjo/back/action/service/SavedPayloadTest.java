package com.chilsuitjo.back.action.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.PhotoDigest;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.common.PartySide;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** {@code save}가 돌려준 payload(붙인 사진 목록 등)가 행동 기록에 남는다(#141, 05 2-3 "사진 지문은 행동 payload에"). */
class SavedPayloadTest {

  private static final String SHA_A = "a".repeat(64);
  private static final String SHA_B = "b".repeat(64);
  private static final UUID PHOTO_1 = UUID.fromString("0193a000-0000-7000-8000-000000000001");
  private static final UUID PHOTO_2 = UUID.fromString("0193a000-0000-7000-8000-000000000002");

  private final ActionPipelineFixture fx = new ActionPipelineFixture();
  private final UUID room = UUID.randomUUID();
  private final UUID tenant = UUID.randomUUID();

  @Test
  void 붙인_사진은_정렬된_목록으로_요청_payload와_함께_남는다() {
    fx.gates().member(room, tenant, PartySide.TENANT);
    // 일부러 순서를 섞어 준다: (sortOrder, photoId) 순으로 정렬돼야 한다
    List<PhotoDigest> attached =
        List.of(
            new PhotoDigest(PHOTO_2, SHA_B, 1),
            new PhotoDigest(PHOTO_1, SHA_A, 1),
            new PhotoDigest(PHOTO_2, SHA_A, 0));

    fx.pipeline()
        .create(
            command(Map.of("note", "x")),
            store(() ->
                SavedState.created(ActionTarget.repairCase(UUID.randomUUID()))
                    .withPhotos(attached)),
            rules());

    ActionEntry recorded = fx.actions().findByRoom(room).getFirst();
    assertThat(recorded.payload())
        .containsEntry("note", "x")
        .containsEntry(
            SavedState.PHOTOS,
            List.of(
                Map.of("photoId", PHOTO_2.toString(), "originalSha256", SHA_A, "sortOrder", 0),
                Map.of("photoId", PHOTO_1.toString(), "originalSha256", SHA_A, "sortOrder", 1),
                Map.of("photoId", PHOTO_2.toString(), "originalSha256", SHA_B, "sortOrder", 1)));
  }

  @Test
  void 사진이_없으면_payload에_칸을_넣지_않는다() {
    fx.gates().member(room, tenant, PartySide.TENANT);

    fx.pipeline()
        .create(
            command(Map.of()),
            store(() ->
                SavedState.created(ActionTarget.repairCase(UUID.randomUUID()))
                    .withPhotos(List.of())),
            rules());

    assertThat(fx.actions().findByRoom(room).getFirst().payload()).isEmpty();
  }

  @Test
  void save가_요청_payload와_같은_키를_돌려주면_거절하고_남기지_않는다() {
    fx.gates().member(room, tenant, PartySide.TENANT);

    assertThatThrownBy(() ->
            fx.pipeline()
                .create(
                    command(Map.of(SavedState.PHOTOS, "from request")),
                    store(() ->
                        SavedState.created(ActionTarget.repairCase(UUID.randomUUID()))
                            .withPhotos(List.of(new PhotoDigest(PHOTO_1, SHA_A, 0)))),
                    rules()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(fx.actions().findByRoom(room)).isEmpty();
  }

  @Test
  void 사진_지문은_64자_소문자_hex만_받는다() {
    assertThatThrownBy(() -> new PhotoDigest(PHOTO_1, "A".repeat(64), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PhotoDigest(PHOTO_1, "a".repeat(63), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PhotoDigest(PHOTO_1, SHA_A, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PhotoDigest(null, SHA_A, 0))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void 같은_키를_두_번_더하면_거절한다() {
    SavedState once = SavedState.unchanged().withPayload("k", 1);

    assertThatThrownBy(() -> once.withPayload("k", 2)).isInstanceOf(IllegalArgumentException.class);
  }

  private ActionCommand command(Map<String, Object> payload) {
    return new ActionCommand(
        room, Actor.member(tenant), ActionType.REGISTER, "k1", null, null, null, null, payload);
  }

  private static StateStore<Boolean> store(Supplier<SavedState> saved) {
    return new StateStore<>() {
      @Override
      public Boolean load(ActionCommand command) {
        return false;
      }

      @Override
      public SavedState save(ActionCommand command, Boolean next) {
        return saved.get();
      }
    };
  }

  private static Transitions<Boolean> rules() {
    return new Transitions<>() {
      @Override
      public Boolean initial() {
        return false;
      }

      @Override
      public Boolean apply(Boolean now, ActionEntry action) {
        return true;
      }
    };
  }
}
