package com.chilsuitjo.back.global.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
  }
}
