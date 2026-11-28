package com.chilsuitjo.back.link.rule;

import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.link.dto.LinkRecordResponse;
import com.chilsuitjo.back.photo.dto.LinkPhoto;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.repair.dto.RepairLinkView;
import com.chilsuitjo.back.repair.rule.ClosedBy;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 링크로 보는 수리 건 하나를 링크 응답으로 옮긴다(06 LINK-1a, 4-4 {@code links/view}, #303). 보이는 범위(공유 중단 기준 시각, 불변식
 * 18, 숨긴 수리 건)는 repair가 이미 정했다({@code RepairLinkQuery.forLink}). 숨긴 사진은 photo가 뺀다. 여기서는 다시 고르지 않고
 * 모양과 문구만 만든다.
 *
 * <ul>
 *   <li>{@code items}는 빈 목록이다(항목은 점검만, LINK-1j)
 *   <li>상태는 08 1장 수리 건 카드의 하자 줄·수리 줄과 공유 중단 기준 시각이다. 돈 줄은 없다(돈은 {@code hasMoney}로 있다는 것만, LINK-1d)
 *   <li>사진은 수리 건·고친 내용·원인 설명·진행 상황 사진을 그 순서로 한 목록에 둔다. 응답에 원인 설명·진행 상황 칸이 없어 글은 내보내지 않는다(경과에
 *       "남겼어요"로만 보임)
 *   <li>경과는 문구를 정한 행동만, 시각 순 그대로. 돈 행동과 모르는 행동은 건너뛴다(점검 링크와 같음)
 * </ul>
 *
 * <p>문구는 06 부록 A "사용자 말 표"와 08 1장 줄 값을 쓴다. 링크 화면에는 이름이 없어(03 6-1) 측 말(세입자 / 집주인)을 쓴다. 업체·전문가 이름,
 * 금액, 호실·부위·기록 ID는 {@link RepairLinkView}에 없거나 여기서 내보내지 않는다(LINK-2a).
 */
public final class RepairLinkRecord {

  private static final DateTimeFormatter AS_OF = DateTimeFormatter.ofPattern("M월 d일 기준");

  private RepairLinkRecord() {}

  /** 사진을 읽을 대상. 보이는 수리 건·고친 내용·원인 설명·진행 상황의 사진 대상(겹치면 한 번). */
  public static List<PhotoTarget> photoTargets(RepairLinkView repair) {
    Set<PhotoTarget> targets = new LinkedHashSet<>();
    targets.add(repair.photoTarget());
    repair.corrections().stream()
        .map(RepairLinkView.Correction::photoTarget)
        .filter(Objects::nonNull)
        .forEach(targets::add);
    repair.causeNotes().stream()
        .map(RepairLinkView.CauseNote::photoTarget)
        .filter(Objects::nonNull)
        .forEach(targets::add);
    repair.progressNotes().stream()
        .map(RepairLinkView.ProgressNote::photoTarget)
        .filter(Objects::nonNull)
        .forEach(targets::add);
    return List.copyOf(targets);
  }

  /**
   * @param photos {@code PhotoService.linkPhotos}의 결과. {@link #photoTargets}의 대상마다 키가 있어야 한다(없으면 계약
   *     위반이라 {@code IllegalStateException}. 사진이 없는 것으로 덮지 않는다)
   * @param zone 날짜를 적는 시간대({@code app.zone})
   * @param viewerSide 링크를 받은 측. 하자에 답할 측이 보는 사람이면 보는 사람 입장 문구를 쓴다
   */
  public static LinkRecordResponse of(
      RepairLinkView repair,
      Map<PhotoTarget, List<LinkPhoto>> photos,
      ZoneId zone,
      PartySide viewerSide) {
    Objects.requireNonNull(repair, "repair");
    Objects.requireNonNull(photos, "photos");
    Objects.requireNonNull(zone, "zone");
    Objects.requireNonNull(viewerSide, "viewerSide");
    List<LinkRecordResponse.Photo> shown = new ArrayList<>();
    for (PhotoTarget target : photoTargets(repair)) {
      List<LinkPhoto> list = photos.get(target);
      if (list == null) {
        throw new IllegalStateException("수리 건 사진 대상의 결과가 없어요(linkPhotos 계약)");
      }
      list.stream().map(RepairLinkRecord::photo).forEach(shown::add);
    }
    List<LinkRecordResponse.HistoryItem> history =
        repair.history().stream().map(RepairLinkRecord::history).flatMap(Optional::stream).toList();
    return new LinkRecordResponse(
        TargetKind.REPAIR_CASE,
        repair.partName(),
        repair.title(),
        repair.description(),
        statusLines(repair, zone, viewerSide),
        shown,
        history);
  }

  private static LinkRecordResponse.Photo photo(LinkPhoto photo) {
    if (photo.erased() != null) {
      return LinkRecordResponse.Photo.erased(photo.id(), photo.erased());
    }
    return LinkRecordResponse.Photo.shown(
        photo.id(), photo.thumbUrl(), photo.originalUrl(), photo.expiresAt());
  }

  private static List<String> statusLines(RepairLinkView repair, ZoneId zone, PartySide viewer) {
    List<String> lines = new ArrayList<>();
    lines.add("하자 · " + defectLine(repair, viewer));
    lines.add("수리 · " + repairLine(repair));
    if (repair.asOf() != null) {
      lines.add(AS_OF.format(repair.asOf().atZone(zone)));
    }
    return lines;
  }

  /**
   * 08 1장 하자 줄. 하자에 답하는 측은 등록하지 않은 측이다. 답할 측이 링크를 보는 사람이면(연결 전 상대에게 공유한 링크는 늘 그렇다) 점검 링크와 같은 보는 사람
   * 입장 문구를 쓴다(06 LINK-1j와 같은 말, 03 1-1 "확인 없음" 화면 표시 ④). 남은 시간·사유·확인 날짜는 {@link RepairLinkView}에 없어
   * 적지 않는다.
   */
  private static String defectLine(RepairLinkView repair, PartySide viewer) {
    PartySide responder = other(repair.registeredBy());
    boolean viewerAnswers = responder == viewer;
    String who = sideWord(responder);
    AckState ack = repair.defectAck();
    return switch (ack) {
      case PENDING -> viewerAnswers ? "확인 기다림" : who + " 확인 기다림";
      case ACK -> who + " 확인 ✓";
      case NEED_MORE -> "보충 요청됨 · 더 필요한 게 있어요";
      case DISPUTE -> "이견 · 사실과 달라요";
      case UNCONFIRMED -> viewerAnswers ? "아직 답하지 않았어요 · 연결하면 지금도 답할 수 있어요" : who + " 확인 없음";
    };
  }

  /** 08 1장 수리 줄. 날짜 수(N일째)는 링크 응답에 시계를 두지 않아 적지 않는다(점검 링크와 같음). */
  private static String repairLine(RepairLinkView repair) {
    return switch (repair.status()) {
      case NOT_STARTED -> "시작 전";
      case IN_PROGRESS -> "처리 중";
      case CLOSE_WAIT -> "종료 확인 기다림";
      case DONE -> done(repair.closedBy());
    };
  }

  private static String done(ClosedBy closedBy) {
    if (closedBy == null) {
      return "처리 완료";
    }
    return switch (closedBy) {
      case BOTH -> "처리 완료(둘 다 확인)";
      case TENANT -> "처리 완료(세입자 확인)";
      case LANDLORD -> "처리 완료(집주인 확인)";
    };
  }

  /** 문구를 정한 행동만. 그 밖(돈·서버 행동·모르는 종류)은 건너뛴다(#303 "모르는 행동 종류는 건너뜁니다"). */
  private static Optional<LinkRecordResponse.HistoryItem> history(RepairLinkView.History line) {
    if (line.side() == null) {
      return Optional.empty();
    }
    String who = subject(line.side());
    String text =
        switch (line.type()) {
          case REGISTER -> who + " 하자를 등록했어요";
          case SUPPLEMENT -> who + " 보충하고 다시 확인을 요청했어요";
          case RESPOND_DEFECT -> who + " 하자에 답했어요";
          case START_REPAIR -> who + " 수리를 시작했어요";
          case REQUEST_CLOSE -> who + " 끝났다고 확인을 요청했어요";
          case CONFIRM_CLOSE -> "둘 다 끝났다고 확인했어요";
          case NOT_YET -> who + " 아직 안 됐다고 답했어요";
          case CLOSE_ALONE -> who + " 끝났다고 남겼어요";
          case REOPEN -> who + " 다시 열었어요";
          case ADD_PHOTO -> who + " 사진을 더했어요";
          case ASK_PROGRESS -> who + " 진행 상황을 물었어요";
          case NOTE_PROGRESS -> who + " 진행 상황을 남겼어요";
          case RESPOND_SITUATION -> who + " 상황 변화에 답했어요";
          case NOTE_CAUSE -> who + " 원인 설명을 남겼어요";
          case RESPOND_CAUSE -> who + " 원인 설명에 답했어요";
          case CORRECT -> who + " 고쳤어요";
          case RESPOND_CORRECTION -> who + " 고친 내용에 답했어요";
          case ACCEPT_ALL_CORRECTIONS -> who + " 고친 내용을 모두 확인했어요";
          case RESUBMIT_CORRECTION -> who + " 고친 내용을 다시 요청했어요";
          case WITHDRAW_CORRECTION -> who + " 고친 내용을 거뒀어요";
          default -> null;
        };
    if (text == null) {
      return Optional.empty();
    }
    return Optional.of(new LinkRecordResponse.HistoryItem(text, line.side(), line.at()));
  }

  private static PartySide other(PartySide side) {
    return side == PartySide.TENANT ? PartySide.LANDLORD : PartySide.TENANT;
  }

  /** 06 부록 A "측": 세입자 쪽 / 집주인 쪽, 또는 그냥 "집주인". */
  private static String sideWord(PartySide side) {
    return side == PartySide.TENANT ? "세입자" : "집주인";
  }

  private static String subject(PartySide side) {
    return side == PartySide.TENANT ? "세입자가" : "집주인이";
  }
}
