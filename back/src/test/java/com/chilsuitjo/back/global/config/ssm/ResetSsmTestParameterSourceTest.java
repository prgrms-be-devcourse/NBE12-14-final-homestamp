package com.chilsuitjo.back.global.config.ssm;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * 다른 테스트가 정적 가짜 SSM에 남긴 호출 수·값·실패가 다음 테스트로 넘어가지 않는지 본다. 앞 테스트는 일부러 지우지 않고 끝나며, 지우는 것은 확장 ({@link
 * ResetSsmTestParameterSource})뿐이다. 다른 클래스에서 prod 컨텍스트가 먼저 떠 호출이 쌓여도 같은 이유로 각 테스트는 0에서 센다.
 */
@ExtendWith(ResetSsmTestParameterSource.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ResetSsmTestParameterSourceTest {

  @Test
  @Order(1)
  void 앞_테스트가_상태를_남긴다() {
    SsmTestParameterSource.setParameters(SsmTestParameterSource.validParameters());
    new SsmTestParameterSource().loadAppParameters();
    new SsmTestParameterSource().loadAppParameters();
    SsmTestParameterSource.setFailure(new IllegalStateException("left behind"));
    assertEquals(2, SsmTestParameterSource.calls());
  }

  @Test
  @Order(2)
  void 다음_테스트는_빈_상태에서_시작한다() {
    assertEquals(0, SsmTestParameterSource.calls());
    var parameters = assertDoesNotThrow(() -> new SsmTestParameterSource().loadAppParameters());
    assertEquals(0, parameters.size());
  }
}
