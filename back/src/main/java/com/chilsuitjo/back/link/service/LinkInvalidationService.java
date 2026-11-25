package com.chilsuitjo.back.link.service;

import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.link.repository.LinkRepository;
import com.chilsuitjo.back.link.rule.LinkInvalidReason;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기존 행동을 처리하는 트랜잭션 안에서 링크를 무효화한다. 호실 행을 먼저 잠그고 링크 행을 잠가 공개 열람과 같은 순서를 따른다. 호실 상태 변경을 저장하는 쪽에서 호출해 그
 * 변경과 링크 무효화가 함께 커밋되게 한다.
 */
@Service
@RequiredArgsConstructor
public class LinkInvalidationService {

  private final LinkRepository links;
  private final RoomGateReader roomGateReader;
  private final Clock clock;

  @Transactional(propagation = Propagation.MANDATORY)
  public void invalidate(UUID linkId, LinkInvalidReason reason) {
    UUID roomRecordId =
        links.findRoomRecordIdById(linkId).orElseThrow(LinkInvalidationService::notFound);
    roomGateReader.lockAndRead(roomRecordId, Actor.server());
    links
        .findByIdForUpdate(linkId)
        .orElseThrow(LinkInvalidationService::notFound)
        .invalidate(reason, clock.instant());
  }

  private static BusinessException notFound() {
    return new BusinessException(ErrorCode.NOT_FOUND);
  }
}
