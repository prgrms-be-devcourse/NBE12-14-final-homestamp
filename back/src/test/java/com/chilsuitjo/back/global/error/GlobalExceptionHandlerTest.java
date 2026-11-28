package com.chilsuitjo.back.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.persistence.OptimisticLockException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 명세 4-10: 모든 오류 응답에 code가 있고, detail은 코드의 문구다. */
class GlobalExceptionHandlerTest {

  private static final String SYNTHETIC_DETAIL =
      "email=person@example.invalid address=가상로 123 token=fake-token SQL=INSERT INTO secret VALUES ('input')";

  private MockMvc mvc;
  private Logger handlerLogger;
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void setUp() {
    handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    logs = new ListAppender<>();
    logs.start();
    handlerLogger.addAppender(logs);
    mvc =
        MockMvcBuilders.standaloneSetup(new 시험용_컨트롤러())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @AfterEach
  void tearDown() {
    handlerLogger.detachAppender(logs);
    logs.stop();
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
    assertThat(logs.list).hasSize(1);
    assertThat(logs.list.getFirst().getFormattedMessage()).isEqualTo("처리하지 못한 예외");
    assertThat(logs.list.getFirst().getThrowableProxy()).isNotNull();
  }

  @Test
  void DB_제약_오류는_원본_메시지와_스택을_로그에_남기지_않는다() throws Exception {
    mvc.perform(post("/시험/DB-제약"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.detail").value(ErrorCode.INTERNAL_ERROR.message()));
    assertSafeDbLog();
  }

  @Test
  void 트랜잭션_포장_예외도_원인에_DB_제약_오류가_있을_때만_보호한다() throws Exception {
    mvc.perform(post("/시험/포장된-DB-제약"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.detail").value(ErrorCode.INTERNAL_ERROR.message()));
    assertSafeDbLog();
  }

  @Test
  void 원인_순환_참조가_있어도_DB_제약_오류를_안전하게_기록한다() throws Exception {
    DataIntegrityViolationException constraint =
        new DataIntegrityViolationException(SYNTHETIC_DETAIL);
    RuntimeException wrapper = new RuntimeException("합성 래퍼");
    constraint.initCause(wrapper);
    wrapper.initCause(constraint);

    var response =
        new GlobalExceptionHandler()
            .handleUnexpected(new TransactionSystemException("합성 순환 원인", constraint));

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
    assertThat(response.getBody().getDetail()).isEqualTo(ErrorCode.INTERNAL_ERROR.message());
    assertSafeDbLog();
  }

  @Test
  void DB_제약이_없는_트랜잭션_예외는_일반_예외로_처리한다() throws Exception {
    mvc.perform(post("/시험/다른-트랜잭션-오류"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    assertThat(logs.list).hasSize(1);
    assertThat(logs.list.getFirst().getFormattedMessage()).isEqualTo("처리하지 못한 예외");
    assertThat(logs.list.getFirst().getThrowableProxy()).isNotNull();
  }

  private void assertSafeDbLog() {
    assertThat(logs.list).hasSize(1);
    ILoggingEvent event = logs.list.getFirst();
    assertThat(event.getFormattedMessage()).isEqualTo("DB_CONSTRAINT_ERROR");
    assertThat(event.getFormattedMessage()).doesNotContain(SYNTHETIC_DETAIL);
    assertThat(event.getFormattedMessage())
        .doesNotContain("person@example.invalid", "가상로 123", "fake-token", "INSERT INTO", "input");
    assertThat(event.getThrowableProxy()).isNull();
  }

  @Test
  void 남은_시간이_있으면_Retry_After에_초로_올림해_넣는다() throws Exception {
    mvc.perform(post("/시험/잠시"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "40"))
        .andExpect(jsonPath("$.photoIndex").doesNotExist())
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  }

  @Test
  void 남은_시간이_없으면_Retry_After를_넣지_않는다() throws Exception {
    mvc.perform(post("/시험/규칙"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().doesNotExist("Retry-After"))
        .andExpect(jsonPath("$.photoIndex").doesNotExist())
        .andExpect(jsonPath("$.duplicateOf").doesNotExist())
        .andExpect(jsonPath("$.code").value("DAILY_LIMIT"));
  }

  @Test
  void 중복_지불이면_기존_기록_ID와_비슷한_기록인지를_응답에_넣는다() throws Exception {
    mvc.perform(post("/시험/중복"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_PAYMENT"))
        .andExpect(jsonPath("$.duplicateOf").value(시험용_컨트롤러.DUPLICATE_ID.toString()))
        .andExpect(jsonPath("$.similar").value(true))
        .andExpect(jsonPath("$.photoIndex").doesNotExist());
  }

  @Test
  void 사진_번호가_있으면_0부터_시작하는_번호를_응답에_넣는다() throws Exception {
    mvc.perform(post("/시험/사진"))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.code").value("PHOTO_CHECK_FAILED"))
        .andExpect(jsonPath("$.photoIndex").value(0))
        .andExpect(header().doesNotExist("Retry-After"));
  }

  @Test
  void 낙관적_잠금_충돌은_409_VERSION_CONFLICT() throws Exception {
    // Spring이 감싼 것(트랜잭션 커밋·@Repository)과 JPA 예외가 그대로 올라온 것 둘 다
    for (String path : new String[] {"/시험/버전-spring", "/시험/버전-jpa"}) {
      mvc.perform(post(path))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
          .andExpect(jsonPath("$.detail").value(ErrorCode.VERSION_CONFLICT.message()));
    }
  }

  @Test
  void 초는_올림하고_0초_이하는_1초다() {
    assertThat(GlobalExceptionHandler.seconds(Duration.ofSeconds(40))).isEqualTo(40);
    assertThat(GlobalExceptionHandler.seconds(Duration.ofMillis(40_001))).isEqualTo(41);
    assertThat(GlobalExceptionHandler.seconds(Duration.ZERO)).isEqualTo(1);
  }

  @RestController
  static class 시험용_컨트롤러 {
    static final UUID DUPLICATE_ID = UUID.fromString("0193a000-0000-7000-8000-000000000001");

    record 요청(String 값) {}

    @PostMapping("/시험")
    String 받기(@RequestBody 요청 요청) {
      return "ok";
    }

    @PostMapping("/시험/터짐")
    String 터짐() {
      throw new IllegalStateException("SELECT * FROM secret");
    }

    @PostMapping("/시험/DB-제약")
    String db제약() {
      throw new DataIntegrityViolationException(SYNTHETIC_DETAIL);
    }

    @PostMapping("/시험/포장된-DB-제약")
    String 포장된Db제약() {
      throw new TransactionSystemException(
          "합성 트랜잭션 실패", new DataIntegrityViolationException(SYNTHETIC_DETAIL));
    }

    @PostMapping("/시험/다른-트랜잭션-오류")
    String 다른트랜잭션오류() {
      throw new TransactionSystemException("다른 실패", new IllegalStateException("원인"));
    }

    @PostMapping("/시험/잠시")
    String 잠시() {
      throw new BusinessException(ErrorCode.RATE_LIMITED, Duration.ofMillis(39_200));
    }

    @PostMapping("/시험/규칙")
    String 규칙() {
      throw new BusinessException(ErrorCode.DAILY_LIMIT);
    }

    @PostMapping("/시험/사진")
    String 사진() {
      throw new BusinessException(ErrorCode.PHOTO_CHECK_FAILED, 0);
    }

    @PostMapping("/시험/중복")
    String 중복() {
      throw BusinessException.duplicatePayment(DUPLICATE_ID, true);
    }

    @PostMapping("/시험/버전-spring")
    String 버전_spring() {
      throw new ObjectOptimisticLockingFailureException("RepairCase", "id");
    }

    @PostMapping("/시험/버전-jpa")
    String 버전_jpa() {
      throw new OptimisticLockException("Row was updated or deleted by another transaction");
    }
  }
}
