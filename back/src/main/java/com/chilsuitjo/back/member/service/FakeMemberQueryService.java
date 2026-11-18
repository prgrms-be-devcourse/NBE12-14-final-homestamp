package com.chilsuitjo.back.member.service;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.member.rule.NameMask;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 회원 조회 가짜(07 1주차 2일차). DB 없이 메모리에 적어 둔 이름으로 답한다. 가리기는 실제와 같은 {@link NameMask}를 쓴다.
 *
 * <p>실제 구현(ACC-1 가입)이 들어오면 이 클래스를 지운다. 다른 도메인은 {@link MemberQueryService}만 쓰므로 바뀌지 않는다.
 */
@Service
public class FakeMemberQueryService implements MemberQueryService {

  /** 탈퇴한 회원은 이름 없이(빈 값으로) 남는다. */
  private final Map<UUID, Optional<String>> names = new ConcurrentHashMap<>();

  @Override
  public Optional<String> maskedName(UUID memberId) {
    Optional<String> name = names.get(memberId);
    if (name == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    return name.map(NameMask::mask);
  }

  /** 이 이름의 회원으로 적어 둔다. */
  public void seed(UUID memberId, String name) {
    names.put(memberId, Optional.of(name));
  }

  /** 탈퇴한 회원으로 적어 둔다. */
  public void seedWithdrawn(UUID memberId) {
    names.put(memberId, Optional.empty());
  }
}
