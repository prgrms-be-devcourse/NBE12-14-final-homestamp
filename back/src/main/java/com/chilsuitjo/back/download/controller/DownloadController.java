package com.chilsuitjo.back.download.controller;

import com.chilsuitjo.back.download.dto.RoomDownload;
import com.chilsuitjo.back.download.service.DownloadService;
import com.chilsuitjo.back.global.security.LoginMember;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 내려받기 API(06 4장 {@code GET /api/rooms/{id}/download}, S-19). */
@RestController
@RequiredArgsConstructor
public class DownloadController {

  private final DownloadService downloads;

  /**
   * 내려받기 목록(DL-1). {@code 기록.json} 내용과 사진 ID 묶음을 준다. 원본 주소는 묶음마다 따로 받는다.
   *
   * <p>주소를 받는 API는 아직 정하는 중이다(#246). 정해지면 06 4장 이 줄과 함께 적는다.
   */
  @GetMapping("/api/rooms/{id}/download")
  public RoomDownload download(@PathVariable UUID id, @LoginMember UUID memberId) {
    return downloads.download(id, memberId);
  }
}
