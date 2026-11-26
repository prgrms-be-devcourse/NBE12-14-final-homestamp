package com.chilsuitjo.back.member.service;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.member.entity.Member;
import com.chilsuitjo.back.member.repository.MemberRepository;
import com.chilsuitjo.back.member.rule.MemberStatus;
import com.chilsuitjo.back.member.rule.NameMask;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 조회 창구의 실제 구현(가입 ACC-1 뒤 가짜를 바꿈). {@code state.member}를 읽기만 하고 다른 도메인을 부르지 않는다(decisions
 * 2026-11-19).
 *
 * <p>탈퇴한 회원은 신원 칸을 지우므로 이름·번호 모두 비어 있다(05 2-1). 이름 칸이 비어 있는 회원도 이름이 없는 것으로 본다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JpaMemberQueryService implements MemberQueryService {

  private final MemberRepository members;

  @Override
  public Optional<String> maskedName(UUID memberId) {
    return nameOf(find(memberId));
  }

  @Override
  public Map<UUID, Optional<String>> maskedNames(Collection<UUID> memberIds) {
    Set<UUID> ids = new LinkedHashSet<>(memberIds);
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Member> found =
        members.findAllById(ids).stream()
            .collect(Collectors.toMap(Member::getId, Function.identity()));
    Map<UUID, Optional<String>> names = new LinkedHashMap<>();
    for (UUID id : ids) {
      Member member = found.get(id);
      if (member == null) {
        throw new BusinessException(ErrorCode.NOT_FOUND);
      }
      names.put(id, nameOf(member));
    }
    return names;
  }

  @Override
  public Optional<String> verifiedPhone(UUID memberId) {
    Member member = find(memberId);
    if (member.getStatus() == MemberStatus.WITHDRAWN) {
      return Optional.empty();
    }
    return Optional.ofNullable(member.getPhone());
  }

  private Member find(UUID memberId) {
    return members.findById(memberId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private static Optional<String> nameOf(Member member) {
    if (member.getStatus() == MemberStatus.WITHDRAWN || member.getDisplayName() == null) {
      return Optional.empty();
    }
    return Optional.of(NameMask.mask(member.getDisplayName()));
  }
}
