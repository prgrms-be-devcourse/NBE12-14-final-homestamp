package com.chilsuitjo.back.photo.service;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.photo.dto.UploadFileRequest;
import com.chilsuitjo.back.photo.dto.UploadTicketsRequest;
import com.chilsuitjo.back.photo.dto.UploadTicketsResponse;
import com.chilsuitjo.back.photo.repository.S3UploadPostSigner;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    properties = {
      "photo.s3.bucket=example-photos",
      "upload.daily.member=2",
      "app.keys.upload-ticket=" + PhotoUploadIssueTest.TICKET_KEY
    })
@Import({TestcontainersConfiguration.class, PhotoUploadIssueTest.TestBeans.class})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhotoUploadIssueTest {

  private static final String PATH = "/api/rooms/{id}/upload-tickets";
  static final String TICKET_KEY = "test-only-photo-issue-test-signing-key";
  private static final Instant NOW = Instant.parse("2026-11-25T00:00:00.123Z");
  private static final Instant DEVICE_TIME = NOW.minus(Duration.ofDays(1));
  private static final String ORIGINAL_CHECKSUM = Base64.getEncoder().encodeToString(new byte[32]);
  private static final String THUMB_CHECKSUM =
      Base64.getEncoder().encodeToString("thumbnail-checksum-test-value-01".getBytes(UTF_8));
  private static final UploadFileRequest FILE =
      new UploadFileRequest(200, 100, ORIGINAL_CHECKSUM, THUMB_CHECKSUM, DEVICE_TIME);

  @Autowired private PhotoUploadService uploads;
  @Autowired private MockMvc mvc;
  @Autowired private JsonMapper json;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transaction;
  @Autowired private PhotoUploadQuotaService quota;
  @MockitoSpyBean private S3UploadPostSigner postSigner;

  private final UUID roomId = UUID.randomUUID();
  private final UUID memberId = UUID.randomUUID();

  @BeforeEach
  void createsRoomWithTwoMembers() {
    jdbc.update(
        """
        INSERT INTO state.region (code, sido, dong)
        VALUES ('9999999999', '테스트', '테스트동') ON CONFLICT DO NOTHING
        """);
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, '9999999999', '테스트 호실', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        roomId);
    seat(PartySide.TENANT, memberId);
    seat(PartySide.LANDLORD, UUID.randomUUID());
  }

  @Test
  void issuesTwoFormsAndASignedTicketPerPhoto() throws Exception {
    byte[] body =
        mvc.perform(uploadRequest(FILE, FILE).with(loginAs(memberId)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    var slots = json.readValue(body, UploadTicketsResponse.class).slots();

    assertThat(slots).hasSize(2);
    var slot = slots.getFirst();
    var ticket = SignedJWT.parse(slot.ticket());
    var claims = ticket.getJWTClaimsSet();
    String originalKey = slot.original().fields().get("key");
    String thumbKey = slot.thumb().fields().get("key");

    assertThat(slots).extracting(s -> s.index()).containsExactly(0, 1);
    assertThat(originalKey).startsWith("pending/" + roomId + "/").endsWith("/original.jpg");
    assertThat(thumbKey).isEqualTo(originalKey.replace("/original.jpg", "/thumb.jpg"));
    assertThat(slots.getLast().original().fields().get("key")).isNotEqualTo(originalKey);
    assertThat(slot.original().fields()).containsEntry("x-amz-checksum-sha256", ORIGINAL_CHECKSUM);
    assertThat(slot.thumb().fields()).containsEntry("x-amz-checksum-sha256", THUMB_CHECKSUM);
    assertThat(ticket.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
    assertThat(ticket.getHeader().getType().toString()).isEqualTo("upload-ticket+jwt");
    assertThat(ticket.verify(new MACVerifier(TICKET_KEY.getBytes(UTF_8)))).isTrue();
    assertThat(claims.getSubject()).isEqualTo(memberId.toString());
    assertThat(claims.getClaim("roomId")).isEqualTo(roomId.toString());
    assertThat(claims.getClaim("originalKey")).isEqualTo(originalKey);
    assertThat(claims.getClaim("thumbKey")).isEqualTo(thumbKey);
    assertThat(claims.getClaim("originalSha256")).isEqualTo(ORIGINAL_CHECKSUM);
    assertThat(claims.getClaim("thumbSha256")).isEqualTo(THUMB_CHECKSUM);
    assertThat(claims.getClaim("deviceTakenAt")).isEqualTo(DEVICE_TIME.toString());
    assertThat(claims.getExpirationTime().toInstant()).isEqualTo(slot.ticketExpiresAt());
    assertThat(slot.ticketExpiresAt()).isEqualTo(Instant.parse("2026-11-25T12:00:00Z"));
    assertThat(slot.original().expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    assertThat(usedQuota()).isEqualTo(2);
  }

  @Test
  void requiresLogin() throws Exception {
    mvc.perform(uploadRequest(FILE))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  void hidesRoomFromMembersWhoAreNotParties() throws Exception {
    mvc.perform(uploadRequest(FILE).with(loginAs(UUID.randomUUID())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void rejectsEmptyPhotoList() throws Exception {
    mvc.perform(uploadRequest().with(loginAs(memberId)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void rejectsRequestsOverQuotaAndReturnsRetryAfter() throws Exception {
    mvc.perform(uploadRequest(FILE, FILE).with(loginAs(memberId))).andExpect(status().isOk());

    mvc.perform(uploadRequest(FILE).with(loginAs(memberId)))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("UPLOAD_LIMIT"))
        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "54000"))
        .andExpect(jsonPath("$.slots").doesNotExist());
  }

  @Test
  void doesNotCountQuotaIfThumbnailSigningFails() {
    doThrow(new IllegalStateException("썸네일 서명 실패"))
        .when(postSigner)
        .createPresignedPost(endsWith("/thumb.jpg"), anyLong(), anyString());

    assertThatThrownBy(
            () -> uploads.issue(roomId, memberId, new UploadTicketsRequest(List.of(FILE))))
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("썸네일 서명 실패");
    assertThat(usedQuota()).isZero();
  }

  @Test
  void doesNotHoldTheQuotaRowWhileSigningForms() throws Exception {
    // 운영의 임시 자격 증명은 갱신할 때 네트워크를 탄다. 그동안 같은 회원의 한도 행을 잠그면 안 된다.
    CountDownLatch signing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean first = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              if (first.getAndSet(false)) {
                signing.countDown();
                release.await(5, TimeUnit.SECONDS);
              }
              return invocation.callRealMethod();
            })
        .when(postSigner)
        .createPresignedPost(anyString(), anyLong(), anyString());

    var slowIssue =
        CompletableFuture.supplyAsync(
            () -> uploads.issue(roomId, memberId, new UploadTicketsRequest(List.of(FILE))));
    try {
      assertThat(signing.await(5, TimeUnit.SECONDS)).isTrue();
      CompletableFuture.runAsync(
              () -> transaction.executeWithoutResult(status -> quota.reserve(memberId, 1)))
          .get(2, TimeUnit.SECONDS);
    } finally {
      release.countDown();
    }

    assertThat(slowIssue.get(5, TimeUnit.SECONDS).slots()).hasSize(1);
    assertThat(usedQuota()).isEqualTo(2);
  }

  private Integer usedQuota() {
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(count), 0) FROM state.rate_counter WHERE counter_key = ?",
        Integer.class,
        "upload:" + memberId);
  }

  private MockHttpServletRequestBuilder uploadRequest(UploadFileRequest... files) {
    return post(PATH, roomId)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsBytes(new UploadTicketsRequest(List.of(files))));
  }

  private void seat(PartySide side, UUID member) {
    jdbc.update(
        """
        INSERT INTO state.member (id, status, created_at, version)
        VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)
        """,
        member);
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, member_id, status, connected_at, created_at, version)
        VALUES (?, ?, ?, ?, 'CONNECTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """,
        UUID.randomUUID(),
        roomId,
        side.name(),
        member);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestBeans {

    @Bean
    @Primary
    Clock photoIssueClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    @Primary
    S3Client testPhotoS3Client() {
      return S3Client.builder()
          .region(Region.AP_NORTHEAST_2)
          .credentialsProvider(
              StaticCredentialsProvider.create(
                  AwsBasicCredentials.create("example-access", "example-secret")))
          .build();
    }
  }
}
