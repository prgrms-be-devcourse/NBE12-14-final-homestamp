package com.chilsuitjo.back.photo.dto;

import com.chilsuitjo.back.photo.rule.PhotoUrlRules;
import com.chilsuitjo.back.photo.rule.PhotoVariant;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * 사진 주소 받기({@code POST /api/photos/view-urls}).
 *
 * @param photoIds 열람 주소를 받을 사진 ID 1~{@link PhotoUrlRules#MAX_BATCH}장. 한 호실의 사진이고 같은 ID가 두 번 오면 안 된다
 * @param variant 받을 주소의 종류(썸네일 또는 원본)
 */
public record ViewUrlsRequest(
    @NotEmpty @Size(max = PhotoUrlRules.MAX_BATCH) List<@NotNull UUID> photoIds,
    @NotNull PhotoVariant variant) {}
