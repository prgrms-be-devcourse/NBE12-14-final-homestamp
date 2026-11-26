package com.chilsuitjo.back.correction.rule;

/** 고치기 결과(03 G). 기준은 "도장이 있나" 하나다. 칸 종류나 글의 뜻(오타인가)으로 나누지 않는다. */
public enum CorrectionResult {
  /** 같이 확인하기 전: 바로 바뀌었다. */
  APPLIED("고쳤어요"),
  /** 같이 확인한 뒤: 앞모습은 그대로이고 상대가 확인하면 바뀐다. */
  REQUESTED("고친 내용을 보냈어요. 상대가 확인하면 바뀌어요");

  private final String message;

  CorrectionResult(String message) {
    this.message = message;
  }

  /** 화면 문구(06 부록 A 말투). */
  public String message() {
    return message;
  }
}
