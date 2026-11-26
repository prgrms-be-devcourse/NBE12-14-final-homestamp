package com.chilsuitjo.back.payment.dto;

import com.chilsuitjo.back.payment.rule.PayerKind;

public record PayerRequest(PayerKind kind, String label) {}
