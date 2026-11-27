package com.chilsuitjo.back.action.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.action.rule.ActionType.Allow;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class ActionTypeTest {

  /**
   * DB의 행동 종류 목록은 {@code ck_action_type}을 마지막으로 다시 만든 V 파일의 것이다(V1에서 처음 만들고, 종류를 더할 때마다 새 V 파일이 지우고
   * 다시 만든다. 예: V5 {@code SHOW_PART}). 그 목록과 enum이 같아야 한다.
   */
  @Test
  void 행동_목록은_가장_최근_V_파일의_ck_action_type과_같다() throws IOException {
    Pattern checkPattern =
        Pattern.compile("ck_action_type\\s+CHECK\\s*\\(type IN \\((.*?)\\)\\)", Pattern.DOTALL);
    String latest = null;
    String latestFile = null;
    for (Resource file : migrationsInOrder()) {
      String sql;
      try (InputStream in = file.getInputStream()) {
        sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      Matcher check = checkPattern.matcher(sql);
      while (check.find()) {
        latest = check.group(1);
        latestFile = file.getFilename();
      }
    }
    assertThat(latest).as("V 파일에 ck_action_type이 있다").isNotNull();
    Set<String> inDb =
        Pattern.compile("'([A-Z_]+)'")
            .matcher(latest)
            .results()
            .map(m -> m.group(1))
            .collect(Collectors.toSet());

    Set<String> inEnum =
        Arrays.stream(ActionType.values()).map(Enum::name).collect(Collectors.toSet());

    assertThat(inEnum).as(latestFile).isEqualTo(inDb);
  }

  /** {@code V<번호>__…sql}을 번호 순으로. 글자 순이면 V10이 V2보다 앞서서 번호로 정렬한다. */
  private static List<Resource> migrationsInOrder() throws IOException {
    Resource[] files =
        new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql");
    return Arrays.stream(files)
        .sorted(
            Comparator.comparingInt(
                f -> Integer.parseInt(f.getFilename().substring(1, f.getFilename().indexOf("__")))))
        .toList();
  }

  @Test
  void 정산_뒤에는_사진_지우기와_다시_공유와_문자_그만_받기만_된다() {
    assertThat(allowed(Allow.AFTER_SETTLED))
        .containsExactlyInAnyOrder(
            ActionType.ERASE_PHOTO,
            ActionType.RESUME_SHARING,
            ActionType.SHARE_CONSENT,
            ActionType.OPT_OUT);
  }

  @Test
  void 합류_전_기록에서는_사진_지우기와_문자_그만_받기만_된다() {
    assertThat(allowed(Allow.ON_JOINED))
        .containsExactlyInAnyOrder(ActionType.ERASE_PHOTO, ActionType.OPT_OUT);
  }

  @Test
  void 공유를_그만둔_사람은_허용_목록의_행동만_할_수_있다() {
    assertThat(allowed(Allow.WHILE_SHARING_STOPPED))
        .containsExactlyInAnyOrder(
            ActionType.NOTE_PROGRESS,
            ActionType.REGISTER,
            ActionType.ADD_PHOTO,
            ActionType.ERASE_PHOTO,
            ActionType.MOVE_OUT,
            ActionType.REQUEST_END,
            ActionType.CONFIRM_END,
            ActionType.REJECT_END,
            ActionType.CONFIRM_LIVING,
            ActionType.RESUME_SHARING,
            ActionType.SHARE_CONSENT,
            ActionType.OPT_OUT);
  }

  @Test
  void 거주가_끝난_호실에서_막히는_행동은_하자_등록과_수리_시작과_혼자_종료와_사진_추가뿐이다() {
    assertThat(Arrays.stream(ActionType.values()).filter(ActionType::blockedAfterEnded))
        .containsExactly(
            ActionType.REGISTER,
            ActionType.START_REPAIR,
            ActionType.CLOSE_ALONE,
            ActionType.ADD_PHOTO);
  }

  private static List<ActionType> allowed(Allow allow) {
    return Arrays.stream(ActionType.values()).filter(t -> t.allows(allow)).toList();
  }
}
