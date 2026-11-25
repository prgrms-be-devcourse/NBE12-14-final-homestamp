package com.chilsuitjo.back.room.repository;

import com.chilsuitjo.back.room.entity.Party;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartyRepository extends JpaRepository<Party, UUID> {

  /** 이 호실의 두 자리. */
  List<Party> findByRoomRecordId(UUID roomRecordId);

  /** 여러 호실의 자리를 한 번에(내 호실 목록). */
  List<Party> findByRoomRecordIdIn(Collection<UUID> roomRecordIds);
}
