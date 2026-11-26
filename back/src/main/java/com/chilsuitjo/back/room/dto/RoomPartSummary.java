package com.chilsuitjo.back.room.dto;

/**
 * 부위 이름과 숨김·켜짐 여부. 다른 도메인과 주고받는 값이다.
 *
 * @param name 부위 이름(예: 천장). 구역은 담지 않는다
 * @param hidden 숨긴 부위인지(03 "부위": 지우지 않고 숨기기만). 숨긴 부위에 새 기록을 받을지는 부르는 쪽이 이 값으로 판단한다
 * @param enabled 켜진 부위인지(설비·가전 켜고 끔 = 이 집에 있다/없다, 그 밖의 부위는 늘 켜짐). 꺼 둔 설비·가전은 새 기록을 받지 않는다(03 "부위",
 *     #182). 이미 있는 기록은 끄더라도 그대로다
 */
public record RoomPartSummary(String name, boolean hidden, boolean enabled) {}
