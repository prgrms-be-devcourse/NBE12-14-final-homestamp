package com.chilsuitjo.back.global.error;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 예외를 RFC 9457 Problem Details + {@code code} 칸으로 바꾼다(명세 4-10). 예외 이름, SQL, 스택 같은 서버 내부 정보는 응답에
 * 넣지 않는다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  ResponseEntity<ProblemDetail> handleBusiness(BusinessException e) {
    ErrorCode code = e.errorCode();
    return ResponseEntity.status(code.status()).body(problem(code, code.status(), null));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
    log.error("처리하지 못한 예외", e);
    ErrorCode code = ErrorCode.INTERNAL_ERROR;
    return ResponseEntity.status(code.status()).body(problem(code, code.status(), null));
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<Map<String, String>> errors =
        e.getBindingResult().getFieldErrors().stream()
            .map(
                f ->
                    Map.of(
                        "field",
                        f.getField(),
                        "message",
                        f.getDefaultMessage() == null ? "" : f.getDefaultMessage()))
            .toList();
    ProblemDetail body = problem(ErrorCode.VALIDATION_FAILED, status, errors);
    return super.handleExceptionInternal(e, body, headers, status, request);
  }

  /**
   * Spring이 직접 처리하는 예외(깨진 JSON, 없는 주소, 허용 안 된 메서드 등)도 {@code code}를 넣는다. 상태는 Spring이 정한 값을 그대로 두고,
   * 코드는 상태로 정한다(명세 4-10).
   */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    if (status.is5xxServerError()) {
      log.error("처리하지 못한 예외", e);
    }
    ProblemDetail problem = problem(codeFor(status), status, null);
    return super.handleExceptionInternal(e, problem, headers, status, request);
  }

  static ErrorCode codeFor(HttpStatusCode status) {
    if (status.is5xxServerError()) {
      return ErrorCode.INTERNAL_ERROR;
    }
    return switch (status.value()) {
      case 400 -> ErrorCode.VALIDATION_FAILED;
      case 404 -> ErrorCode.NOT_FOUND;
      default -> ErrorCode.UNSUPPORTED_REQUEST;
    };
  }

  private static ProblemDetail problem(
      ErrorCode code, HttpStatusCode status, List<Map<String, String>> errors) {
    ProblemDetail body = ProblemDetail.forStatus(status);
    body.setProperty("code", code.name());
    body.setDetail(code.message());
    if (errors != null) {
      body.setProperty("errors", errors);
    }
    return body;
  }
}
