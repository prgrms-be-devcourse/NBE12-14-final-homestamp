package com.chilsuitjo.back.action.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.action.rule.ActionType.Allow;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ActionTypeTest {

  @Test
  void 행동_목록은_V1의_ck_action_type과_같다() throws IOException {
    String sql;
    try (InputStream in = getClass().getResourceAsStream("/db/migration/V1__init_schema.sql")) {
      sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    Matcher check =
        Pattern.compile("ck_action_type\\s+CHECK\\s*\\(type IN \\((.*?)\\)\\)", Pattern.DOTALL)
            .matcher(sql);
    assertThat(check.find()).as("V1에 ck_action_type이 있다").isTrue();
    Set<String> inV1 =
        Pattern.compile("'([A-Z_]+)'")
            .matcher(check.group(1))
            .results()
            .map(m -> m.group(1))
            .collect(Collectors.toSet());

    Set<String> inEnum =
        Arrays.stream(ActionType.values()).map(Enum::name).collect(Collectors.toSet());

    assertThat(inEnum).isEqualTo(inV1);
  }

  @Test
  void 정산_뒤에는_사진_지우기와_다시_공유만_된다() {
    assertThat(allowed(Allow.AFTER_SETTLED))
        .containsExactlyInAnyOrder(
            ActionType.ERASE_PHOTO, ActionType.RESUME_SHARING, ActionType.SHARE_CONSENT);
  }

  @Test
  void 합류_전_기록에서는_사진_지우기만_된다() {
    assertThat(allowed(Allow.ON_JOINED)).containsExactly(ActionType.ERASE_PHOTO);
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
            ActionType.SHARE_CONSENT);
  }

  @Test
  void 거주가_끝난_호실에서_막히는_행동은_하자_등록뿐이다() {
    assertThat(Arrays.stream(ActionType.values()).filter(ActionType::blockedAfterEnded))
        .containsExactly(ActionType.REGISTER);
  }

  private static List<ActionType> allowed(Allow allow) {
    return Arrays.stream(ActionType.values()).filter(t -> t.allows(allow)).toList();
  }
}
