package com.chilsuitjo.back.action.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.action.rule.ActionType.Allow;
import com.chilsuitjo.back.action.rule.ActionType.Kind;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
  void 정산_뒤와_합류_전_기록에서는_사진_지우기만_된다() {
    assertThat(Arrays.stream(ActionType.values()).filter(t -> t.allows(Allow.AFTER_SETTLED)))
        .containsExactly(ActionType.ERASE_PHOTO);
    assertThat(Arrays.stream(ActionType.values()).filter(t -> t.allows(Allow.ON_JOINED)))
        .containsExactly(ActionType.ERASE_PHOTO);
  }

  @Test
  void 거주가_끝난_호실에서_막히는_행동은_하자_등록뿐이다() {
    assertThat(Arrays.stream(ActionType.values()).filter(ActionType::blockedAfterEnded))
        .containsExactly(ActionType.REGISTER);
  }

  @Test
  void 공유를_그만둔_사람은_응답과_초대를_할_수_없다() {
    assertThat(ActionType.RESPOND_DEFECT.kind()).isEqualTo(Kind.RESPONSE);
    assertThat(ActionType.RESPOND_DEFECT.blockedWhileSharingStopped()).isTrue();
    assertThat(ActionType.INVITE.blockedWhileSharingStopped()).isTrue();
    assertThat(ActionType.NOTE_PROGRESS.blockedWhileSharingStopped()).isFalse();
  }
}
