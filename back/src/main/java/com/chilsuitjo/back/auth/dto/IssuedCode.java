package com.chilsuitjo.back.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

/**
 * 커밋한 인증 행과 보낼 원문 인증번호. 원문은 문자 보내기 호출 안에서만 쓰고 저장·로그에 남기지 않는다(04 4-2). 실수로 응답에 넣어도 원문이 JSON에 나가지 않게
 * {@code code}는 직렬화하지 않고, {@code toString}에도 넣지 않는다.
 *
 * @param verificationId verify 주소의 {@code {id}}
 * @param phone 정규화한 번호(숫자만)
 * @param code 원문 6자리
 */
public record IssuedCode(UUID verificationId, String phone, @JsonIgnore String code) {

  @Override
  public String toString() {
    return "IssuedCode[verificationId=" + verificationId + "]";
  }
}
