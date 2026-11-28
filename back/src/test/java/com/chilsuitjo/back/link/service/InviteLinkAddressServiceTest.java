package com.chilsuitjo.back.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chilsuitjo.back.link.entity.Link;
import com.chilsuitjo.back.link.repository.LinkRepository;
import com.chilsuitjo.back.link.rule.LinkKind;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InviteLinkAddressServiceTest {

  private static final UUID ROOM = UUID.randomUUID();
  private static final UUID PARTY = UUID.randomUUID();
  private static final UUID LINK = UUID.randomUUID();

  private final LinkRepository links = mock(LinkRepository.class);

  private InviteLinkAddressService service(String origin, boolean prod) {
    return new InviteLinkAddressService(
        links,
        new LinkTokenKeys("test-only-link-token-hmac-key-32bytes", java.util.Map.of()),
        origin,
        prod);
  }

  private Link link(LinkKind kind, UUID party, UUID room, String hash, Instant invalidatedAt) {
    Link link = mock(Link.class);
    when(link.getId()).thenReturn(LINK);
    when(link.getKind()).thenReturn(kind);
    when(link.getPartyId()).thenReturn(party);
    when(link.getRoomRecordId()).thenReturn(room);
    when(link.getTokenHash()).thenReturn(hash);
    when(link.getInvalidatedAt()).thenReturn(invalidatedAt);
    return link;
  }

  @Test
  void 유효한_초대_링크면_화면_주소_뒤에_해시_조각으로_토큰을_붙인다() {
    Link valid = link(LinkKind.INVITE, PARTY, ROOM, TestLinkTokens.hash(LINK), null);
    when(links.findById(LINK)).thenReturn(Optional.of(valid));

    assertThat(service("https://homestamp.cloud", true).inviteAddress(ROOM, LINK, PARTY))
        .contains("https://homestamp.cloud/l#" + TestLinkTokens.token(LINK));
  }

  @Test
  void 닫힌_링크_다른_자리_다른_호실_초대가_아닌_링크_다른_해시_없는_링크는_주소가_없다() {
    String hash = TestLinkTokens.hash(LINK);
    InviteLinkAddressService service = service("https://homestamp.cloud", true);
    for (Link link :
        new Link[] {
          link(LinkKind.INVITE, PARTY, ROOM, hash, Instant.EPOCH),
          link(LinkKind.INVITE, UUID.randomUUID(), ROOM, hash, null),
          link(LinkKind.INVITE, PARTY, UUID.randomUUID(), hash, null),
          link(LinkKind.SETTLEMENT_INVITE, PARTY, ROOM, hash, null),
          link(LinkKind.INVITE, PARTY, ROOM, "other", null)
        }) {
      when(links.findById(LINK)).thenReturn(Optional.of(link));
      assertThat(service.inviteAddress(ROOM, LINK, PARTY)).isEmpty();
    }
    when(links.findById(LINK)).thenReturn(Optional.empty());
    assertThat(service.inviteAddress(ROOM, LINK, PARTY)).isEmpty();
  }

  @Test
  void prod에서는_https_공개_주소만_받고_문구에_값을_넣지_않는다() {
    for (String origin :
        new String[] {
          "",
          "http://homestamp.cloud",
          "https://localhost",
          "https://app.localhost",
          "https://127.0.0.1",
          "https://10.0.0.5",
          "https://192.168.0.2",
          "https://[::1]",
          "https://homestamp.cloud/",
          "https://homestamp.cloud/app",
          "https://homestamp.cloud?x=1",
          "https://HomeStamp.cloud",
          "not a url"
        }) {
      assertThatThrownBy(() -> service(origin, true))
          .as(origin)
          .isInstanceOf(IllegalStateException.class)
          .satisfies(e -> {
            if (!origin.isEmpty()) {
              assertThat(e.getMessage()).doesNotContain(origin);
            }
          });
    }
  }

  @Test
  void local_test에서는_개발_화면의_http_주소를_받는다() {
    Link valid = link(LinkKind.INVITE, PARTY, ROOM, TestLinkTokens.hash(LINK), null);
    when(links.findById(LINK)).thenReturn(Optional.of(valid));

    assertThat(service("http://localhost:5173", false).inviteAddress(ROOM, LINK, PARTY))
        .contains("http://localhost:5173/l#" + TestLinkTokens.token(LINK));
  }
}
