package com.chilsuitjo.back.photo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.dto.PhotoDigest;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.photo.dto.PhotoQuota;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * {@code PhotoService.attach}의 실제 구현(#112). 06 PHO-1a~c·1f, 04 5-1 "제출 처리", 05 불변식 14·17, 사진
 * 한도(decisions 2026-11-12·18·26).
 */
class JpaPhotoServiceAttachTest extends PhotoAttachTestSupport {

  // ---- A. 거절: 한 장도 붙지 않는다 ----

  @Nested
  class 거절 {

    @Test
    void 다른_키로_서명한_표는_UPLOAD_TICKET_INVALID() {
      var other = new PhotoUploadTicketSigner("another-photo-upload-ticket-signing-key");
      Upload forged =
          signed(
              other, room, tenant, TestJpegs.redrawn(), TestJpegs.redrawn(), NOW.plusSeconds(60));

      assertRejected(() -> attach(upload(), forged), ErrorCode.UPLOAD_TICKET_INVALID, 1);
      assertNothingAttached();
    }

    @Test
    void 남의_표는_UPLOAD_TICKET_INVALID() {
      Upload others =
          signed(
              signer,
              room,
              landlord,
              TestJpegs.redrawn(),
              TestJpegs.redrawn(),
              NOW.plusSeconds(60));

      assertRejected(() -> attach(others), ErrorCode.UPLOAD_TICKET_INVALID, 0);
      assertNothingAttached();
    }

    @Test
    void 다른_호실_기록의_표는_UPLOAD_TICKET_INVALID() {
      Upload otherRoom =
          signed(
              signer,
              java.util.UUID.randomUUID(),
              tenant,
              TestJpegs.redrawn(),
              TestJpegs.redrawn(),
              NOW.plusSeconds(60));

      assertRejected(() -> attach(otherRoom), ErrorCode.UPLOAD_TICKET_INVALID, 0);
      assertNothingAttached();
    }

    @Test
    void PHO_1c_만료된_표는_UPLOAD_TICKET_EXPIRED() {
      Upload expired =
          signed(
              signer, room, tenant, TestJpegs.redrawn(), TestJpegs.redrawn(), NOW.minusSeconds(1));

      assertRejected(() -> attach(upload(), expired), ErrorCode.UPLOAD_TICKET_EXPIRED, 1);
      assertNothingAttached();
    }

    @Test
    void PHO_1a_원본에_위치_정보가_있으면_두_파일을_지우고_PHOTO_CHECK_FAILED() {
      Upload gps = upload(TestJpegs.withGps(), TestJpegs.redrawn());

      assertRejected(() -> attach(gps), ErrorCode.PHOTO_CHECK_FAILED, 0);
      assertThat(store.exists(gps.pendingOriginal())).isFalse();
      assertThat(store.exists(gps.pendingThumb())).isFalse();
      assertNothingAttached();
    }

    @Test
    void PHO_1a_썸네일에_위치_정보가_있어도_PHOTO_CHECK_FAILED() {
      Upload gps = upload(TestJpegs.redrawn(), TestJpegs.withGps());

      assertRejected(() -> attach(gps), ErrorCode.PHOTO_CHECK_FAILED, 0);
      assertThat(store.exists(gps.pendingOriginal())).isFalse();
      assertThat(store.exists(gps.pendingThumb())).isFalse();
      assertNothingAttached();
    }

    @Test
    void JPEG가_아니면_PHOTO_CHECK_FAILED() {
      Upload png = upload(TestJpegs.notJpeg(), TestJpegs.redrawn());

      assertRejected(() -> attach(png), ErrorCode.PHOTO_CHECK_FAILED, 0);
      assertNothingAttached();
    }

    @Test
    void 저장된_체크섬이_표와_다르면_PHOTO_CHECK_FAILED() {
      Upload upload = upload();
      store.put(upload.pendingOriginal(), TestJpegs.withExifWithoutGps());

      assertRejected(() -> attach(upload), ErrorCode.PHOTO_CHECK_FAILED, 0);
      assertNothingAttached();
    }

    @Test
    void 아직_올라오지_않은_사진은_PHOTO_CHECK_FAILED() {
      Upload missing = notUploaded(TestJpegs.redrawn(), TestJpegs.redrawn());

      assertRejected(() -> attach(upload(), missing), ErrorCode.PHOTO_CHECK_FAILED, 1);
      assertNothingAttached();
    }

    @Test
    void 검사한_뒤_파일이_바뀌면_두_파일을_지우고_PHOTO_CHECK_FAILED() {
      Upload swapped = upload();
      // 검사(HEAD·READ)가 끝난 뒤 복사 직전에 같은 키로 다시 올린다: ETag가 바뀌어 S3가 412로 거절한다
      store.onCall((op, key) -> {
        if (op.equals("COPY") && key.equals(swapped.pendingOriginal())) {
          store.put(swapped.pendingOriginal(), TestJpegs.withGps());
        }
      });

      assertRejected(() -> attach(swapped), ErrorCode.PHOTO_CHECK_FAILED, 0);
      assertThat(store.exists(swapped.pendingOriginal())).isFalse();
      assertThat(store.exists(swapped.pendingThumb())).isFalse();
      assertThat(photoCount()).isZero();
    }

    @Test
    void 여러_장이_실패하면_목록에서_가장_앞의_사진_번호만_준다() {
      Upload expired =
          signed(
              signer, room, tenant, TestJpegs.redrawn(), TestJpegs.redrawn(), NOW.minusSeconds(1));

      assertRejected(
          () -> attach(upload(), expired, upload(TestJpegs.withGps(), TestJpegs.redrawn())),
          ErrorCode.UPLOAD_TICKET_EXPIRED,
          1);
      assertNothingAttached();
    }

    @Test
    void 앞_사진의_파일_검사_실패가_뒤_사진의_표_실패보다_먼저다() {
      // 표 검사(로컬)를 모든 사진에 먼저 해 버리면 2번이 나온다. 06 4-10은 "목록에서 가장 앞"이다
      var other = new PhotoUploadTicketSigner("another-photo-upload-ticket-signing-key");
      Upload forged =
          signed(
              other, room, tenant, TestJpegs.redrawn(), TestJpegs.redrawn(), NOW.plusSeconds(60));

      assertRejected(
          () -> attach(upload(TestJpegs.withGps(), TestJpegs.redrawn()), upload(), forged),
          ErrorCode.PHOTO_CHECK_FAILED,
          0);
      assertNothingAttached();
    }

    @Test
    void 뒤_사진이_검사에_실패하면_앞_사진도_복사하지_않는다() {
      assertRejected(
          () -> attach(upload(), upload(), upload(TestJpegs.withGps(), TestJpegs.redrawn())),
          ErrorCode.PHOTO_CHECK_FAILED,
          2);
      assertThat(store.count("COPY")).isZero();
      assertNothingAttached();
    }

    @Test
    void 한_번에_10장을_넘기거나_빈_목록은_부르는_쪽_버그다() {
      List<Upload> eleven = IntStream.range(0, 11).mapToObj(i -> upload()).toList();

      assertThatThrownBy(() -> attach(command(eleven)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> attach(command(List.of())))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(store.count("HEAD")).isZero();
    }

    @Test
    void 이미_붙은_표를_다시_쓰면_S3를_부르지_않고_UPLOAD_TICKET_INVALID() {
      Upload upload = upload();
      attach(upload);
      int callsAfterFirst = store.calls().size();

      assertRejected(() -> attach(upload), ErrorCode.UPLOAD_TICKET_INVALID, 0);
      assertThat(store.calls()).hasSize(callsAfterFirst);
      assertThat(photoCount()).isOne();
    }

    @Test
    void 한_요청에_같은_표가_두_번이면_뒤의_것이_UPLOAD_TICKET_INVALID() {
      // 그대로 두면 커밋 때 UNIQUE(original_key) 위반이 나고, pipeline은 이것을 같은 키의 동시 요청으로 보고 다시 읽기를 시도한다
      Upload upload = upload();

      assertRejected(() -> attach(upload, upload), ErrorCode.UPLOAD_TICKET_INVALID, 1);
      assertNothingAttached();
    }

    private void assertNothingAttached() {
      assertThat(photoCount()).isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM state.photo_visibility WHERE room_record_id = ?",
                  Integer.class,
                  room))
          .isZero();
    }
  }

  // ---- A9. 대상 한도(PHOTO_LIMIT, decisions 2026-11-26) ----

  @Nested
  class 대상_한도 {

    @Test
    void 한도를_넘으면_S3를_부르지_않고_PHOTO_LIMIT() {
      existingPhotos(29);

      assertRejected(() -> attach(upload(), upload()), ErrorCode.PHOTO_LIMIT, null);
      assertThat(store.calls()).isEmpty();
      assertThat(photoCount()).isEqualTo(29);
    }

    @Test
    void 한도까지는_붙는다() {
      existingPhotos(28);

      assertThat(attach(upload(), upload())).hasSize(2);
      assertThat(photoCount()).isEqualTo(REPAIR_LIMIT);
    }

    @Test
    void 올리는_사람이_볼_수_없는_사진은_세지_않는다() {
      // 공유를 그만둔 뒤 상대가 붙인 사진은 나에게 보이지 않는다. 세면 숨긴 사진이 한도 오류로 드러난다(03 3-1, decisions 2026-11-18)
      Instant stopped = NOW.minus(Duration.ofDays(1));
      existingPhotos(29);
      for (int i = 0; i < 5; i++) {
        existingPhoto(caseTarget(), PartySide.LANDLORD, stopped.plusSeconds(60), false);
      }

      assertThat(attach(command(access(stopped), caseQuota(), List.of(upload())))).hasSize(1);
    }

    @Test
    void 지운_사진은_세지_않는다() {
      existingPhotos(29);
      existingPhoto(caseTarget(), PartySide.TENANT, NOW.minusSeconds(60), true);

      assertThat(attach(upload())).hasSize(1);
    }

    @Test
    void 함께_세는_대상의_사진도_센다() {
      var note = new PhotoTarget.Ref(PhotoTargetType.PROGRESS_NOTE, progressNote());
      existingPhotos(20);
      for (int i = 0; i < 10; i++) {
        existingPhoto(note, PartySide.LANDLORD, NOW.minusSeconds(60), false);
      }
      var quota = new PhotoQuota(REPAIR_LIMIT, List.of(caseTarget(), note));

      assertRejected(
          () -> attach(command(tenantAccess(), quota, List.of(upload()))),
          ErrorCode.PHOTO_LIMIT,
          null);
    }

    @Test
    void 같은_대상에_동시에_붙여도_한도를_넘지_않는다() throws Exception {
      // pipeline이 호실을 잠가 차례로 센다(06 0-1 "제출 때 잠그고 셈")
      existingPhotos(15);
      List<Upload> first = IntStream.range(0, 10).mapToObj(i -> upload()).toList();
      List<Upload> second = IntStream.range(0, 10).mapToObj(i -> upload()).toList();

      List<CompletableFuture<Object>> both =
          List.of(first, second).stream()
              .map(uploads ->
                  CompletableFuture.supplyAsync(() -> {
                    try {
                      return (Object) attach(command(uploads));
                    } catch (BusinessException e) {
                      return e.errorCode();
                    }
                  }))
              .toList();
      List<Object> results = new ArrayList<>();
      for (var future : both) {
        results.add(future.get(30, TimeUnit.SECONDS));
      }

      assertThat(results).containsOnlyOnce(ErrorCode.PHOTO_LIMIT);
      assertThat(photoCount()).isEqualTo(25);
    }
  }

  // ---- B. 성공 ----

  @Nested
  class 성공 {

    @Test
    void 정식_키와_지문으로_사진_카드를_만든다() {
      Upload first = upload();
      Upload second = upload(TestJpegs.withExifWithoutGps(), TestJpegs.redrawn());

      List<PhotoDigest> digests = attach(first, second);

      assertThat(digests)
          .extracting(PhotoDigest::originalSha256, PhotoDigest::sortOrder)
          .containsExactly(
              org.assertj.core.groups.Tuple.tuple(first.originalSha256Hex(), 0),
              org.assertj.core.groups.Tuple.tuple(second.originalSha256Hex(), 1));
      List<Map<String, Object>> rows = photoRows();
      assertThat(rows).hasSize(2);
      Map<String, Object> row = rows.getFirst();
      assertThat(row.get("id")).isEqualTo(digests.getFirst().photoId());
      assertThat(row.get("repair_case_id")).isEqualTo(repairCase);
      assertThat(row.get("original_key")).isEqualTo(first.photosOriginal());
      assertThat(row.get("thumb_key")).isEqualTo(first.photosThumb());
      assertThat(row.get("original_sha256")).isEqualTo(first.originalSha256Hex());
      assertThat((String) row.get("thumb_sha256")).matches("[0-9a-f]{64}");
      assertThat(row.get("size_bytes")).isEqualTo((long) TestJpegs.redrawn().length);
      assertThat(row.get("stage")).isEqualTo("BEFORE");
      assertThat(row.get("uploaded_by_side")).isEqualTo("TENANT");
      // 06 PHO-1f: 기록 시각은 서버 시각이고 기기 시각은 참고값으로만 남는다
      assertThat(instant(row.get("created_at"))).isEqualTo(NOW);
      assertThat(instant(row.get("device_taken_at"))).isEqualTo(DEVICE_TIME);
      assertThat(store.exists(first.photosOriginal())).isTrue();
      assertThat(store.exists(first.photosThumb())).isTrue();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM state.photo_visibility WHERE room_record_id = ?"
                      + " AND NOT hidden_on_link AND erased_at IS NULL",
                  Integer.class,
                  room))
          .isEqualTo(2);
    }

    @Test
    void 같은_대상의_기존_사진_뒤로_순서를_잇는다() {
      existingPhotos(5);

      assertThat(attach(upload(), upload()))
          .extracting(PhotoDigest::sortOrder)
          .containsExactly(5, 6);
    }

    @Test
    void 커밋하면_pending_파일과_확인_줄을_지운다() {
      Upload upload = upload();

      attach(upload);

      assertThat(store.exists(upload.pendingOriginal())).isFalse();
      assertThat(store.exists(upload.pendingThumb())).isFalse();
      assertThat(checkRow(upload)).isNull();
    }

    @Test
    void 바깥_트랜잭션이_롤백되면_pending_파일을_지우지_않는다() {
      Upload upload = upload();

      assertThatThrownBy(() ->
              transaction.executeWithoutResult(status -> {
                lockRoom();
                photos.attach(command(List.of(upload)));
                throw new IllegalStateException("행동 기록 저장 실패");
              }))
          .hasMessage("행동 기록 저장 실패");

      assertThat(store.exists(upload.pendingOriginal())).isTrue();
      assertThat(store.exists(upload.pendingThumb())).isTrue();
      assertThat(photoCount()).isZero();
    }

    @Test
    void 부르는_쪽_트랜잭션_없이는_부를_수_없다() {
      assertThatThrownBy(() -> photos.attach(command(List.of(upload()))))
          .isInstanceOf(IllegalTransactionStateException.class);
    }
  }

  // ---- C. 정합성(05 불변식 17: 카드 없는 정식 파일은 반드시 지워지고, 카드 있는 파일은 지우지 않음) ----

  @Nested
  class 고아_파일_확인_줄 {

    @Test
    void 확인_줄은_복사보다_먼저_커밋되어_바깥이_롤백돼도_남는다() {
      Upload copied = upload();
      Upload failing = upload();
      store.onCall((op, key) -> {
        if (op.equals("COPY") && key.equals(failing.pendingOriginal())) {
          throw new IllegalStateException("S3 연결 끊김");
        }
      });

      assertThatThrownBy(() -> attach(copied, failing)).hasMessageContaining("S3 연결 끊김");

      // 앞 사진은 이미 photos/에 복사됐는데 카드는 없다: 확인 줄이 남아 있어야 1시간 뒤 지워진다
      assertThat(store.exists(copied.photosOriginal())).isTrue();
      assertThat(photoCount()).isZero();
      for (Upload upload : List.of(copied, failing)) {
        Map<String, Object> row = checkRow(upload);
        assertThat(row).isNotNull();
        assertThat(row.get("kind")).isEqualTo("ORPHAN_CHECK");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(instant(row.get("next_attempt_at"))).isEqualTo(NOW.plus(ORPHAN_CHECK_AFTER));
        assertThat((String) row.get("payload"))
            .contains(upload.photosOriginal(), upload.photosThumb());
      }
    }

    @Test
    void 복사_뒤_카드_저장이_실패해도_정식_파일을_바로_지우지_않는다() {
      Upload upload = upload();

      assertThatThrownBy(() ->
              transaction.executeWithoutResult(status -> {
                lockRoom();
                photos.attach(command(List.of(upload)));
                throw new IllegalStateException("서버가 멈췄다");
              }))
          .hasMessage("서버가 멈췄다");

      // 같은 키로 겹친 재시도가 이미 저장한 사진을 지우지 않게, 지우는 일은 확인 작업이 한다(04 5-1)
      assertThat(store.exists(upload.photosOriginal())).isTrue();
      assertThat(store.exists(upload.photosThumb())).isTrue();
      assertThat(checkRow(upload)).isNotNull();
    }

    @Test
    void 확인_작업이_줄을_잡고_있으면_그_사진은_복사하지_않고_기다린다() throws Exception {
      Upload upload = upload();
      failCopyOnce(upload);
      assertThatThrownBy(() -> attach(upload)).isInstanceOf(IllegalStateException.class);
      assertThat(checkRow(upload)).isNotNull();

      // 확인 작업: 줄을 FOR UPDATE로 잡고 카드가 정말 없는지 보는 중
      CountDownLatch locked = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      var checker =
          CompletableFuture.runAsync(() ->
              transaction.executeWithoutResult(status -> {
                jdbc.queryForObject(
                    "SELECT id FROM state.outbox WHERE dedupe_key = ? FOR UPDATE",
                    java.util.UUID.class,
                    upload.orphanCheckKey());
                locked.countDown();
                await(release);
              }));
      assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
      long copiesBefore = store.count("COPY");

      var retry = CompletableFuture.supplyAsync(() -> attach(upload));
      try {
        assertThatThrownBy(() -> retry.get(1, TimeUnit.SECONDS))
            .isInstanceOf(TimeoutException.class);
        // 확인 작업이 곧 photos/ 파일을 지울 수 있다: 그동안 다시 복사하면 정식 사진이 지워진다
        assertThat(store.count("COPY")).isEqualTo(copiesBefore);
      } finally {
        release.countDown();
      }

      checker.get(5, TimeUnit.SECONDS);
      assertThat(retry.get(5, TimeUnit.SECONDS)).hasSize(1);
      assertThat(store.exists(upload.photosOriginal())).isTrue();
    }

    @Test
    void 다시_붙이면_남아_있던_확인_줄의_시각을_새로_정한다() {
      Upload upload = upload();
      jdbc.update(
          "INSERT INTO state.outbox (id, kind, dedupe_key, payload, status, attempts,"
              + " next_attempt_at, created_at, version)"
              + " VALUES (?, 'ORPHAN_CHECK', ?, '{}'::jsonb, 'PENDING', 0, ?, ?, 0)",
          java.util.UUID.randomUUID(),
          upload.orphanCheckKey(),
          java.sql.Timestamp.from(NOW.minus(Duration.ofMinutes(10))),
          java.sql.Timestamp.from(NOW.minus(Duration.ofHours(2))));
      failCopyOnce(upload);

      assertThatThrownBy(() -> attach(upload)).isInstanceOf(IllegalStateException.class);

      // 시각을 그대로 두면 10분 전에 이미 돌았어야 할 확인 작업이 방금 복사한 파일을 지운다
      assertThat(instant(checkRow(upload).get("next_attempt_at")))
          .isEqualTo(NOW.plus(ORPHAN_CHECK_AFTER));
    }

    private void failCopyOnce(Upload upload) {
      List<Boolean> failed = Collections.synchronizedList(new ArrayList<>());
      store.onCall((op, key) -> {
        if (op.equals("COPY") && key.equals(upload.pendingOriginal()) && failed.isEmpty()) {
          failed.add(true);
          throw new IllegalStateException("S3 연결 끊김");
        }
      });
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
