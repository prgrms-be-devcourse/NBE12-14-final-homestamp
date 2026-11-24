package com.chilsuitjo.back.photo.dto;

import java.util.List;

/**
 * 업로드 표 받기({@code POST /api/rooms/{id}/upload-tickets}).
 *
 * @param files 업로드할 사진 1~10장의 파일 정보
 */
public record UploadTicketsRequest(List<UploadFileRequest> files) {}
