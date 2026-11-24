package com.chilsuitjo.back.action.repository;

import com.chilsuitjo.back.action.entity.Supplement;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 보충 기록({@code record.supplement}). 새 행을 더하기만 한다. action 안에서만 쓰고, 다른 도메인은 {@code SupplementWriter}를
 * 쓴다.
 */
public interface SupplementRepository extends JpaRepository<Supplement, UUID> {}
