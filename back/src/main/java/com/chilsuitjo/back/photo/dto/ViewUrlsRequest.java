package com.chilsuitjo.back.photo.dto;

import com.chilsuitjo.back.photo.rule.PhotoVariant;
import java.util.List;
import java.util.UUID;

/**
 * 사진 주소 받기({@code POST /api/photos/view-urls}).
 *
 * @param photoIds 열람 주소를 받을 사진 ID 목록
 * @param variant 받을 주소의 종류(썸네일 또는 원본)
 */
public record ViewUrlsRequest(List<UUID> photoIds, PhotoVariant variant) {}
