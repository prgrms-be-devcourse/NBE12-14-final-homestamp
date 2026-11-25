package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.room.dto.RegionOptionResponse;
import com.chilsuitjo.back.room.dto.RegionResponse;
import com.chilsuitjo.back.room.repository.RegionRepository;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 호실 주소를 고르는 법정동 찾기·단계 선택(06 ROOM-1, 4-3). 고를 수 없는 동은 빼고 돌려준다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegionService {

  /** 찾는 말의 최대 길이. 코드표의 동 이름 칸({@code varchar(30)})과 같다. */
  static final int MAX_QUERY_LENGTH = 30;

  private static final Pattern SIDO_CODE = Pattern.compile("[0-9]{2}");
  private static final Pattern SIGUNGU_CODE = Pattern.compile("[0-9]{5}");

  private final RegionRepository regions;

  /**
   * 동 이름이 {@code q}로 시작하는 법정동을 코드 순으로 20개까지 찾는다.
   *
   * @throws BusinessException {@code VALIDATION_FAILED}(앞뒤 공백을 뺀 뒤 비었거나 30자를 넘음, 제어 문자가 있음. NUL은
   *     DB가 받지 못해 서버 오류가 되므로 먼저 막는다)
   */
  public List<RegionResponse> search(String q) {
    String keyword = q.strip();
    if (keyword.isEmpty()
        || keyword.length() > MAX_QUERY_LENGTH
        || keyword.chars().anyMatch(Character::isISOControl)) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    return regions.findTop20ByActiveTrueAndDongStartingWithOrderByCodeAsc(keyword).stream()
        .map(RegionResponse::from)
        .toList();
  }

  /**
   * 단계 선택 목록. {@code parent}가 없으면 시·도, 2자리면 그 시·도의 시·군·구, 5자리면 그 시·군·구의 읍·면·동. 없는 코드면 빈 목록.
   *
   * @throws BusinessException {@code VALIDATION_FAILED}({@code parent}가 숫자 2자리·5자리가 아님)
   */
  public List<RegionOptionResponse> options(String parent) {
    if (parent == null) {
      return regions.findSidos();
    }
    if (SIDO_CODE.matcher(parent).matches()) {
      return regions.findSigungus(parent);
    }
    if (SIGUNGU_CODE.matcher(parent).matches()) {
      return regions.findDongs(parent);
    }
    throw new BusinessException(ErrorCode.VALIDATION_FAILED);
  }
}
