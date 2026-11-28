package com.chilsuitjo.back.link.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.correction.rule.CorrectionStatus;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.link.dto.LinkRecordResponse;
import com.chilsuitjo.back.photo.dto.ErasedPhoto;
import com.chilsuitjo.back.photo.dto.LinkPhoto;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.EraseReason;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import com.chilsuitjo.back.repair.dto.RepairLinkView;
import com.chilsuitjo.back.repair.rule.CauseBasis;
import com.chilsuitjo.back.repair.rule.ClosedBy;
import com.chilsuitjo.back.repair.rule.RepairStatus;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 수리 건 링크 응답의 모양과 문구(06 LINK-1a, 08 1장 수리 건 카드, 부록 A). 보이는 범위는 repair 테스트가 본다. */
class RepairLinkRecordTest {

  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private static final Instant AT = Instant.parse("2026-11-20T01:00:00Z");
  private final UUID caseId = UUID.randomUUID();
  private final PhotoTarget caseTarget = new PhotoTarget.Ref(PhotoTargetType.REPAIR_CASE, caseId);

  @Test
  void 수리_건은_부위_제목_설명과_하자_수리_줄이고_항목은_빈_목록이다() {
    LinkRecordResponse response =
        RepairLinkRecord.of(
            view(AckState.PENDING, RepairStatus.NOT_STARTED, null, null),
            Map.of(caseTarget, List.of()),
            SEOUL,
            PartySide.LANDLORD);

    assertThat(response.targetKind()).isEqualTo(TargetKind.REPAIR_CASE);
    assertThat(response.partName()).isEqualTo("세면대");
    assertThat(response.title()).isEqualTo("수전에서 물이 새요");
    assertThat(response.description()).isEqualTo("어제부터");
    // 하자에 답할 측(집주인)이 링크를 보는 사람이라 보는 사람 입장
    assertThat(response.statusLines()).containsExactly("하자 · 확인 기다림", "수리 · 시작 전");
    assertThat(response.items()).isEmpty();
    assertThat(response.photos()).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({
    "PENDING,LANDLORD,하자 · 확인 기다림",
    "PENDING,TENANT,하자 · 집주인 확인 기다림",
    "ACK,LANDLORD,하자 · 집주인 확인 ✓",
    "NEED_MORE,LANDLORD,하자 · 보충 요청됨 · 더 필요한 게 있어요",
    "DISPUTE,LANDLORD,하자 · 이견 · 사실과 달라요",
    "UNCONFIRMED,LANDLORD,하자 · 아직 답하지 않았어요 · 연결하면 지금도 답할 수 있어요",
    "UNCONFIRMED,TENANT,하자 · 집주인 확인 없음"
  })
  void 하자_줄은_답할_측이_보는_사람이면_보는_사람_입장이다(AckState ack, PartySide viewer, String line) {
    assertThat(
            RepairLinkRecord.of(
                    view(ack, RepairStatus.IN_PROGRESS, null, null),
                    Map.of(caseTarget, List.of()),
                    SEOUL,
                    viewer)
                .statusLines()
                .getFirst())
        .isEqualTo(line);
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "IN_PROGRESS,,수리 · 처리 중",
        "CLOSE_WAIT,,수리 · 종료 확인 기다림",
        "DONE,BOTH,수리 · 처리 완료(둘 다 확인)",
        "DONE,TENANT,수리 · 처리 완료(세입자 확인)",
        "DONE,LANDLORD,수리 · 처리 완료(집주인 확인)",
        "DONE,,수리 · 처리 완료"
      },
      nullValues = "")
  void 수리_줄은_08_줄_값이다(RepairStatus status, ClosedBy closedBy, String line) {
    assertThat(
            RepairLinkRecord.of(
                    view(AckState.ACK, status, closedBy, null),
                    Map.of(caseTarget, List.of()),
                    SEOUL,
                    PartySide.LANDLORD)
                .statusLines()
                .get(1))
        .isEqualTo(line);
  }

  @Test
  void 공유를_그만둔_뒤면_기준_날짜_줄을_더한다() {
    assertThat(
            RepairLinkRecord.of(
                    view(
                        AckState.PENDING,
                        RepairStatus.NOT_STARTED,
                        null,
                        Instant.parse("2026-11-25T16:00:00Z")),
                    Map.of(caseTarget, List.of()),
                    SEOUL,
                    PartySide.LANDLORD)
                .statusLines())
        .containsExactly("하자 · 확인 기다림", "수리 · 시작 전", "11월 26일 기준");
  }

  @Test
  void 사진은_수리_건_고친_내용_원인_설명_진행_상황_순서이고_지운_사진은_흔적만이다() {
    UUID correctionId = UUID.randomUUID();
    UUID causeId = UUID.randomUUID();
    UUID progressId = UUID.randomUUID();
    PhotoTarget correction = new PhotoTarget.Ref(PhotoTargetType.CORRECTION_REQUEST, correctionId);
    PhotoTarget cause = new PhotoTarget.Ref(PhotoTargetType.CAUSE_NOTE, causeId);
    PhotoTarget progress = new PhotoTarget.Ref(PhotoTargetType.PROGRESS_NOTE, progressId);
    RepairLinkView repair =
        new RepairLinkView(
            PartySide.TENANT,
            RepairStatus.IN_PROGRESS,
            null,
            null,
            AckState.ACK,
            AT,
            null,
            "세면대",
            "수전에서 물이 새요",
            null,
            caseTarget,
            List.of(
                new RepairLinkView.Correction(
                    correctionId,
                    "title",
                    "수전 누수",
                    PartySide.TENANT,
                    "말을 고쳐요",
                    CorrectionStatus.WAITING,
                    AckState.PENDING,
                    AT,
                    correction)),
            List.of(
                new RepairLinkView.CauseNote(
                    causeId,
                    PartySide.LANDLORD,
                    "낡은 수전",
                    CauseBasis.EXPERT,
                    AT,
                    AckState.PENDING,
                    cause)),
            List.of(
                new RepairLinkView.ProgressNote(
                    progressId, PartySide.LANDLORD, "부품 주문", false, AT, null, progress)),
            List.of());
    UUID shown = UUID.randomUUID();
    UUID mine = UUID.randomUUID();
    UUID third = UUID.randomUUID();
    UUID last = UUID.randomUUID();
    Map<PhotoTarget, List<LinkPhoto>> photos = new HashMap<>();
    photos.put(caseTarget, List.of(shownPhoto(shown, caseTarget)));
    photos.put(
        correction,
        List.of(
            new LinkPhoto(
                mine,
                correction,
                0,
                null,
                null,
                null,
                new ErasedPhoto(PartySide.TENANT, AT, EraseReason.PRIVACY))));
    photos.put(
        cause,
        List.of(
            new LinkPhoto(
                third,
                cause,
                0,
                null,
                null,
                null,
                new ErasedPhoto(PartySide.LANDLORD, AT, EraseReason.THIRD_PARTY))));
    photos.put(progress, List.of(shownPhoto(last, progress)));

    LinkRecordResponse response = RepairLinkRecord.of(repair, photos, SEOUL, PartySide.LANDLORD);

    assertThat(response.photos())
        .extracting(LinkRecordResponse.Photo::id)
        .containsExactly(shown, mine, third, last);
    assertThat(response.photos().get(1).erasedSide()).isEqualTo(PartySide.TENANT);
    assertThat(response.photos().get(1).thumbUrl()).isNull();
    // 다른 사람의 요청으로 지운 사진은 측 없이(불변식 16)
    assertThat(response.photos().get(2).erasedSide()).isNull();
    assertThat(response.photos().get(2).erasedReason()).isEqualTo(EraseReason.THIRD_PARTY);
    // 원인 설명·진행 상황의 글은 응답 칸이 없어 내보내지 않는다
    assertThat(response.toString()).doesNotContain("낡은 수전").doesNotContain("부품 주문");
  }

  @Test
  void 사진_대상의_결과가_없으면_빈_사진으로_덮지_않고_예외다() {
    assertThatThrownBy(() ->
            RepairLinkRecord.of(
                view(AckState.PENDING, RepairStatus.NOT_STARTED, null, null),
                Map.of(),
                SEOUL,
                PartySide.LANDLORD))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 경과는_문구를_정한_행동만_측_말로_시각_순이고_돈_행동과_서버_행동은_건너뛴다() {
    RepairLinkView repair =
        new RepairLinkView(
            PartySide.TENANT,
            RepairStatus.DONE,
            null,
            ClosedBy.BOTH,
            AckState.ACK,
            AT,
            null,
            "세면대",
            "수전에서 물이 새요",
            null,
            caseTarget,
            List.of(),
            List.of(),
            List.of(),
            List.of(
                history(ActionType.REGISTER, PartySide.TENANT, 0),
                history(ActionType.RESPOND_DEFECT, PartySide.LANDLORD, 1),
                history(ActionType.RECORD_PAYMENT, PartySide.LANDLORD, 2),
                history(ActionType.START_REPAIR, PartySide.LANDLORD, 3),
                history(ActionType.MARK_UNCONFIRMED, null, 4),
                history(ActionType.REQUEST_CLOSE, PartySide.LANDLORD, 5),
                history(ActionType.CONFIRM_CLOSE, PartySide.TENANT, 6),
                history(ActionType.CORRECT, PartySide.TENANT, 7)));

    LinkRecordResponse response =
        RepairLinkRecord.of(repair, Map.of(caseTarget, List.of()), SEOUL, PartySide.LANDLORD);

    assertThat(response.history())
        .extracting(LinkRecordResponse.HistoryItem::label)
        .containsExactly(
            "세입자가 하자를 등록했어요",
            "집주인이 하자에 답했어요",
            "집주인이 수리를 시작했어요",
            "집주인이 끝났다고 확인을 요청했어요",
            "둘 다 끝났다고 확인했어요",
            "세입자가 고쳤어요");
    assertThat(response.history().getFirst().side()).isEqualTo(PartySide.TENANT);
    assertThat(response.history().getFirst().at()).isEqualTo(AT);
  }

  private RepairLinkView view(AckState ack, RepairStatus status, ClosedBy closedBy, Instant asOf) {
    return new RepairLinkView(
        PartySide.TENANT,
        status,
        status == RepairStatus.CLOSE_WAIT ? PartySide.LANDLORD : null,
        closedBy,
        ack,
        AT,
        asOf,
        "세면대",
        "수전에서 물이 새요",
        "어제부터",
        caseTarget,
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static LinkPhoto shownPhoto(UUID id, PhotoTarget target) {
    return new LinkPhoto(
        id, target, 0, "https://s3/thumb", "https://s3/original", AT.plusSeconds(300), null);
  }

  private RepairLinkView.History history(ActionType type, PartySide side, int seconds) {
    return new RepairLinkView.History(
        type, side, AT.plusSeconds(seconds), TargetKind.REPAIR_CASE, null);
  }
}
