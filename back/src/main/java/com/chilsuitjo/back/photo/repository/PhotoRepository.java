package com.chilsuitjo.back.photo.repository;

import com.chilsuitjo.back.photo.entity.Photo;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 사진 카드({@code record.photo}). 덧붙이기만 한다. */
public interface PhotoRepository extends JpaRepository<Photo, UUID> {

  /** 이 정식 키로 이미 붙은 사진이 있나({@code UNIQUE(original_key)}, 같은 표로 두 번 확정하지 않음). */
  boolean existsByOriginalKey(String originalKey);
}
