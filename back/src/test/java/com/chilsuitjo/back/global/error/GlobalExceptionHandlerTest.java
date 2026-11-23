package com.chilsuitjo.back.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 명세 4-10: 모든 오류 응답에 code가 있고, detail은 코드의 문구다. */
class GlobalExceptionHandlerTest {

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new 시험용_컨트롤러())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void 깨진_JSON은_VALIDATION_FAILED() throws Exception {
    mvc.perform(post("/시험").contentType(MediaType.APPLICATION_JSON).content("{깨짐"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.detail").value(ErrorCode.VALIDATION_FAILED.message()));
  }

  @Test
  void 없는_주소는_NOT_FOUND() throws Exception {
    mvc.perform(get("/없는-주소"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void 허용_안_된_메서드는_UNSUPPORTED_REQUEST이고_상태는_405_그대로() throws Exception {
    mvc.perform(get("/시험"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().exists("Allow"))
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_REQUEST"))
        .andExpect(jsonPath("$.detail").value(ErrorCode.UNSUPPORTED_REQUEST.message()));
  }

  @Test
  void 지원_안_하는_형식은_UNSUPPORTED_REQUEST이고_상태는_415_그대로() throws Exception {
    mvc.perform(post("/시험").contentType(MediaType.TEXT_PLAIN).content("글"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_REQUEST"));
  }

  @Test
  void 예상_못_한_예외는_INTERNAL_ERROR이고_내부_정보가_없다() throws Exception {
    mvc.perform(post("/시험/터짐"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.detail").value(ErrorCode.INTERNAL_ERROR.message()));
  }

  @Test
  void 남은_시간이_있으면_Retry_After에_초로_올림해_넣는다() throws Exception {
    mvc.perform(post("/시험/잠시"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "40"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  }

  @Test
  void 남은_시간이_없으면_Retry_After를_넣지_않는다() throws Exception {
    mvc.perform(post("/시험/규칙"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().doesNotExist("Retry-After"))
        .andExpect(jsonPath("$.code").value("DAILY_LIMIT"));
  }

  @Test
  void 초는_올림하고_0초_이하는_1초다() {
    assertThat(GlobalExceptionHandler.seconds(Duration.ofSeconds(40))).isEqualTo(40);
    assertThat(GlobalExceptionHandler.seconds(Duration.ofMillis(40_001))).isEqualTo(41);
    assertThat(GlobalExceptionHandler.seconds(Duration.ZERO)).isEqualTo(1);
  }

  @RestController
  static class 시험용_컨트롤러 {
    record 요청(String 값) {}

    @PostMapping("/시험")
    String 받기(@RequestBody 요청 요청) {
      return "ok";
    }

    @PostMapping("/시험/터짐")
    String 터짐() {
      throw new IllegalStateException("SELECT * FROM secret");
    }

    @PostMapping("/시험/잠시")
    String 잠시() {
      throw new BusinessException(ErrorCode.RATE_LIMITED, Duration.ofMillis(39_200));
    }

    @PostMapping("/시험/규칙")
    String 규칙() {
      throw new BusinessException(ErrorCode.DAILY_LIMIT);
    }
  }
}
