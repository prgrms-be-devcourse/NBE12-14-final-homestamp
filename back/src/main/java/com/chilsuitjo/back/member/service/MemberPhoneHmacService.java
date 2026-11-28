package com.chilsuitjo.back.member.service;

import com.chilsuitjo.back.member.dto.PhoneToFill;
import com.chilsuitjo.back.member.repository.MemberRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 번호 HMAC({@code state.member.phone_hmac}, #462)의 회원 쪽 일. HMAC 계산과 번호 잠금은 auth가 한다(키와 잠금이 auth에
 * 있음). member는 다른 도메인을 부르지 않고 값만 읽고 쓴다.
 *
 * <p>V14부터 HMAC은 "비었거나 지금 번호의 값"이다(번호만 바뀐 UPDATE는 트리거가 비움). 빈 값은 V14 전에 가입한 회원과 이 칸을 모르는 이전 앱이
 * 블루/그린 전환 중에 쓴 행에서 생기고, auth 채우기({@code MemberPhoneHmacFill})가 채운다.
 */
@Service
@RequiredArgsConstructor
public class MemberPhoneHmacService {

  private final MemberRepository members;

  /** 번호는 있는데 HMAC이 빈 행을 {@code afterId}보다 큰 ID부터 ID 순으로 {@code limit}개 읽는다. */
  @Transactional(readOnly = true)
  public List<PhoneToFill> phonesWithoutHmac(UUID afterId, int limit) {
    return members.findPhonesWithoutHmac(afterId, limit).stream()
        .map(row -> new PhoneToFill(row.getId(), row.getPhone()))
        .toList();
  }

  /**
   * 그 행의 번호가 {@code phone}과 같고 HMAC이 비어 있을 때만 채운다. 채웠으면 {@code true}. 그사이 번호가 바뀌었거나 이미 채워졌으면 {@code
   * false}이고 아무것도 바꾸지 않는다.
   *
   * @param phoneHmac {@code phone}의 번호 HMAC({@code PhoneHmacs})
   */
  @Transactional
  public boolean fill(UUID memberId, String phone, String phoneHmac) {
    return members.fillPhoneHmac(memberId, phone, phoneHmac) == 1;
  }

  /**
   * 이 번호 HMAC으로 인증한 활동 회원이 있는가(#83 Q2). 부르는 쪽(auth)이 같은 트랜잭션에서 그 번호의 잠금을 먼저 잡는다.
   *
   * <p>HMAC이 빈 행이 하나라도 있으면 그 행이 이 번호의 회원일 수 있어 {@code false}로 답하지 않고 예외를 낸다(회원 번호를 비회원 번호로 잘못 보아 수신
   * 거부 목록에 넣는 일이 없게). 2단계 V 파일이 {@code (phone IS NULL) = (phone_hmac IS NULL)}을 걸면 이 검사를 뺀다.
   *
   * @throws IllegalStateException 아직 채우지 않은 행이 있음
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean isVerifiedActive(String phoneHmac) {
    if (members.existsPhoneWithoutHmac()) {
      throw new IllegalStateException("번호 HMAC을 아직 채우지 않은 회원이 있다(#462 채우기 전)");
    }
    return members.existsActiveByPhoneHmac(phoneHmac);
  }
}
