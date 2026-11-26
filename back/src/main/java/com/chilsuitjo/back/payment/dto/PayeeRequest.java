package com.chilsuitjo.back.payment.dto;

import com.chilsuitjo.back.payment.rule.PayeeKind;

public record PayeeRequest(PayeeKind kind, String label) {}
