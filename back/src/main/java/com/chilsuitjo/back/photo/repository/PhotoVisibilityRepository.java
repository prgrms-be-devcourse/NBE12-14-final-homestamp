package com.chilsuitjo.back.photo.repository;

import com.chilsuitjo.back.photo.entity.PhotoVisibility;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 사진 공개 상태({@code state.photo_visibility}). 사진 하나에 한 행. */
public interface PhotoVisibilityRepository extends JpaRepository<PhotoVisibility, UUID> {}
