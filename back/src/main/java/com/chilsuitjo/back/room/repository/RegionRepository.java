package com.chilsuitjo.back.room.repository;

import com.chilsuitjo.back.room.dto.RegionOptionResponse;
import com.chilsuitjo.back.room.entity.Region;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 법정동 코드표. 코드표에는 읍·면·동 행만 있어서, 시·도와 시·군·구는 고를 수 있는 동의 코드 앞자리(2자리·5자리)로 묶어 읽는다(05 2-2 {@code
 * region}). 고를 수 없는 동({@code active = false})은 어느 단계에도 나오지 않는다.
 */
public interface RegionRepository extends JpaRepository<Region, String> {

  /** 코드표에 있고 지금 고를 수 있는 법정동인지(06 4-3 {@code POST /api/rooms}). */
  boolean existsByCodeAndActiveTrue(String code);

  /** 동 이름이 이 글자로 시작하는 고를 수 있는 동, 코드 순 20개. {@code %}·{@code _}는 글자 그대로 찾는다(Spring Data가 이스케이프). */
  List<Region> findTop20ByActiveTrueAndDongStartingWithOrderByCodeAsc(String dong);

  /** 고를 수 있는 동이 있는 시·도(코드 2자리), 코드 순. */
  @Query(
      """
      SELECT DISTINCT new com.chilsuitjo.back.room.dto.RegionOptionResponse(
          substring(r.code, 1, 2), r.sido)
      FROM Region r
      WHERE r.active = true
      ORDER BY 1
      """)
  List<RegionOptionResponse> findSidos();

  /** 그 시·도에서 고를 수 있는 동이 있는 시·군·구(코드 5자리), 코드 순. 세종은 이름이 null인 한 칸. */
  @Query(
      """
      SELECT DISTINCT new com.chilsuitjo.back.room.dto.RegionOptionResponse(
          substring(r.code, 1, 5), r.sigungu)
      FROM Region r
      WHERE r.active = true AND substring(r.code, 1, 2) = :sido
      ORDER BY 1
      """)
  List<RegionOptionResponse> findSigungus(@Param("sido") String sidoCode);

  /** 그 시·군·구에서 고를 수 있는 읍·면·동(코드 10자리), 코드 순. */
  @Query(
      """
      SELECT new com.chilsuitjo.back.room.dto.RegionOptionResponse(r.code, r.dong)
      FROM Region r
      WHERE r.active = true AND substring(r.code, 1, 5) = :sigungu
      ORDER BY r.code
      """)
  List<RegionOptionResponse> findDongs(@Param("sigungu") String sigunguCode);
}
