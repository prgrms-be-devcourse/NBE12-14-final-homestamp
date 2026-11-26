package com.chilsuitjo.back.photo.repository;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 회원별 하루 사진 업로드 표 발급 횟수를 기록한다(05 rate_counter). */
@Repository
@RequiredArgsConstructor
public class PhotoUploadQuotaRepository {

  private final EntityManager entityManager;

  /** 요청한 장수를 전부 더할 수 있을 때만 갱신한다. */
  public boolean tryAdd(UUID memberId, LocalDate day, int photoCount, int dailyLimit) {
    return entityManager
            .createNativeQuery(
                """
                INSERT INTO state.rate_counter AS c (counter_key, window_date, count)
                SELECT :counterKey, :day, :photoCount
                WHERE :photoCount BETWEEN 1 AND :dailyLimit
                ON CONFLICT (counter_key, window_date)
                DO UPDATE SET count = c.count + EXCLUDED.count
                WHERE c.count <= :dailyLimit - EXCLUDED.count
                """)
            .setParameter("counterKey", "upload:" + memberId)
            .setParameter("day", day)
            .setParameter("photoCount", photoCount)
            .setParameter("dailyLimit", dailyLimit)
            .executeUpdate()
        == 1;
  }
}
