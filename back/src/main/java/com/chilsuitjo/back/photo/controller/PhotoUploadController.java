package com.chilsuitjo.back.photo.controller;

import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.photo.dto.UploadTicketsRequest;
import com.chilsuitjo.back.photo.dto.UploadTicketsResponse;
import com.chilsuitjo.back.photo.service.PhotoUploadService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 사진 업로드 표 API(06 4-5, PHO-1). */
@RestController
@RequiredArgsConstructor
public class PhotoUploadController {

  private final PhotoUploadService photoUploads;

  @PostMapping("/api/rooms/{id}/upload-tickets")
  public UploadTicketsResponse issueUploadTickets(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @Valid @RequestBody UploadTicketsRequest request) {
    return photoUploads.issue(id, memberId, request);
  }
}
