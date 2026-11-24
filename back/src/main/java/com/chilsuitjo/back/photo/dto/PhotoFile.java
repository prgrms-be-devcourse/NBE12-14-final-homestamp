package com.chilsuitjo.back.photo.dto;

/**
 * 사진 파일의 저장 위치·지문·크기. 사진 등록에 필요한 파일 정보를 묶어 전달한다.
 *
 * @param originalKey 원본 파일의 S3 객체 키
 * @param thumbKey 썸네일 파일의 S3 객체 키
 * @param originalSha256 원본 파일의 SHA-256 지문
 * @param thumbSha256 썸네일 파일의 SHA-256 지문
 * @param sizeBytes 사진 파일 크기(바이트)
 */
public record PhotoFile(
    String originalKey,
    String thumbKey,
    String originalSha256,
    String thumbSha256,
    long sizeBytes) {}
