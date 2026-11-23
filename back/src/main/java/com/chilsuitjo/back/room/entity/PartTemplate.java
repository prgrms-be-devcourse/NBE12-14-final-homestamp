package com.chilsuitjo.back.room.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** 부위 템플릿({@code state.part_template}, 05 2-2, 고정 데이터). Flyway가 넣고 앱은 읽기만 한다. */
@Entity
@Immutable
@Table(schema = "state", name = "part_template")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PartTemplate {

  @Id private UUID id;

  @Column(nullable = false, length = 30)
  private String zone;

  @Column(nullable = false, length = 50)
  private String name;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  /** 설비·가전처럼 호실마다 켜고 끄는 부위인지. */
  @Column(nullable = false)
  private boolean optional;
}
