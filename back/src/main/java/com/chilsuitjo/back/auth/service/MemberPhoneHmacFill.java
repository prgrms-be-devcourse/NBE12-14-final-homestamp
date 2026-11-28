package com.chilsuitjo.back.auth.service;

import com.chilsuitjo.back.member.repository.MemberRepository.PhoneToFill;
import com.chilsuitjo.back.member.service.MemberPhoneHmacService;
import com.chilsuitjo.back.notification.service.PhoneHmacs;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 번호 HMAC이 빈 회원 행을 채운다(#462). 마이그레이션에는 HMAC 키가 없어 V14가 기존 행을 채우지 못하고, 블루/그린 전환 중이나 이전 앱으로 되돌린 동안에는
 * 이 칸을 모르는 앱이 빈 행을 더 만든다. 그래서 한 번이 아니라 켜질 때와 일정 간격으로 돈다({@link MemberPhoneHmacFillPoller}).
 *
 * <p>행마다 따로 커밋한다: 읽은 번호가 그대로일 때만 채우므로(그사이 번호가 바뀌면 0줄) 번호 잠금을 잡지 않고, blue·green이 함께 돌아도 결과가 같다. 계산할
 * 수 없는 번호(정규화 실패 등)는 그 행만 건너뛰고 회원 ID만 남긴다. 그 행이 남아 있는 동안 회원 확인({@code
 * MemberPhoneHmacService.isVerifiedActive})은 예외를 내므로 조용히 넘어가지 않는다. 번호와 HMAC은 로그에 남기지 않는다.
 */
@Service
@RequiredArgsConstructor
public class MemberPhoneHmacFill {

  private static final Logger log = LoggerFactory.getLogger(MemberPhoneHmacFill.class);

  /** PostgreSQL uuid 순서(부호 없는 바이트 순)에서 가장 작은 값. 회원 ID는 v7이라 이 값이 아니다. */
  private static final UUID FIRST = new UUID(0L, 0L);

  static final int BATCH_SIZE = 200;

  private final MemberPhoneHmacService members;
  private final PhoneHmacs phoneHmacs;

  /** 지금 빈 행을 모두 한 번씩 본다. 채운 행 수를 돌려준다. */
  public int fillAll() {
    int filled = 0;
    int skipped = 0;
    UUID after = FIRST;
    List<PhoneToFill> batch;
    do {
      batch = members.phonesWithoutHmac(after, BATCH_SIZE);
      for (PhoneToFill row : batch) {
        after = row.getId();
        String phoneHmac;
        try {
          phoneHmac = phoneHmacs.phoneHmac(row.getPhone());
        } catch (RuntimeException e) {
          skipped++;
          log.warn("번호 HMAC 채우기: 계산할 수 없는 번호, 회원 {}", row.getId());
          continue;
        }
        if (members.fill(row.getId(), row.getPhone(), phoneHmac)) {
          filled++;
        }
      }
    } while (batch.size() == BATCH_SIZE);
    if (filled > 0 || skipped > 0) {
      log.info("번호 HMAC 채우기: 채움 {}, 건너뜀 {}", filled, skipped);
    }
    return filled;
  }
}
