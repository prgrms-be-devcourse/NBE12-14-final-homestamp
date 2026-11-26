package com.chilsuitjo.back.photo.dto;

import com.chilsuitjo.back.photo.rule.PhotoUploadFileRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 업로드 표 받기({@code POST /api/rooms/{id}/upload-tickets}).
 *
 * @param files 업로드할 사진 1~{@link PhotoUploadFileRules#MAX_BATCH}장의 파일 정보
 */
public record UploadTicketsRequest(
    @NotEmpty @Size(max = PhotoUploadFileRules.MAX_BATCH)
        List<@NotNull @Valid UploadFileRequest> files) {}
