package com.chilsuitjo.back.link.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.inspection.dto.InspectionForLink;
import com.chilsuitjo.back.inspection.rule.InspectionKind;
import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import com.chilsuitjo.back.inspection.rule.LockReason;
import com.chilsuitjo.back.link.dto.LinkRecordResponse;
import com.chilsuitjo.back.photo.dto.ErasedPhoto;
import com.chilsuitjo.back.photo.dto.LinkPhoto;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.EraseReason;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 링크로 보는 점검의 응답 모양과 문구(#303 D 결정 7218475215). 보이는 범위는 inspection이 정한 값을 그대로 받는다고 본다. */
class InspectionLinkRecordTest {

  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private static final Instant CREATED = Instant.parse("2026-03-02T01:00:00Z");
  private static final Instant LOCKED = Instant.parse("2026-03-09T15:30:00Z"); // 서울 3/10 00:30

  private final UUID tile = UUID.randomUUID();
  private final UUID wall = UUID.randomUUID();

  @Test
  void 항목마다_부위_설명_상태와_그_항목의_사진만_묶는다() {
    UUID shot = UUID.randomUUID();
    UUID erased = UUID.randomUUID();
    Instant erasedAt = Instant.parse("2026-03-05T00:00:00Z");
    Map<PhotoTarget, List<LinkPhoto>> photos =
        Map.of(
            target(tile),
            List.of(
                new LinkPhoto(shot, target(tile), 0, "thumb", "orig", CREATED, null),
                new LinkPhoto(
                    erased,
                    target(tile),
                    1,
                    null,
                    null,
                    null,
                    new ErasedPhoto(PartySide.TENANT, erasedAt, EraseReason.PRIVACY))),
            target(wall),
            List.of());

    LinkRecordResponse record =
        InspectionLinkRecord.of(
            inspection(
                InspectionStatus.REQUESTED,
                List.of(
                    item(tile, "타일", "금이 갔어요", AckState.PENDING, PartySide.TENANT),
                    item(wall, "벽", "얼룩", AckState.ACK, PartySide.LANDLORD)),
                List.of()),
            photos,
            SEOUL,
            PartySide.LANDLORD);

    assertThat(record.targetKind()).isEqualTo(TargetKind.INSPECTION);
    assertThat(record.title()).isEqualTo("입주 점검");
    assertThat(record.partName()).isNull();
    assertThat(record.photos()).isEmpty();
    assertThat(record.items())
        .extracting(
            LinkRecordResponse.Item::partName,
            LinkRecordResponse.Item::description,
            LinkRecordResponse.Item::statusLine)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("타일", "금이 갔어요", "확인 기다림"),
            org.assertj.core.groups.Tuple.tuple("벽", "얼룩", "세입자 확인 ✓"));
    assertThat(record.items().getFirst().photos())
        .containsExactly(
            new LinkRecordResponse.Photo(shot, "thumb", "orig", CREATED, false, null),
            LinkRecordResponse.Photo.erased(erased, erasedAt));
    assertThat(record.items().get(1).photos()).isEmpty();
  }

  @Test
  void 항목_칸에_ID가_없다() {
    assertThat(
            Arrays.stream(LinkRecordResponse.Item.class.getRecordComponents())
                .map(RecordComponent::getName))
        .containsExactly("partName", "description", "statusLine", "photos");
  }

  @Test
  void 응답_상태는_부록_A_말로_적고_잠긴_뒤_추가는_날짜와_함께_앞에_둔다() {
    List<InspectionForLink.Item> items =
        List.of(
            item(tile, "타일", "a", AckState.NEED_MORE, PartySide.TENANT),
            item(wall, "벽", "b", AckState.DISPUTE, PartySide.TENANT),
            new InspectionForLink.Item(
                UUID.randomUUID(),
                AckState.UNCONFIRMED,
                PartySide.LANDLORD,
                true,
                false,
                Instant.parse("2026-11-30T03:00:00Z"),
                1,
                "방충망",
                "c",
                new PhotoTarget.Ref(PhotoTargetType.INSPECTION_ITEM, UUID.randomUUID())),
            new InspectionForLink.Item(
                UUID.randomUUID(),
                AckState.PENDING,
                PartySide.TENANT,
                true,
                true,
                Instant.parse("2027-01-05T03:00:00Z"),
                1,
                "현관문",
                "d",
                new PhotoTarget.Ref(PhotoTargetType.INSPECTION_ITEM, UUID.randomUUID())));

    LinkRecordResponse record =
        InspectionLinkRecord.of(
            inspection(InspectionStatus.REQUESTED, items, List.of()),
            noPhotos(items),
            SEOUL,
            PartySide.LANDLORD);

    assertThat(record.items())
        .extracting(LinkRecordResponse.Item::statusLine)
        .containsExactly(
            "보충 요청됨 · 더 필요한 게 있어요",
            "이견 · 사실과 달라요",
            "잠긴 뒤 추가 · 11/30 · 세입자 확인 없음",
            "거주 종료 뒤 추가 · 1/5 · 확인 기다림");
  }

  @Test
  void 답할_측이_보는_사람이면_보는_사람_입장으로_기다림과_확인_없음을_나눠_적는다() {
    List<InspectionForLink.Item> items =
        List.of(
            item(tile, "타일", "a", AckState.PENDING, PartySide.TENANT),
            item(wall, "벽", "b", AckState.UNCONFIRMED, PartySide.TENANT));
    InspectionForLink inspection = inspection(InspectionStatus.LOCKED, items, List.of());

    // 집주인이 링크로 본다: 답할 측(세입자가 더한 항목의 상대)이 보는 사람이다
    assertThat(
            InspectionLinkRecord.of(inspection, noPhotos(items), SEOUL, PartySide.LANDLORD).items())
        .extracting(LinkRecordResponse.Item::statusLine)
        .containsExactly("확인 기다림", "아직 답하지 않았어요 · 연결하면 지금도 답할 수 있어요");
    // 답할 측이 보는 사람이 아니면 측 말로 적고 늦은 답 안내를 붙이지 않는다
    assertThat(
            InspectionLinkRecord.of(inspection, noPhotos(items), SEOUL, PartySide.TENANT).items())
        .extracting(LinkRecordResponse.Item::statusLine)
        .containsExactly("집주인 확인 기다림", "집주인 확인 없음");
  }

  @Test
  void 점검_전체_상태는_statusLines에_잠김과_날짜_늦은_점검_기준_시각을_둔다() {
    InspectionForLink locked =
        new InspectionForLink(
            InspectionKind.MOVE_OUT,
            InspectionStatus.LOCKED,
            PartySide.TENANT,
            true,
            LOCKED,
            LockReason.NO_ITEMS,
            CREATED,
            Instant.parse("2026-04-01T00:00:00Z"),
            List.of(),
            List.of());

    LinkRecordResponse record =
        InspectionLinkRecord.of(locked, Map.of(), SEOUL, PartySide.LANDLORD);

    assertThat(record.title()).isEqualTo("퇴거 점검");
    assertThat(record.statusLines())
        .containsExactly("잠김 · 3/10", "하자 기록 없음", "입주 후 등록(3/2)", "입주 당시 상태와 다를 수 있어요", "4월 1일 기준");
    assertThat(
            InspectionLinkRecord.of(
                    inspection(InspectionStatus.REQUESTED, List.of(), List.of()),
                    Map.of(),
                    SEOUL,
                    PartySide.LANDLORD)
                .statusLines())
        .containsExactly("확인 요청");
  }

  @Test
  void 경과는_항목_행동에_부위를_붙이고_문구를_정하지_않은_행동은_건너뛴다() {
    List<InspectionForLink.Item> items =
        List.of(item(tile, "타일", "a", AckState.ACK, PartySide.TENANT));
    List<InspectionForLink.History> history =
        List.of(
            line(ActionType.START_INSPECTION, PartySide.TENANT, null),
            line(ActionType.ADD_ITEM, PartySide.TENANT, tile),
            line(ActionType.ADD_PART_PHOTO, PartySide.TENANT, null),
            line(ActionType.REQUEST_INSPECTION_CONFIRM, PartySide.TENANT, null),
            line(ActionType.RESPOND_ITEM, PartySide.LANDLORD, tile),
            line(ActionType.SUPPLEMENT_ITEM, PartySide.TENANT, tile),
            line(ActionType.CONFIRM_ALL, PartySide.LANDLORD, null),
            line(ActionType.FINISH_INSPECTION, PartySide.TENANT, null),
            line(ActionType.LOCK, null, null));

    LinkRecordResponse record =
        InspectionLinkRecord.of(
            inspection(InspectionStatus.LOCKED, items, history),
            noPhotos(items),
            SEOUL,
            PartySide.LANDLORD);

    assertThat(record.history())
        .extracting(LinkRecordResponse.HistoryItem::label, LinkRecordResponse.HistoryItem::side)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("세입자가 점검을 시작했어요", PartySide.TENANT),
            org.assertj.core.groups.Tuple.tuple("타일 · 세입자가 하자를 더했어요", PartySide.TENANT),
            org.assertj.core.groups.Tuple.tuple("세입자가 확인을 요청했어요", PartySide.TENANT),
            org.assertj.core.groups.Tuple.tuple("타일 · 집주인이 답했어요", PartySide.LANDLORD),
            org.assertj.core.groups.Tuple.tuple("타일 · 세입자가 보충했어요", PartySide.TENANT),
            org.assertj.core.groups.Tuple.tuple("집주인이 남은 항목을 모두 확인했어요", PartySide.LANDLORD),
            org.assertj.core.groups.Tuple.tuple("점검이 잠겼어요", null));
  }

  @Test
  void 항목_사진_결과가_빠지면_사진_없음으로_덮지_않고_예외다() {
    List<InspectionForLink.Item> items =
        List.of(item(tile, "타일", "a", AckState.PENDING, PartySide.TENANT));

    assertThatThrownBy(() ->
            InspectionLinkRecord.of(
                inspection(InspectionStatus.REQUESTED, items, List.of()),
                Map.of(),
                SEOUL,
                PartySide.LANDLORD))
        .isInstanceOf(IllegalStateException.class);
  }

  private InspectionForLink inspection(
      InspectionStatus status,
      List<InspectionForLink.Item> items,
      List<InspectionForLink.History> history) {
    boolean locked = status == InspectionStatus.LOCKED;
    return new InspectionForLink(
        InspectionKind.MOVE_IN,
        status,
        PartySide.TENANT,
        false,
        locked ? LOCKED : null,
        locked ? LockReason.ALL_RESOLVED : null,
        CREATED,
        null,
        items,
        history);
  }

  private static InspectionForLink.Item item(
      UUID id, String part, String description, AckState state, PartySide addedBy) {
    return new InspectionForLink.Item(
        id, state, addedBy, false, false, CREATED, 1, part, description, target(id));
  }

  private static PhotoTarget target(UUID item) {
    return new PhotoTarget.Ref(PhotoTargetType.INSPECTION_ITEM, item);
  }

  private static Map<PhotoTarget, List<LinkPhoto>> noPhotos(List<InspectionForLink.Item> items) {
    return items.stream()
        .collect(
            java.util.stream.Collectors.toMap(
                InspectionForLink.Item::photoTarget, item -> List.<LinkPhoto>of()));
  }

  private static InspectionForLink.History line(ActionType type, PartySide side, UUID item) {
    return new InspectionForLink.History(type, side, CREATED, item);
  }
}
