package com.chilsuitjo.back.photo.dto;

/**
 * 개인정보 사진 지우기({@code POST /api/photos/{id}/erase}).
 *
 * @param redactedUpload [지우고 가린 사본 올리기]면 가린 사본의 업로드 표. 없으면 null
 */
public record ErasePhotoRequest(String redactedUpload) {}
