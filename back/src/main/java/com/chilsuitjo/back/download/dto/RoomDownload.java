package com.chilsuitjo.back.download.dto;

import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.room.rule.RoomStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 내려받기 목록({@code GET /api/rooms/{id}/download}, 06 DL-1·S-19). 화면은 {@code record}를 그대로 {@code
 * 기록.json}으로 저장하고, {@code photoBatches}의 묶음마다 원본 주소를 받아 사진을 {@code 사진/<photoId>.jpg}로 ZIP에 넣는다(03
 * 6-3 "가져가기").
 *
 * @param record {@code 기록.json} 내용
 * @param photoBatches 받을 사진 ID를 50개씩 묶은 것. 지운 사진은 파일이 없어 빠진다. 주소가 끝나면 그 묶음만 다시 받는다(DL-1b)
 * @param photoCount 받을 사진 수({@code photoBatches}를 합친 수). 받기 전 "사진 ○장"에 쓴다
 */
public record RoomDownload(RecordFile record, List<List<UUID>> photoBatches, int photoCount) {

  /**
   * {@code 기록.json}. 행동 번호·이전 해시·해시·내용 지문과 삭제 기록은 넣지 않는다(03 3-1, 불변식 18). 회원 식별값과 멱등 키 같은 서버 내부 값도
   * 넣지 않는다.
   *
   * @param format 파일 형식 이름과 판
   * @param exportedAt 만든 시각
   * @param notice 화면 문구와 같은 안내(03 6-3 "서비스에 남긴 기록의 사본")
   * @param mySide 내려받은 사람의 측
   * @param roomStatus 내려받을 때의 호실 상태
   * @param actions 내가 볼 수 있는 행동 전체. 시각 순, 같은 시각이면 남긴 순
   * @param photos 그 행동들에 붙은 사진. 행동 순, 같은 행동 안에서는 표시 순서
   */
  public record RecordFile(
      String format,
      Instant exportedAt,
      String notice,
      PartySide mySide,
      RoomStatus roomStatus,
      List<RecordAction> actions,
      List<RecordPhoto> photos) {}

  /**
   * 행동 한 줄.
   *
   * @param id 행동 ID(UUID라 건수가 드러나지 않는다)
   * @param at 서버 시각
   * @param type 행동 종류(05 2-3)
   * @param side 남긴 측. 서버·운영 행동이면 null
   * @param target 대상. 호실 기록 자체가 대상이면 null
   * @param reason 사유
   * @param payload 행동 내용. 사진 지문은 {@code photos}로 옮겨 여기서 뺀다
   * @param photos 이 행동으로 붙인 사진
   */
  public record RecordAction(
      UUID id,
      Instant at,
      ActionType type,
      PartySide side,
      Target target,
      String reason,
      Map<String, Object> payload,
      List<ActionPhoto> photos) {}

  /** 행동·사진의 대상. */
  public record Target(TargetKind kind, UUID id) {}

  /**
   * 행동에 붙은 사진 한 장.
   *
   * @param sha256 올릴 때 저장한 원본 지문값(64자 소문자 hex, {@code shasum -a 256} 결과와 같음, 04 "지문값")
   * @param file ZIP 안의 파일 경로. 지운 사진이면 null
   */
  public record ActionPhoto(UUID photoId, String sha256, String file) {}

  /**
   * 사진 한 장.
   *
   * @param file ZIP 안의 파일 경로. 지운 사진이면 null(파일 대신 지운 사실과 지문값만 남는다, 03 6-1)
   * @param sha256 원본 지문값
   * @param uploadedBy 올린 측
   * @param target 사진을 붙인 행동의 대상
   * @param createdAt 사진을 붙인 행동의 시각
   * @param erased 지운 사진이면 흔적, 아니면 null(불변식 16)
   */
  public record RecordPhoto(
      UUID photoId,
      String file,
      String sha256,
      PartySide uploadedBy,
      Target target,
      Instant createdAt,
      Erased erased) {}

  /**
   * 지운 사진의 흔적(불변식 16).
   *
   * @param side 지운 측. 다른 사람의 요청으로 운영이 지웠으면 null
   * @param at 지운 시각
   */
  public record Erased(PartySide side, Instant at) {}
}
