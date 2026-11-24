package com.chilsuitjo.back.payment.dto;

import java.time.LocalDate;
import java.util.List;

public record PaymentCreateRequest(
    PayerRequest payer,
    PayeeRequest payee,
    long amount,
    LocalDate paidOn,
    String evidenceKind,
    List<String> uploadTickets,
    boolean similarConfirmed) {}
