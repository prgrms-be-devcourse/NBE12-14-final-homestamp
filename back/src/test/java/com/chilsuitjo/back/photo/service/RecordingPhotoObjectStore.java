package com.chilsuitjo.back.photo.service;

import com.chilsuitjo.back.photo.repository.PhotoObjectStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 메모리 S3. 실제 S3처럼 조건(ETag)이 다르면 412({@link ObjectChangedException})로 거절하고, 복사하면 ETag·체크섬이 따라온다. 호출을
 * 모두 적어 두고({@link #calls()}), 호출 직전에 끼어들 수 있다({@link #onCall}): 실패를 넣거나 latch로 멈춰 세운다.
 */
class RecordingPhotoObjectStore implements PhotoObjectStore {

  /** 호출 직전에 불린다. {@code op}는 HEAD·READ·COPY·DELETE, {@code key}는 읽거나 복사할 원본 키다. */
  @FunctionalInterface
  interface Hook {
    void before(String op, String key) throws Exception;
  }

  private record Stored(byte[] bytes, String contentType, String eTag, String sha256Base64) {}

  private final Map<String, Stored> objects = new ConcurrentHashMap<>();
  private final List<String> calls = new CopyOnWriteArrayList<>();
  private final List<Hook> hooks = new CopyOnWriteArrayList<>();

  /** 테스트마다 새로 시작한다. 파일은 키가 테스트마다 달라 남겨 둬도 섞이지 않지만 함께 비운다. */
  void reset() {
    objects.clear();
    calls.clear();
    hooks.clear();
  }

  /** 브라우저가 presigned POST로 올린 것처럼 둔다. 같은 키로 다시 올리면 ETag가 바뀐다. */
  void put(String key, byte[] bytes) {
    put(key, bytes, "image/jpeg");
  }

  void put(String key, byte[] bytes, String contentType) {
    objects.put(
        key,
        new Stored(bytes.clone(), contentType, "\"" + UUID.randomUUID() + "\"", sha256(bytes)));
  }

  boolean exists(String key) {
    return objects.containsKey(key);
  }

  /** 지금 있는 파일 키. */
  List<String> keys() {
    return List.copyOf(objects.keySet());
  }

  void onCall(Hook hook) {
    hooks.add(hook);
  }

  /** {@code "HEAD pending/…"} 모양의 호출 기록. 복사는 {@code "COPY 원본 -> 대상"}. */
  List<String> calls() {
    return List.copyOf(calls);
  }

  long count(String op) {
    return calls.stream().filter(call -> call.startsWith(op + " ")).count();
  }

  @Override
  public Optional<ObjectHead> head(String key) {
    run("HEAD", key);
    return Optional.ofNullable(objects.get(key))
        .map(o -> new ObjectHead(o.eTag(), o.bytes().length, o.contentType(), o.sha256Base64()));
  }

  @Override
  public byte[] readStart(String key, String eTag, int bytes) {
    run("READ", key);
    Stored stored = matching(key, eTag);
    return Arrays.copyOf(stored.bytes(), Math.min(bytes, stored.bytes().length));
  }

  @Override
  public void copy(String sourceKey, String targetKey, String eTag) {
    run("COPY", sourceKey, "COPY " + sourceKey + " -> " + targetKey);
    objects.put(targetKey, matching(sourceKey, eTag));
  }

  @Override
  public void delete(String key) {
    run("DELETE", key);
    objects.remove(key);
  }

  private Stored matching(String key, String eTag) {
    Stored stored = objects.get(key);
    if (stored == null || !stored.eTag().equals(eTag)) {
      throw new ObjectChangedException(key);
    }
    return stored;
  }

  private void run(String op, String key) {
    run(op, key, op + " " + key);
  }

  private void run(String op, String key, String call) {
    for (Hook hook : new ArrayList<>(hooks)) {
      try {
        hook.before(op, key);
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
    calls.add(call);
  }

  static String sha256(byte[] bytes) {
    try {
      return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
