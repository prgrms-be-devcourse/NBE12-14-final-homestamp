package com.chilsuitjo.back.correction.dto;

import com.chilsuitjo.back.action.dto.DigestInput;
import java.util.UUID;

/** 새로 만든 판. {@code digest}는 그 판의 지문 재료라 행동 기록({@code content_digest})에 그대로 넘긴다. */
public record AppliedVersion(UUID versionId, int no, DigestInput digest) {}
