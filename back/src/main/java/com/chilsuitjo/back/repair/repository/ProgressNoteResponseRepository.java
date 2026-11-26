package com.chilsuitjo.back.repair.repository;

import com.chilsuitjo.back.repair.entity.ProgressNoteResponse;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 상황 변화 응답({@code state.progress_note_response}, C). 진행 상황 하나에 지금 답 하나. repair 안에서만 쓴다. */
public interface ProgressNoteResponseRepository extends JpaRepository<ProgressNoteResponse, UUID> {

  Optional<ProgressNoteResponse> findByProgressNoteId(UUID progressNoteId);
}
