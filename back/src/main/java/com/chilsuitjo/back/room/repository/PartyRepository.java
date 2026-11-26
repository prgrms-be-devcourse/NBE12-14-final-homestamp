package com.chilsuitjo.back.room.repository;

import com.chilsuitjo.back.room.entity.Party;
import com.chilsuitjo.back.room.rule.PartyStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartyRepository extends JpaRepository<Party, UUID> {

  /** 이 호실의 두 자리. */
  List<Party> findByRoomRecordId(UUID roomRecordId);

  /** 여러 호실의 자리를 한 번에(내 호실 목록). */
  List<Party> findByRoomRecordIdIn(Collection<UUID> roomRecordIds);

  /**
   * 이 번호로 초대된 자리(받은 초대, 06 4-3 {@code GET /api/invitations}). 번호는 정규화된 번호다. {@code
   * ix_party_invite_phone}을 쓴다.
   */
  List<Party> findByStatusAndInvitePhone(PartyStatus status, String invitePhone);
}
