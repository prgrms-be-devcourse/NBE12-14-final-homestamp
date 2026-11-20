package com.chilsuitjo.back.auth.service;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 휴대폰 인증 설정값(06 0-1). 이름은 06 표와 같다. */
@Component
record VerificationSettings(
    @Value("${verify.code.ttl}") Duration codeTtl,
    @Value("${verify.attempts.max}") int attemptsMax,
    @Value("${verify.daily.phone}") int dailyPhone,
    @Value("${verify.ticket.ttl}") Duration ticketTtl,
    @Value("${verify.resend.interval}") Duration resendInterval,
    @Value("${sms.daily.total}") int smsDailyTotal,
    @Value("${app.zone}") ZoneId zone) {}
