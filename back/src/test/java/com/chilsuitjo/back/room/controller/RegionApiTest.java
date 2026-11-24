package com.chilsuitjo.back.room.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 06 4-3 {@code GET /api/regions/search}·{@code GET /api/regions}(ROOM-1). 코드표는 V2 시드(법정동코드 전체자료)를
 * 그대로 쓰고, 고를 수 없는 동만 테스트가 더한다. 다른 테스트도 같은 DB에 법정동을 넣으므로 목록 전체가 아니라 필요한 줄만 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegionApiTest {

  /** 종로구 안의 고를 수 없는 동. 찾기("청운")와 종로구 읍·면·동 목록 모두에서 빠져야 한다. */
  private static final String INACTIVE_DONG = "1111099900";

  /** 고를 수 있는 동이 하나도 없는 시·도의 동. 시·도 목록에서 그 시·도(98)가 빠져야 한다. */
  private static final String INACTIVE_SIDO_DONG = "9800000100";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void inactiveRegions() {
    jdbc.update(
        """
        INSERT INTO state.region (code, sido, sigungu, dong, active)
        VALUES (?, '서울특별시', '종로구', '청운없어진동', false),
               (?, '없어진도', '없어진구', '없어진동', false)
        ON CONFLICT (code) DO NOTHING
        """,
        INACTIVE_DONG,
        INACTIVE_SIDO_DONG);
  }

  @Test
  void 로그인하지_않으면_두_API_모두_401이다() throws Exception {
    mvc.perform(get("/api/regions/search").param("q", "청운"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    mvc.perform(get("/api/regions"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  void 동_이름_앞부분으로_찾으면_시도_시군구_동을_함께_준다() throws Exception {
    // "청운"은 서울 종로구 청운동과 경기 양평군 청운면을 함께 찾는다(코드 순)
    member(get("/api/regions/search").param("q", "청운"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].code", contains("1111010100", "4183037000")))
        .andExpect(jsonPath("$[0].sido").value("서울특별시"))
        .andExpect(jsonPath("$[0].sigungu").value("종로구"))
        .andExpect(jsonPath("$[0].dong").value("청운동"))
        .andExpect(jsonPath("$[1].dong").value("청운면"));
  }

  @Test
  void 찾는_말은_앞뒤_공백을_빼고_앞부분만_맞춘다() throws Exception {
    member(get("/api/regions/search").param("q", "  청운동 "))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].code", contains("1111010100")));
    // 가운데 글자로는 찾지 않는다: "운동"으로 찾은 동은 모두 "운동"으로 시작한다(청운동은 없다)
    member(get("/api/regions/search").param("q", "운동"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].dong", everyItem(startsWith("운동"))))
        .andExpect(jsonPath("$[*].code", not(hasItem("1111010100"))));
  }

  @Test
  void 찾은_동은_코드_순으로_20개까지만_준다() throws Exception {
    String body =
        member(get("/api/regions/search").param("q", "신"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(20)))
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> codes = JsonPath.read(body, "$[*].code");
    assertThat(codes).isSorted();
  }

  @Test
  void 세종은_시군구가_null로_찾힌다() throws Exception {
    member(get("/api/regions/search").param("q", "반곡"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.code == '3611010100')].sido").value(hasItem("세종특별자치시")))
        .andExpect(jsonPath("$[?(@.code == '3611010100')].sigungu").value(hasItem(nullValue())));
  }

  @Test
  void 와일드카드_글자는_글자_그대로_찾는다() throws Exception {
    for (String q : List.of("%", "_", "%동")) {
      member(get("/api/regions/search").param("q", q))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$", hasSize(0)));
    }
  }

  @Test
  void 고를_수_없는_동은_찾기와_단계_선택에서_빠진다() throws Exception {
    member(get("/api/regions/search").param("q", "청운"))
        .andExpect(jsonPath("$[*].code", not(hasItem(INACTIVE_DONG))));
    member(get("/api/regions").param("parent", "11110"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].code", hasItem("1111010100")))
        .andExpect(jsonPath("$[*].code", not(hasItem(INACTIVE_DONG))));
    member(get("/api/regions")).andExpect(jsonPath("$[*].code", not(hasItem("98"))));
    member(get("/api/regions").param("parent", "98"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(0)));
  }

  @Test
  void 찾는_말이_없거나_비었거나_30자를_넘거나_제어_문자가_있으면_VALIDATION_FAILED다() throws Exception {
    // NUL 같은 제어 문자는 DB가 받지 못하므로 서버 오류가 되기 전에 막는다
    for (String q : List.of("", "   ", "가".repeat(31), "청운\u0000", "\t청\n운")) {
      member(get("/api/regions/search").param("q", q))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
    }
    member(get("/api/regions/search"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
  }

  @Test
  void 찾는_말은_30자까지_받는다() throws Exception {
    member(get("/api/regions/search").param("q", "가".repeat(30)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(0)));
  }

  @Test
  void parent가_없으면_시도를_코드_순으로_준다() throws Exception {
    String body =
        member(get("/api/regions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].code").value("11"))
            .andExpect(jsonPath("$[0].name").value("서울특별시"))
            .andExpect(jsonPath("$[?(@.code == '36')].name").value(hasItem("세종특별자치시")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> codes = JsonPath.read(body, "$[*].code");
    assertThat(codes).isSorted().doesNotHaveDuplicates().allMatch(c -> c.length() == 2);
  }

  @Test
  void parent가_2자리면_그_시도의_시군구를_코드_순으로_준다() throws Exception {
    String body =
        member(get("/api/regions").param("parent", "11"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].code").value("11110"))
            .andExpect(jsonPath("$[0].name").value("종로구"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> codes = JsonPath.read(body, "$[*].code");
    assertThat(codes)
        .hasSize(25)
        .isSorted()
        .doesNotHaveDuplicates()
        .allMatch(c -> c.startsWith("11") && c.length() == 5);
  }

  @Test
  void parent가_5자리면_그_시군구의_읍면동을_코드_순으로_준다() throws Exception {
    String body =
        member(get("/api/regions").param("parent", "11110"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].code").value("1111010100"))
            .andExpect(jsonPath("$[0].name").value("청운동"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> codes = JsonPath.read(body, "$[*].code");
    assertThat(codes).isSorted().allMatch(c -> c.startsWith("11110") && c.length() == 10);
  }

  @Test
  void 세종은_이름이_null인_시군구_하나_아래에_읍면동이_있다() throws Exception {
    member(get("/api/regions").param("parent", "36"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].code").value("36110"))
        .andExpect(jsonPath("$[0].name").value(nullValue()));
    member(get("/api/regions").param("parent", "36110"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.code == '3611010100')].name").value(hasItem("반곡동")));
  }

  @Test
  void 없는_코드면_빈_목록이다() throws Exception {
    member(get("/api/regions").param("parent", "00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(0)));
    member(get("/api/regions").param("parent", "11999"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(0)));
  }

  @Test
  void parent가_숫자_2자리나_5자리가_아니면_VALIDATION_FAILED다() throws Exception {
    for (String parent : List.of("", "1", "111", "1111010100", "ab", "11a10")) {
      member(get("/api/regions").param("parent", parent))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
    }
  }

  private ResultActions member(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request.with(loginAs(UUID.randomUUID())));
  }
}
