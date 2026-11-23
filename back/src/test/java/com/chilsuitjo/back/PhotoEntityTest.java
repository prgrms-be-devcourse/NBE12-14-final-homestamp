package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.photo.entity.Photo;
import com.chilsuitjo.back.photo.entity.PhotoVisibility;
import com.chilsuitjo.back.photo.rule.PhotoStage;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;

/**
 * 사진 카드({@code record.photo})와 공개 상태({@code state.photo_visibility})를 JPA로 실제 DB에 저장하고 다시 읽는다(PR
 * #107 리뷰). 공개 상태는 {@code @GeneratedValue} 없이 사진 ID를 그대로 쓰므로, {@code @Version}이 비어 있어 merge가 아니라 새
 * 행으로 들어가는지도 본다(04 기본 키).
 */
class PhotoEntityTest extends SchemaTestSupport {

  private static final Instant NOW = Instant.parse("2026-11-23T00:00:00Z");
  private static final String ORIGINAL_SHA = "a".repeat(64);
  private static final String THUMB_SHA = "b".repeat(64);

  @PersistenceContext private EntityManager entityManager;

  @Test
  void 사진과_공개_상태를_한_트랜잭션에_저장하고_다시_읽는다() {
    UUID room = room();
    UUID repairCase = repair(room);
    String originalKey = "photos/" + room + "/original.jpg";
    Photo photo =
        Photo.forTarget(
            room,
            PhotoTargetType.REPAIR_CASE,
            repairCase,
            null,
            PhotoStage.BEFORE,
            PartySide.TENANT,
            null,
            originalKey,
            "photos/" + room + "/thumb.jpg",
            ORIGINAL_SHA,
            THUMB_SHA,
            1024,
            0,
            NOW);

    new SimpleJpaRepository<>(Photo.class, entityManager).save(photo);
    PhotoVisibility visibility = new PhotoVisibility(photo.getId(), room, NOW);
    // save가 merge로 가면 복사본을 돌려준다. 같은 객체면 persist(INSERT 한 번)로 갔다.
    PhotoVisibility savedVisibility =
        new SimpleJpaRepository<>(PhotoVisibility.class, entityManager).save(visibility);
    entityManager.flush();
    entityManager.clear();

    assertThat(savedVisibility).isSameAs(visibility);

    Photo foundPhoto = entityManager.find(Photo.class, photo.getId());
    assertThat(foundPhoto.getRoomRecordId()).isEqualTo(room);
    assertThat(foundPhoto.getRepairCaseId()).isEqualTo(repairCase);
    assertThat(foundPhoto.getInspectionId()).isNull();
    assertThat(foundPhoto.getRoomPartId()).isNull();
    assertThat(foundPhoto.getStage()).isEqualTo(PhotoStage.BEFORE);
    assertThat(foundPhoto.getUploadedBySide()).isEqualTo(PartySide.TENANT);
    assertThat(foundPhoto.getOriginalKey()).isEqualTo(originalKey);
    assertThat(foundPhoto.getOriginalSha256()).isEqualTo(ORIGINAL_SHA);
    assertThat(foundPhoto.getThumbSha256()).isEqualTo(THUMB_SHA);
    assertThat(foundPhoto.getSizeBytes()).isEqualTo(1024);
    assertThat(foundPhoto.getSortOrder()).isZero();
    assertThat(foundPhoto.getCreatedAt()).isEqualTo(NOW);

    PhotoVisibility foundVisibility = entityManager.find(PhotoVisibility.class, photo.getId());
    assertThat(foundVisibility.getRoomRecordId()).isEqualTo(room);
    assertThat(foundVisibility.isHiddenOnLink()).isFalse();
    assertThat(foundVisibility.getMarkedWrongAt()).isNull();
    assertThat(foundVisibility.getErasedAt()).isNull();
    assertThat(foundVisibility.getEraseReason()).isNull();
    assertThat(foundVisibility.getCreatedAt()).isEqualTo(NOW);
    assertThat(foundVisibility.getVersion()).isZero();
  }
}
