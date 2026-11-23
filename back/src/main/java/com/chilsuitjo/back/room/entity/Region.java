package com.chilsuitjo.back.room.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 법정동 코드표({@code state.region}, 05 2-2, 고정 데이터). 없어진 동은 지우지 않고 {@code active = false}로 고를 수 없게만
 * 한다(06 0-1 "주소 저장 범위").
 */
@Entity
@Immutable
@Table(schema = "state", name = "region")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Region {

  @Id
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 10)
  private String code;

  @Column(nullable = false, length = 20)
  private String sido;

  /** 세종특별자치시는 시·군·구가 없어 null. */
  @Column(length = 30)
  private String sigungu;

  @Column(nullable = false, length = 30)
  private String dong;

  @Column(nullable = false)
  private boolean active;
}
