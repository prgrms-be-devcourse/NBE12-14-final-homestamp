package com.chilsuitjo.back.photo.repository;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.photo.dto.PresignedPostResponse;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 직접 서명한 presigned POST를 실제 S3가 받아 주는지 확인한다(04 5-1 "POST 서명", 04 17장 "사진 처리"). {@code
 * PHOTO_S3_BUCKET}과 AWS 자격 증명이 있을 때만 돈다. 올린 시험 파일은 끝나면 지운다.
 */
@EnabledIfEnvironmentVariable(named = "PHOTO_S3_BUCKET", matches = "\\S+")
class S3UploadPostSignerS3Test {

  private static final long MAX_BYTES = DataSize.ofMegabytes(10).toBytes();

  private final HttpClient http = HttpClient.newHttpClient();
  private final List<String> uploadedKeys = new ArrayList<>();
  private String bucket;
  private S3Client s3;
  private S3UploadPostSigner signer;

  @BeforeEach
  void setUp() {
    bucket = System.getenv("PHOTO_S3_BUCKET");
    String region = System.getenv().getOrDefault("PHOTO_S3_REGION", "ap-northeast-2");
    s3 = S3Client.builder().region(Region.of(region)).build();
    signer =
        new S3UploadPostSigner(
            s3, Clock.systemUTC(), bucket, Duration.ofMinutes(5), DataSize.ofBytes(MAX_BYTES));
  }

  @AfterEach
  void tearDown() {
    for (String key : uploadedKeys) {
      try {
        s3.deleteObject(request -> request.bucket(bucket).key(key));
      } catch (RuntimeException ignored) {
        // pending/ 정리 규칙이 하루 뒤에 지운다.
      }
    }
    s3.close();
  }

  @Test
  void acceptsMatchingFile() throws Exception {
    byte[] file = randomBytes(1024);
    var response = upload(sign(file), file);

    assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
  }

  @Test
  void rejectsFileWithDifferentChecksum() throws Exception {
    byte[] signed = randomBytes(1024);
    byte[] other = randomBytes(1024);
    var response = upload(sign(signed), other);

    assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
  }

  @Test
  void rejectsFileOverSizeLimit() throws Exception {
    byte[] small = randomBytes(1024);
    byte[] large = randomBytes((int) MAX_BYTES + 1);
    var response = upload(sign(small), large);

    assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
    assertThat(response.body()).contains("EntityTooLarge");
  }

  private PresignedPostResponse sign(byte[] file) throws Exception {
    String key = "pending/s3-post-test/" + UUID.randomUUID() + "/original.jpg";
    uploadedKeys.add(key);
    return signer.createPresignedPost(key, file.length, sha256Base64(file));
  }

  private HttpResponse<String> upload(PresignedPostResponse post, byte[] file) throws Exception {
    String boundary = "----s3-post-test-" + UUID.randomUUID();
    var body = new ByteArrayOutputStream();
    for (Map.Entry<String, String> field : post.fields().entrySet()) {
      body.write(
          ("--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\""
                  + field.getKey()
                  + "\"\r\n\r\n"
                  + field.getValue()
                  + "\r\n")
              .getBytes(UTF_8));
    }
    // S3는 file 필드 뒤의 필드를 무시하므로 file을 맨 끝에 둔다.
    body.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"original.jpg\""
                + "\r\nContent-Type: image/jpeg\r\n\r\n")
            .getBytes(UTF_8));
    body.write(file);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(UTF_8));

    var request =
        HttpRequest.newBuilder(URI.create(post.url()))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static byte[] randomBytes(int size) {
    byte[] bytes = new byte[size];
    new java.util.Random().nextBytes(bytes);
    return bytes;
  }

  private static String sha256Base64(byte[] bytes) throws Exception {
    return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
