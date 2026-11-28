package com.chilsuitjo.back.member.dto;

import com.chilsuitjo.back.member.rule.AcknowledgementKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 바뀐 문구 다시 확인({@code POST /api/me/agreements}, 06 4-1, CONS-4). 화면이 {@code GET /api/me}의 {@code
 * pendingAcknowledgements}를 보여 준 뒤 그대로 보낸다.
 *
 * @param acknowledgements 확인한 항목. 1~3개, 같은 종류는 한 번만
 */
public record AgreementsRequest(
    @NotEmpty @Size(max = 3) List<@NotNull @Valid Item> acknowledgements) {

  /**
   * @param textVersion 확인한 문구의 판(화면이 보여 준 판)
   */
  public record Item(
      @NotNull AcknowledgementKind kind, @NotNull @Size(max = 20) String textVersion) {}

  /** 종류별 판. 같은 종류가 두 번 오면 null(부르는 쪽이 {@code VALIDATION_FAILED}). */
  public Map<AcknowledgementKind, String> textVersions() {
    Map<AcknowledgementKind, String> versions = new EnumMap<>(AcknowledgementKind.class);
    for (Item item : acknowledgements) {
      if (versions.put(item.kind(), item.textVersion()) != null) {
        return null;
      }
    }
    return versions;
  }
}
