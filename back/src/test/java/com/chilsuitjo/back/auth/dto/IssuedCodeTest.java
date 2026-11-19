package com.chilsuitjo.back.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 원문 인증번호와 인증 표는 로그(toString)에, 원문 인증번호는 응답(JSON)에도 나가지 않는다(04 4-2). */
class IssuedCodeTest {

  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void 원문_인증번호는_JSON과_로그에_나가지_않는다() {
    UUID id = UUID.randomUUID();
    IssuedCode issued = new IssuedCode(id, "01012345678", "482915");

    assertThat(json.writeValueAsString(issued)).doesNotContain("482915").contains(id.toString());
    assertThat(issued.toString()).doesNotContain("482915").doesNotContain("01012345678");
  }

  @Test
  void 인증_표는_로그에_나가지_않는다() {
    VerificationTicket ticket = new VerificationTicket("id.signature", Instant.EPOCH);

    assertThat(ticket.toString()).doesNotContain("signature");
  }
}
