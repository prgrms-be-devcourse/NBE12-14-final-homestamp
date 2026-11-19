package com.chilsuitjo.back.repair.rule;

/** 원인 설명의 근거 종류(06 REP-8). 값은 V1 {@code cause_note.basis} CHECK와 같다. */
public enum CauseBasis {
  GUESS,
  SEEN,
  EXPERT,
  HEARD
}
