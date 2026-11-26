package com.chilsuitjo.back.photo.controller;

import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.photo.dto.ViewUrlsRequest;
import com.chilsuitjo.back.photo.dto.ViewUrlsResponse;
import com.chilsuitjo.back.photo.service.PhotoViewService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 사진 주소 API(06 4-5, PHO-2). 그 호실 회원만 받는다. 링크 열람자의 주소는 링크 조회가 함께 준다({@code linkPhotos}). */
@RestController
@RequiredArgsConstructor
public class PhotoViewController {

  private final PhotoViewService photoViews;

  @PostMapping("/api/photos/view-urls")
  public ViewUrlsResponse viewUrls(
      @LoginMember UUID memberId, @Valid @RequestBody ViewUrlsRequest request) {
    return photoViews.viewUrls(memberId, request);
  }
}
