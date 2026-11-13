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
    return toResponse(e.errorCode(), null);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
    log.error("처리하지 못한 예외", e);
    return toResponse(ErrorCode.INTERNAL_ERROR, null);
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
    return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
        .body(toResponse(ErrorCode.VALIDATION_FAILED, errors).getBody());
  }

  private static ResponseEntity<ProblemDetail> toResponse(
      ErrorCode code, List<Map<String, String>> errors) {
    ProblemDetail body = ProblemDetail.forStatus(code.status());
    body.setProperty("code", code.name());
    body.setDetail(code.message());
    if (errors != null) {
      body.setProperty("errors", errors);
    }
    return ResponseEntity.status(code.status()).body(body);
  }
}
