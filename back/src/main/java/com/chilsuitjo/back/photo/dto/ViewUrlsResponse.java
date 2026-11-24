package com.chilsuitjo.back.photo.dto;

import java.util.List;

/**
 * 사진 주소 받기 응답.
 *
 * @param items 사진별 열람 주소와 만료 시각. 지운 사진은 주소 대신 지운 흔적을 담는다
 */
public record ViewUrlsResponse(List<PhotoUrlResponse> items) {}
