package com.chilsuitjo.back.link.service;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.link.dto.LinkRecordResponse;
import com.chilsuitjo.back.link.dto.LinkRoom;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Primary;

/**
 * 테스트용 링크 화면 값. 테스트가 정해 둔 호실·기록을 돌려주고 보는 측은 보지 않는다. 운영 구현은 {@link LinkContents}다.
 *
 * <p>쓰는 법: 스프링 테스트는 {@code @Import(FakeLinkContentSource.class)}로 넣는다({@code @Primary}라 실제 구현 대신
 * 쓰인다). 단위 테스트는 직접 만든다.
 */
@Primary
public class FakeLinkContentSource implements LinkContentSource {

  private final Map<Key, LinkRoom> rooms = new ConcurrentHashMap<>();
  private final Map<Key, LinkRecordResponse> records = new ConcurrentHashMap<>();
  private final Set<Key> withMoney = ConcurrentHashMap.newKeySet();

  @Override
  public Optional<LinkRoom> room(UUID roomRecordId, UUID partyId) {
    return Optional.ofNullable(rooms.get(new Key(roomRecordId, partyId)));
  }

  @Override
  public Optional<LinkRecordResponse> record(
      UUID roomRecordId, TargetKind kind, UUID recordId, PartySide viewerSide) {
    return Optional.ofNullable(records.get(new Key(roomRecordId, recordId)))
        .filter(record -> record.targetKind() == kind);
  }

  @Override
  public boolean hasMoney(UUID roomRecordId, UUID repairCaseId) {
    return withMoney.contains(new Key(roomRecordId, repairCaseId));
  }

  public void seedRoom(UUID roomRecordId, UUID partyId, LinkRoom room) {
    rooms.put(new Key(roomRecordId, partyId), room);
  }

  public void seedRecord(
      UUID roomRecordId, UUID recordId, LinkRecordResponse record, boolean money) {
    records.put(new Key(roomRecordId, recordId), record);
    if (money) {
      withMoney.add(new Key(roomRecordId, recordId));
    }
  }

  private record Key(UUID roomRecordId, UUID id) {}
}
