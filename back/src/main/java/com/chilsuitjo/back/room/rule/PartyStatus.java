package com.chilsuitjo.back.room.rule;

/** 당사자 자리 상태. 값은 05 ERD {@code party.status}와 같다. */
public enum PartyStatus {
  EMPTY,
  NUMBER_ONLY,
  INVITED,
  CONNECTED,
  SHARING_STOPPED,
  REJECTED,
  WITHDRAWN
}
