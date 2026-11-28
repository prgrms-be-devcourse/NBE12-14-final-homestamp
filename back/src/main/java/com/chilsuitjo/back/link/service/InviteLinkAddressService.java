package com.chilsuitjo.back.link.service;

import com.chilsuitjo.back.link.entity.Link;
import com.chilsuitjo.back.link.repository.LinkRepository;
import com.chilsuitjo.back.link.rule.LinkKind;
import com.chilsuitjo.back.notification.service.LinkAddresses;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link LinkAddresses}의 구현. 초대 링크의 토큰을 {@link LinkTokenKeys}로 다시 만들어 {@code app.origin + "/l#" +
 * 토큰}을 돌려준다(링크 화면이 토큰을 주소의 # 뒤에서 읽어 서버 로그·Referer에 남지 않음, 03 6-1).
 *
 * <p>화면 Origin({@code app.origin})은 시작할 때 확인한다: http(s)://host[:port] 모양이어야 하고, prod에서는 https이고
 * host가 localhost·루프백 주소가 아니어야 한다. 잘못된 주소가 실제 문자에 들어가지 않게 시작하지 않는다(문구에 값을 넣지 않음).
 */
@Service
public class InviteLinkAddressService implements LinkAddresses {

  static final String PATH = "/l#";

  private final LinkRepository links;
  private final LinkTokenKeys tokenKeys;
  private final String origin;

  @Autowired
  InviteLinkAddressService(
      LinkRepository links,
      LinkTokenKeys tokenKeys,
      @Value("${app.origin:}") String origin,
      Environment environment) {
    this(links, tokenKeys, origin, Arrays.asList(environment.getActiveProfiles()).contains("prod"));
  }

  InviteLinkAddressService(
      LinkRepository links, LinkTokenKeys tokenKeys, String origin, boolean prod) {
    this.links = links;
    this.tokenKeys = tokenKeys;
    this.origin = requireOrigin(origin, prod);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<String> inviteAddress(UUID roomRecordId, UUID linkId, UUID recipientPartyId) {
    Objects.requireNonNull(roomRecordId, "roomRecordId");
    Objects.requireNonNull(linkId, "linkId");
    Objects.requireNonNull(recipientPartyId, "recipientPartyId");
    return links
        .findById(linkId)
        .filter(link -> sendable(link, roomRecordId, recipientPartyId))
        .map(link -> origin + PATH + tokenKeys.token(link.getId()));
  }

  private boolean sendable(Link link, UUID roomRecordId, UUID recipientPartyId) {
    return link.getKind() == LinkKind.INVITE
        && link.getInvalidatedAt() == null
        && roomRecordId.equals(link.getRoomRecordId())
        && recipientPartyId.equals(link.getPartyId())
        // 지금 키로 만든 토큰이 저장된 해시와 같아야 그 링크로 열린다(키가 바뀌었으면 열리지 않는 주소를 보내지 않음)
        && tokenKeys.tokenHash(link.getId()).equals(link.getTokenHash());
  }

  static String requireOrigin(String origin, boolean prod) {
    if (origin == null || origin.isBlank()) {
      throw new IllegalStateException("app.origin이 없어 문자에 넣을 링크 주소를 만들 수 없다");
    }
    URI uri;
    try {
      uri = new URI(origin);
    } catch (URISyntaxException e) {
      throw badOrigin();
    }
    String scheme = uri.getScheme();
    String host = uri.getHost();
    boolean web = "https".equals(scheme) || (!prod && "http".equals(scheme));
    if (!web
        || host == null
        || uri.getRawUserInfo() != null
        || !uri.getRawPath().isEmpty()
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || !host.equals(host.toLowerCase(Locale.ROOT))
        || (prod && isLocal(host))) {
      throw badOrigin();
    }
    return origin;
  }

  /** 이름을 찾지 않고(DNS 없이) 글자만 본다. */
  private static boolean isLocal(String host) {
    if ("localhost".equals(host) || host.endsWith(".localhost")) {
      return true;
    }
    String bare = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
    if (!bare.matches("[0-9.]+") && !bare.contains(":")) {
      return false;
    }
    try {
      InetAddress address = InetAddress.ofLiteral(bare);
      return address.isLoopbackAddress()
          || address.isAnyLocalAddress()
          || address.isSiteLocalAddress()
          || address.isLinkLocalAddress();
    } catch (IllegalArgumentException e) {
      return true;
    }
  }

  private static IllegalStateException badOrigin() {
    return new IllegalStateException(
        "app.origin이 문자 링크 주소로 쓸 수 없는 모양이다(prod는 https 공개 주소만): app.origin");
  }
}
