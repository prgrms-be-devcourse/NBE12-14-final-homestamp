package com.chilsuitjo.back.photo.dto;

import java.util.List;

/**
 * 대상별 사진 한도(decisions 2026-11-12). 올리는 사람이 볼 수 있는 사진만 센다(decisions 2026-11-18).
 *
 * @param max 수리 건 30, 점검 40, 정산 항목 10
 * @param countedTargets 한도에 같이 세는 대상
 */
public record PhotoQuota(int max, List<PhotoTarget> countedTargets) {

  /** 대상 한도가 없는 사진. 한 번에 10장 한도는 그대로 적용된다. */
  public static PhotoQuota none() {
    return new PhotoQuota(Integer.MAX_VALUE, List.of());
  }
}
