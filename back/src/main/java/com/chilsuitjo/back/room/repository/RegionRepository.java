package com.chilsuitjo.back.room.repository;

import com.chilsuitjo.back.room.entity.Region;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegionRepository extends JpaRepository<Region, String> {

  /** 코드표에 있고 지금 고를 수 있는 법정동인지(06 4-3 {@code POST /api/rooms}). */
  boolean existsByCodeAndActiveTrue(String code);
}
