package com.chilsuitjo.back.inspection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.inspection.entity.Inspection;
import com.chilsuitjo.back.inspection.entity.InspectionItem;
import com.chilsuitjo.back.inspection.repository.InspectionItemRepository;
import com.chilsuitjo.back.inspection.repository.InspectionRepository;
import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import com.chilsuitjo.back.notification.dto.AwaitingConfirm;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 확인 요청 상태 점검 읽기(#317, {@code InspectionsAwaitingConfirm}). 어느 점검을 주는지만 본다. 초대 문자·요청과 잇는 것은 {@code
 * RoomInviteApiTest}가 실제 DB로 본다.
 */
class JpaInspectionsAwaitingConfirmTest {

  private final InspectionRepository inspections = mock(InspectionRepository.class);
  private final InspectionItemRepository items = mock(InspectionItemRepository.class);
  private final JpaInspectionsAwaitingConfirm port =
      new JpaInspectionsAwaitingConfirm(inspections, items);
  private final UUID room = UUID.randomUUID();

  @Test
  void 확인_요청_상태_점검을_쓴_측을_기준_측으로_준다() {
    Inspection row = inspection(PartySide.LANDLORD);
    requested(row);
    items(row, item(PartySide.LANDLORD, AckState.PENDING));

    assertThat(port.of(room)).containsExactly(new AwaitingConfirm(row.getId(), PartySide.LANDLORD));
  }

  @Test
  void 쓴_측_항목이_없으면_하자_기록_없음_확인을_기다려_준다() {
    Inspection row = inspection(PartySide.TENANT);
    requested(row);
    items(row);

    assertThat(port.of(room)).containsExactly(new AwaitingConfirm(row.getId(), PartySide.TENANT));
  }

  @Test
  void 미확인_항목이_남아_있어도_준다() {
    Inspection row = inspection(PartySide.TENANT);
    requested(row);
    items(row, item(PartySide.TENANT, AckState.ACK), item(PartySide.TENANT, AckState.UNCONFIRMED));

    assertThat(port.of(room)).hasSize(1);
  }

  @Test
  void 쓴_측_항목에_모두_답했으면_주지_않는다() {
    // 주면 닫힌 요청 대신 새 확인 요청이 하나 더 열린다(포트 계약)
    Inspection row = inspection(PartySide.TENANT);
    requested(row);
    items(
        row,
        item(PartySide.TENANT, AckState.ACK),
        item(PartySide.TENANT, AckState.DISPUTE),
        item(PartySide.LANDLORD, AckState.PENDING));

    assertThat(port.of(room)).isEmpty();
  }

  @Test
  void 확인_요청_상태가_아닌_점검은_읽지_않는다() {
    when(inspections.findByRoomRecordIdAndStatusOrderByCreatedAtAsc(
            room, InspectionStatus.REQUESTED))
        .thenReturn(List.of());

    assertThat(port.of(room)).isEmpty();
  }

  @Test
  void 호실이_없으면_거절한다() {
    assertThatThrownBy(() -> port.of(null)).isInstanceOf(NullPointerException.class);
  }

  private void requested(Inspection... rows) {
    when(inspections.findByRoomRecordIdAndStatusOrderByCreatedAtAsc(
            room, InspectionStatus.REQUESTED))
        .thenReturn(List.of(rows));
  }

  private void items(Inspection row, InspectionItem... rows) {
    when(items.findByInspectionIdOrderByIdAsc(row.getId())).thenReturn(List.of(rows));
  }

  private static Inspection inspection(PartySide author) {
    Inspection row = mock(Inspection.class);
    when(row.getId()).thenReturn(UUID.randomUUID());
    when(row.getAuthorSide()).thenReturn(author);
    return row;
  }

  private static InspectionItem item(PartySide addedBy, AckState status) {
    InspectionItem row = mock(InspectionItem.class);
    when(row.getAddedBySide()).thenReturn(addedBy);
    when(row.getStatus()).thenReturn(status);
    return row;
  }
}
