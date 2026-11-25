package com.chilsuitjo.back.member.service;

import com.chilsuitjo.back.member.entity.Member;
import com.chilsuitjo.back.member.repository.MemberRepository;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인에서 회원 쪽 일(06 ACC-2). 이메일·비밀번호가 맞는 회원을 찾기만 하고, 토큰은 부르는 쪽(auth {@code LoginService})이 준다.
 *
 * <p>없는 이메일·틀린 비밀번호·탈퇴한 회원을 구분해 돌려주지 않는다(ACC-2d). 없는 이메일에도 해시 확인을 한 번 해서 응답 시간으로 가입 여부가 드러나지 않게 한다.
 */
@Service
@RequiredArgsConstructor
public class MemberLoginService {

  /** BCrypt가 쓰는 최대 길이(UTF-8 바이트). 가입에서 이보다 긴 비밀번호는 막으므로 넘으면 맞을 수 없다. */
  private static final int PASSWORD_MAX_BYTES = 72;

  private final MemberRepository members;
  private final PasswordEncoder passwordEncoder;

  /** 없는 이메일일 때 비교할 해시. 처음 필요할 때 만든다(동시에 둘이 만들어도 해가 없다). */
  private volatile String dummyHash;

  /**
   * 이메일(대소문자 무시)과 비밀번호가 맞는 회원 ID. 맞지 않으면 이유와 상관없이 비어 있다.
   *
   * @param email 받은 그대로의 이메일
   * @param rawPassword 받은 비밀번호
   */
  @Transactional(readOnly = true)
  public Optional<UUID> authenticate(String email, String rawPassword) {
    if (rawPassword.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
      return Optional.empty();
    }
    Optional<Member> member = members.findByEmailIgnoringCase(email);
    if (member.isEmpty()) {
      passwordEncoder.matches(rawPassword, dummyHash());
      return Optional.empty();
    }
    return member.filter(m -> m.canLogInWith(rawPassword, passwordEncoder)).map(Member::getId);
  }

  private String dummyHash() {
    String hash = dummyHash;
    if (hash == null) {
      hash = passwordEncoder.encode(UUID.randomUUID().toString());
      dummyHash = hash;
    }
    return hash;
  }
}
