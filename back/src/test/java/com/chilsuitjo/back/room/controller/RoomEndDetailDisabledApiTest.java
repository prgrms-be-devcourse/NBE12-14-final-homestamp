package com.chilsuitjo.back.room.controller;

import static com.chilsuitjo.back.room.controller.RoomEndDetailApiTest.detail;
import static com.chilsuitjo.back.room.controller.RoomEndDetailApiTest.member;
import static com.chilsuitjo.back.room.controller.RoomEndDetailApiTest.moveOut;
import static com.chilsuitjo.back.room.controller.RoomEndDetailApiTest.region;
import static com.chilsuitjo.back.room.controller.RoomEndDetailApiTest.room;
import static com.chilsuitjo.back.room.controller.RoomEndDetailApiTest.seat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 기본 설정({@code app.room.end.actions-enabled} 꺼짐)에서는 호실 상세에 거주 종료 행동을 넣지 않는다(#421 D 리뷰, #445). 거주 종료
 * 요청 문자·기다림 시작·미확인 뒤 적용·양쪽 안내가 연결되기 전이라서다. 요청과 거주 종료 칸은 그대로 보인다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "test.notification.wait-service=jpa")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomEndDetailDisabledApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void 설정이_꺼져_있으면_거주_종료_버튼은_없고_요청은_보인다() throws Exception {
    region(jdbc);
    UUID tenant = member(jdbc);
    UUID landlord = member(jdbc);
    UUID room = room(jdbc, "LIVING");
    seat(jdbc, room, "TENANT", tenant, "CONNECTED");
    seat(jdbc, room, "LANDLORD", landlord, "CONNECTED");
    detail(mvc, room, landlord).andExpect(jsonPath("$.availableActions", not(hasItem("move-out"))));

    // API 자체는 그대로라 요청은 남는다. 화면에서 누를 길만 막는다
    moveOut(mvc, room, landlord).andExpect(status().isOk());

    detail(mvc, room, tenant)
        .andExpect(jsonPath("$.availableActions", not(hasItem("confirm"))))
        .andExpect(jsonPath("$.availableActions", not(hasItem("reject"))))
        .andExpect(jsonPath("$.availableActions", hasItem("repair-cases")))
        .andExpect(jsonPath("$.endRequest.status").value("WAITING"));
  }
}
