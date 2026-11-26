package com.chilsuitjo.back.room.rule;

import java.util.List;

/**
 * 부위 구역. 기본 부위 목록(V7 {@code part_template.zone})의 6구역과 글자까지 같다(06 ROOM-3, #178). 직접 더하는 부위도 이 중 하나에
 * 넣는다. 목록 순서가 화면의 구역 순서다.
 */
public final class PartZone {

  /** V7 시드의 구역, 보이는 순서대로. 시드와 같은지는 테스트가 본다. */
  public static final List<String> ALL = List.of("현관", "방", "주방", "욕실", "설비·가전", "기타");

  private PartZone() {}

  public static boolean isKnown(String zone) {
    return zone != null && ALL.contains(zone);
  }

  /** 구역의 순서(0부터). 모르는 구역은 맨 뒤다(V7 전에 직접 넣은 행 등). */
  public static int rank(String zone) {
    int index = ALL.indexOf(zone);
    return index < 0 ? ALL.size() : index;
  }
}
