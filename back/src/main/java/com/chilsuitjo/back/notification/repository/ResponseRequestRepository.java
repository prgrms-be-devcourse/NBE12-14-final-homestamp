package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.notification.entity.ResponseRequest;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 응답 요청 저장소. 대상 + 종류 조회는 {@link ResponseRequestLookup}(V1 유니크별로 나눈 계약)에 있다. */
public interface ResponseRequestRepository
    extends JpaRepository<ResponseRequest, UUID>, ResponseRequestLookup {}
