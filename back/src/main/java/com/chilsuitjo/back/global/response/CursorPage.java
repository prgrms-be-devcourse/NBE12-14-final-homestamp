package com.chilsuitjo.back.global.response;

import java.util.List;

/**
 * 커서 방식 목록 응답(06 4-0).
 *
 * @param items 이번 쪽의 항목
 * @param nextCursor 다음 쪽 커서. 더 없으면 null
 */
public record CursorPage<T>(List<T> items, String nextCursor) {}
