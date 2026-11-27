package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.notification.entity.SmsOptOut;
import org.springframework.data.repository.Repository;

/** 수신 거부 목록 조회. 지금은 있는지만 본다(쓰기는 #163 답 뒤). */
public interface SmsOptOutRepository extends Repository<SmsOptOut, String> {

  boolean existsById(String phoneHmac);
}
