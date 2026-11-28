package com.chilsuitjo.back.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 수신 거부 행({@code state.sms_opt_out}, 05, C 테이블). 추가·삭제만 하고 UPDATE는 없다(decisions 2026-11-16, V1
 * {@code trg_sms_opt_out_no_update}). 등록·해제는 네이티브 쿼리({@code SmsOptOutRepository})로 해서 만드는 생성자를 두지
 * 않는다.
 */
@Entity
@Immutable
@Table(schema = "state", name = "sms_opt_out")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SmsOptOut {

  /** 번호 HMAC({@code PhoneHmacs}, 소문자 hex 64자). */
  @Id
  @Column(name = "phone_hmac", length = 128, updatable = false)
  private String phoneHmac;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
}
