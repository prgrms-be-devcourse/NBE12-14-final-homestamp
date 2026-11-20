package com.chilsuitjo.back.link.service;

import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.link.dto.LinkRecordResponse;
import com.chilsuitjo.back.link.dto.LinkRoom;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 링크 화면 값 가짜. 테스트가 정해 둔 호실·기록을 돌려준다.
 *
 * <p>room·repair·inspection·photo·payment의 공개 조회가 들어오면 그것을 부르는 실제 구현으로 바꾸고 이 클래스를 지운다.
 */
@Service
public class FakeLinkContentSource implements LinkContentSource {

  private final Map<Key, LinkRoom> rooms = new ConcurrentHashMap<>();
  private final Map<Key, LinkRecordResponse> records = new ConcurrentHashMap<>();
  private final Set<Key> withMoney = ConcurrentHashMap.newKeySet();

  @Override
  public Optional<LinkRoom> room(UUID roomRecordId, UUID partyId) {
    return Optional.ofNullable(rooms.get(new Key(roomRecordId, partyId)));
  }

  @Override
  public Optional<LinkRecordResponse> record(UUID roomRecordId, TargetKind kind, UUID recordId) {
    return Optional.ofNullable(records.get(new Key(roomRecordId, recordId)))
        .filter(record -> record.targetKind() == kind);
  }

  @Override
  public boolean hasMoney(UUID roomRecordId, TargetKind kind, UUID recordId) {
    return withMoney.contains(new Key(roomRecordId, recordId));
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
