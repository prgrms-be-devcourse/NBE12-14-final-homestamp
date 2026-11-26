package com.chilsuitjo.back.room.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.room.entity.PartTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 기본 부위 목록 시드(V7, #142). 6구역 29개이고 설비·가전만 호실마다 켜고 끄는 부위다(03 2장 H "부위"). 목록을 바꾸면 새 V 파일과 함께 이 테스트를
 * 고친다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class PartTemplateSeedTest {

  /** [구역, 부위, 켜고 끄는 부위인지]. 보이는 순서(sort_order)대로. */
  private static final List<List<Object>> EXPECTED =
      List.of(
          List.of("현관", "현관문·도어락", false),
          List.of("현관", "신발장", false),
          List.of("현관", "바닥", false),
          List.of("방", "벽", false),
          List.of("방", "천장", false),
          List.of("방", "바닥", false),
          List.of("방", "창문·창틀", false),
          List.of("방", "방충망", false),
          List.of("방", "붙박이장", false),
          List.of("방", "조명", false),
          List.of("방", "콘센트·스위치", false),
          List.of("주방", "싱크대·배수구", false),
          List.of("주방", "수전", false),
          List.of("주방", "가스레인지·인덕션", false),
          List.of("주방", "후드", false),
          List.of("주방", "수납장", false),
          List.of("욕실", "천장", false),
          List.of("욕실", "벽·타일", false),
          List.of("욕실", "바닥·배수구", false),
          List.of("욕실", "변기", false),
          List.of("욕실", "세면대·수전", false),
          List.of("욕실", "샤워기·욕조", false),
          List.of("욕실", "환풍기", false),
          List.of("설비·가전", "보일러", true),
          List.of("설비·가전", "에어컨", true),
          List.of("설비·가전", "냉장고", true),
          List.of("설비·가전", "세탁기", true),
          List.of("설비·가전", "인터폰", true),
          List.of("기타", "기타", false));

  @Autowired private PartTemplateRepository templates;

  @Test
  void 기본_부위는_6구역_29개이고_설비_가전만_켜고_끈다() {
    List<PartTemplate> all = templates.findAllByOrderBySortOrder();

    assertThat(all)
        .extracting(t -> List.<Object>of(t.getZone(), t.getName(), t.isOptional()))
        .containsExactlyElementsOf(EXPECTED);
    assertThat(all.stream().map(PartTemplate::getZone).distinct()).hasSize(6);
    assertThat(all).extracting(PartTemplate::getSortOrder).doesNotHaveDuplicates();
  }
}
