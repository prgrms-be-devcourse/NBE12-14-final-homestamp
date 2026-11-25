package com.chilsuitjo.back.photo.service;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.photo.repository.PhotoUploadQuotaRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 한국 날짜 기준으로 회원별 업로드 표 발급 한도를 적용한다(06 PHO-1d). */
@Service
public class PhotoUploadQuotaService {

  private final PhotoUploadQuotaRepository quota;
  private final Clock clock;
  private final ZoneId zone;
  private final int dailyLimit;

  public PhotoUploadQuotaService(
      PhotoUploadQuotaRepository quota,
      Clock clock,
      @Value("${app.zone}") ZoneId zone,
      @Value("${upload.daily.member}") int dailyLimit) {
    this.quota = quota;
    this.clock = clock;
    this.zone = zone;
    this.dailyLimit = dailyLimit;
  }

  /** 표 발급과 같은 트랜잭션에서 사진 장수를 더한다. 발급에 실패하면 차감도 함께 롤백된다. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void reserve(UUID memberId, int photoCount) {
    Instant now = clock.instant();
    LocalDate today = now.atZone(zone).toLocalDate();
    if (!quota.tryAdd(memberId, today, photoCount, dailyLimit)) {
      Instant nextDay = today.plusDays(1).atStartOfDay(zone).toInstant();
      throw new BusinessException(ErrorCode.UPLOAD_LIMIT, Duration.between(now, nextDay));
    }
  }
}
