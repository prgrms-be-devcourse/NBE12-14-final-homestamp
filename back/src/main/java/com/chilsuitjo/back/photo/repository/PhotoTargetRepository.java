package com.chilsuitjo.back.photo.repository;

import static com.chilsuitjo.back.photo.repository.PhotoTargetSql.anyOf;

import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.entity.Photo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** 대상에 붙은 사진 카드 읽기. 대상 칸이 여덟 개라 Spring Data 메서드 대신 대상마다 조건을 만들어 한 번에 읽는다. */
@Repository
public class PhotoTargetRepository {

  private final EntityManager entityManager;

  PhotoTargetRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /**
   * 이 호실 기록에서 대상 중 하나에 붙은 사진. 지운 사진과 보는 사람에게 안 보이는 사진도 모두 준다(거르는 것은 부르는 쪽).
   *
   * <p>순서는 정하지 않는다. Postgres의 uuid 비교(바이트 순)가 Java {@code UUID.compareTo}와 달라서 같은 {@code
   * sort_order} 안의 순서는 부르는 쪽이 Java로 정한다.
   */
  public List<Photo> findByTargets(UUID roomRecordId, Collection<PhotoTarget> targets) {
    if (targets.isEmpty()) {
      return List.of();
    }
    Map<String, Object> params = new LinkedHashMap<>();
    Query query =
        entityManager
            .createNativeQuery(
                "SELECT p.* FROM record.photo p WHERE p.room_record_id = :room AND ("
                    + anyOf(targets, params)
                    + ")",
                Photo.class)
            .setParameter("room", roomRecordId);
    params.forEach(query::setParameter);
    @SuppressWarnings("unchecked")
    List<Photo> rows = query.getResultList();
    return rows;
  }
}
