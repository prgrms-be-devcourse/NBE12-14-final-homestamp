package com.chilsuitjo.back.photo.dto;

import java.time.Instant;
import java.util.Map;

/**
 * S3 presigned POST. 화면이 이 주소와 필드로 파일을 직접 올린다.
 *
 * @param url 파일을 직접 올릴 S3 POST 주소
 * @param fields 파일과 함께 전송할 서명·정책 등의 폼 필드
 * @param expiresAt 만료(5분)
 */
public record PresignedPostResponse(String url, Map<String, String> fields, Instant expiresAt) {}
