package com.chilsuitjo.back.link.rule;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.inspection.dto.InspectionForLink;
import com.chilsuitjo.back.inspection.rule.InspectionKind;
import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import com.chilsuitjo.back.inspection.rule.LockReason;
import com.chilsuitjo.back.link.dto.LinkRecordResponse;
import com.chilsuitjo.back.photo.dto.LinkPhoto;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 링크로 보는 점검 하나를 링크 응답으로 옮긴다(#303 D 결정 7218475215, 06 LINK-1a). 보이는 범위는 inspection이 이미 정했다({@code
 * InspectionLinkQuery.forLink}). 여기서는 다시 고르지 않고 모양과 문구만 만든다.
 *
 * <ul>
 *   <li>항목마다 부위·설명·응답 상태·사진을 묶는다({@link LinkRecordResponse.Item}). 항목 ID는 내보내지 않는다
 *   <li>점검 전체 상태(확인 요청 / 잠김 · 날짜, 늦은 점검, 공유 중단 기준 시각)는 {@code statusLines}에 둔다
 *   <li>경과는 한 목록이다. 항목 행동이면 문구 앞에 그 항목의 부위 이름을 붙인다. 문구를 정하지 않은 행동 종류는 건너뛴다(경과 문구만의 일이다. 보이는 범위와 상태는
 *       inspection이 행동 전체로 이미 정했다)
 * </ul>
 *
 * <p>문구는 06 부록 A "사용자 말 표"와 08 9장 점검 화면의 말을 쓴다. 링크 화면에는 이름이 없어(03 6-1) "○○ 님" 대신 측 말(세입자 / 집주인)을
 * 쓴다.
 */
public final class InspectionLinkRecord {

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M/d");
  private static final DateTimeFormatter AS_OF = DateTimeFormatter.ofPattern("M월 d일 기준");

  private InspectionLinkRecord() {}

  /** 사진을 읽을 대상. 보이는 항목의 사진 대상뿐이다. */
  public static List<PhotoTarget> photoTargets(InspectionForLink inspection) {
    return inspection.items().stream().map(InspectionForLink.Item::photoTarget).toList();
  }

  /**
   * @param photos {@code PhotoService.linkPhotos}의 결과. 넘긴 대상마다 키가 있어야 한다(없으면 계약 위반이라 {@code
   *     IllegalStateException}. 사진이 없는 것으로 덮지 않는다)
   * @param zone 날짜를 적는 시간대({@code app.zone})
   * @param viewerSide 링크를 받은 측. 답할 측이 보는 사람이면 보는 사람 입장 문구를 쓴다
   */
  public static LinkRecordResponse of(
      InspectionForLink inspection,
      Map<PhotoTarget, List<LinkPhoto>> photos,
      ZoneId zone,
      PartySide viewerSide) {
    Objects.requireNonNull(inspection, "inspection");
    Objects.requireNonNull(photos, "photos");
    Objects.requireNonNull(zone, "zone");
    Objects.requireNonNull(viewerSide, "viewerSide");
    List<LinkRecordResponse.Item> items =
        inspection.items().stream().map(item -> item(item, photos, zone, viewerSide)).toList();
    Map<UUID, String> partNames =
        inspection.items().stream()
            .collect(
                Collectors.toMap(InspectionForLink.Item::id, InspectionForLink.Item::partName));
    List<LinkRecordResponse.HistoryItem> history =
        inspection.history().stream()
            .map(line -> history(line, partNames))
            .flatMap(Optional::stream)
            .toList();
    return new LinkRecordResponse(
        TargetKind.INSPECTION,
        null,
        title(inspection.kind()),
        null,
        statusLines(inspection, zone),
        List.of(),
        history,
        items);
  }

  private static LinkRecordResponse.Item item(
      InspectionForLink.Item item,
      Map<PhotoTarget, List<LinkPhoto>> photos,
      ZoneId zone,
      PartySide viewerSide) {
    List<LinkPhoto> shown = photos.get(item.photoTarget());
    if (shown == null) {
      throw new IllegalStateException("항목 사진 대상의 결과가 없어요(linkPhotos 계약)");
    }
    return new LinkRecordResponse.Item(
        item.partName(),
        item.description(),
        statusLine(item, zone, viewerSide),
        shown.stream().map(InspectionLinkRecord::photo).toList());
  }

  private static LinkRecordResponse.Photo photo(LinkPhoto photo) {
    if (photo.erased() != null) {
      return LinkRecordResponse.Photo.erased(photo.id(), photo.erased().at());
    }
    return new LinkRecordResponse.Photo(
        photo.id(), photo.thumbUrl(), photo.originalUrl(), photo.expiresAt(), false, null);
  }

  /**
   * 08 9장 "잠긴 뒤 추가 · 11/30 · 서윤 님 확인 기다림"의 모양. 답하는 측은 항목을 더하지 않은 측이다(06 4-7 respond).
   *
   * <p><b>답할 측이 링크를 보는 사람이면</b>(링크로 보이는 항목은 늘 그렇다. 연결 전이라 항목을 더할 수 없다) 보는 사람 입장으로 쓴다.
   *
   * <ul>
   *   <li>답을 기다림: "확인 기다림"(08 1장 줄 상태 표 "하자")
   *   <li>확인 없음(서버가 정한 {@code UNCONFIRMED}): "아직 답하지 않았어요 · 연결하면 지금도 답할 수 있어요"(03 1-1 "확인 없음" 화면 표시
   *       ④를 연결 전 열람자에게 맞춤: 링크에서는 답할 수 없고 연결해야 답한다). 확인 없음 뒤 늦은 답은 정산 완료 전까지 되고(03 3-1), 초대 링크는 거주
   *       중에만 열리므로 이 문구가 보일 때는 늘 답할 수 있다
   * </ul>
   *
   * 문자가 가지 않음·상대 없음·혼자 진행은 항목 상태가 아니라서 이 문구가 되지 않는다(항목 상태는 {@code AckState}뿐). 답할 측이 보는 사람이 아니면(지금
   * 경로로는 생기지 않음) 측 말로 적는다.
   *
   * <p>보류·이견의 사유와 확인 날짜는 {@code InspectionForLink.Item}에 없어 적지 않는다. 행동 시각으로 짐작하지 않는다. 사유 자리에는 부록 A의
   * 뜻 말("더 필요한 게 있어요"·"사실과 달라요")을 쓴다.
   */
  private static String statusLine(InspectionForLink.Item item, ZoneId zone, PartySide viewerSide) {
    List<String> parts = new ArrayList<>();
    if (item.addedAfterEnd()) {
      parts.add("거주 종료 뒤 추가 · " + day(item.createdAt(), zone));
    } else if (item.addedAfterLock()) {
      parts.add("잠긴 뒤 추가 · " + day(item.createdAt(), zone));
    }
    PartySide responderSide = other(item.addedBySide());
    boolean viewerAnswers = responderSide == viewerSide;
    String responder = sideWord(responderSide);
    parts.add(
        switch (item.status()) {
          case PENDING -> viewerAnswers ? "확인 기다림" : responder + " 확인 기다림";
          case ACK -> responder + " 확인 ✓";
          case NEED_MORE -> "보충 요청됨 · 더 필요한 게 있어요";
          case DISPUTE -> "이견 · 사실과 달라요";
          case UNCONFIRMED ->
              viewerAnswers ? "아직 답하지 않았어요 · 연결하면 지금도 답할 수 있어요" : responder + " 확인 없음";
        });
    return String.join(" · ", parts);
  }

  private static List<String> statusLines(InspectionForLink inspection, ZoneId zone) {
    List<String> lines = new ArrayList<>();
    if (inspection.status() == InspectionStatus.LOCKED) {
      // 잠김이면 inspection이 잠긴 시각을 채운다(보이는 상태가 잠김일 때만, #363)
      Instant lockedAt = Objects.requireNonNull(inspection.lockedAt(), "잠긴 점검에 잠긴 시각이 없어요");
      lines.add("잠김 · " + day(lockedAt, zone));
      if (inspection.lockReason() == LockReason.NO_ITEMS) {
        lines.add("하자 기록 없음");
      }
    } else {
      lines.add("확인 요청");
    }
    if (inspection.late()) {
      // 06 INSP-2a
      lines.add("입주 후 등록(" + day(inspection.createdAt(), zone) + ")");
      lines.add("입주 당시 상태와 다를 수 있어요");
    }
    if (inspection.asOf() != null) {
      lines.add(AS_OF.format(inspection.asOf().atZone(zone)));
    }
    return lines;
  }

  /** 문구를 정한 행동만. 그 밖은 건너뛴다(#303 "모르는 행동 종류는 건너뜁니다"). */
  private static Optional<LinkRecordResponse.HistoryItem> history(
      InspectionForLink.History line, Map<UUID, String> partNames) {
    String who = line.side() == null ? null : subject(line.side());
    String text =
        switch (line.type()) {
          case START_INSPECTION -> who == null ? null : who + " 점검을 시작했어요";
          case REQUEST_INSPECTION_CONFIRM -> who == null ? null : who + " 확인을 요청했어요";
          case CONFIRM_ALL -> who == null ? null : who + " 남은 항목을 모두 확인했어요";
          case ADD_ITEM -> who == null ? null : who + " 하자를 더했어요";
          case RESPOND_ITEM -> who == null ? null : who + " 답했어요";
          case SUPPLEMENT_ITEM -> who == null ? null : who + " 보충했어요";
          case LOCK -> "점검이 잠겼어요";
          default -> null;
        };
    if (text == null) {
      return Optional.empty();
    }
    if (line.itemId() != null) {
      String part = partNames.get(line.itemId());
      if (part == null) {
        return Optional.empty(); // 보이지 않는 항목의 행동은 inspection이 넣지 않는다. 와도 부위 없이 붙이지 않는다
      }
      text = part + " · " + text;
    }
    return Optional.of(new LinkRecordResponse.HistoryItem(text, line.side(), line.at()));
  }

  private static String title(InspectionKind kind) {
    return switch (kind) {
      case MOVE_IN -> "입주 점검";
      case MOVE_OUT -> "퇴거 점검";
    };
  }

  private static String day(Instant at, ZoneId zone) {
    return DAY.format(at.atZone(zone));
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
