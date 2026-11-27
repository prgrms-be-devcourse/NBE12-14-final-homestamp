package com.chilsuitjo.back.correction.service;

import com.chilsuitjo.back.correction.dto.CorrectionView;
import com.chilsuitjo.back.correction.repository.CorrectionStateStore;
import com.chilsuitjo.back.correction.rule.CorrectionTargetKind;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 기록의 고친 내용 읽기(상세 화면용). 잠그지 않고 읽는다. 보이는 범위(공유 중단 뒤 고정)는 부르는 도메인이 거른다. */
@Service
@RequiredArgsConstructor
public class CorrectionReader {

  private final CorrectionStateStore store;

  /** 그 기록의 고친 내용 모두(만든 순서). */
  public List<CorrectionView> findByRecord(CorrectionTargetKind kind, UUID targetId) {
    return store.views(kind.targetKey(targetId));
  }
}
