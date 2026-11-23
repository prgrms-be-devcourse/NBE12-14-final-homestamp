package com.chilsuitjo.back.room.repository;

import com.chilsuitjo.back.room.entity.RoomPart;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 호실 부위 저장. 다른 도메인이 부위를 읽는 창구는 {@code RoomQueryService}다. */
public interface RoomPartRepository extends JpaRepository<RoomPart, UUID> {}
