package com.chilsuitjo.back.notification.repository;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * 테스트용 가짜 솔라피 서버(실제 업체를 부르지 않음). 경로마다 정해 둔 답을 차례로 돌려주고, 받은 요청(방법·경로·쿼리·헤더·본문)을 남긴다. 정한 답이 없으면
 * 500이다.
 */
public final class MockSolapi implements AutoCloseable {

  public record Received(
      String method, String path, String query, String authorization, String body) {}

  /**
   * @param delay 헤더를 보내기 전 기다림
   * @param bodyStall 헤더와 본문 앞 절반을 보낸 뒤 나머지를 보내기 전 기다림(본문이 멈춘 업체)
   */
  public record Reply(int status, String body, Duration delay, Duration bodyStall) {
    public Reply(int status, String body, Duration delay) {
      this(status, body, delay, Duration.ZERO);
    }

    public static Reply of(int status, String body) {
      return new Reply(status, body, Duration.ZERO);
    }
  }

  private final HttpServer server;
  private final Map<String, ConcurrentLinkedDeque<Reply>> replies = new ConcurrentHashMap<>();
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final Map<String, String[]> listed = new ConcurrentHashMap<>();

  public MockSolapi() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          URI uri = exchange.getRequestURI();
          received.add(
              new Received(
                  exchange.getRequestMethod(),
                  uri.getPath(),
                  uri.getRawQuery(),
                  exchange.getRequestHeaders().getFirst("Authorization"),
                  body));
          ConcurrentLinkedDeque<Reply> queue = replies.get(uri.getPath());
          Reply reply = queue == null ? null : queue.poll();
          if (reply == null && uri.getPath().equals(SolapiClient.LIST_PATH)) {
            reply = listReply(uri.getRawQuery());
          }
          if (reply == null) {
            reply = Reply.of(500, "{\"errorCode\":\"NoReply\"}");
          }
          try {
            Thread.sleep(reply.delay().toMillis());
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          try {
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0 && !reply.bodyStall().isZero()) {
              int half = bytes.length / 2;
              exchange.getResponseBody().write(bytes, 0, half);
              exchange.getResponseBody().flush();
              Thread.sleep(reply.bodyStall().toMillis());
              exchange.getResponseBody().write(bytes, half, bytes.length - half);
            } else if (bytes.length > 0) {
              exchange.getResponseBody().write(bytes);
            }
          } catch (IOException e) {
            // 받는 쪽이 시간 제한으로 먼저 끊은 경우
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  /** 정해 둔 답이 없는 결과 조회에는 물은 메시지 ID 가운데 {@link #listed}로 정한 것만 담아 답한다(다른 문자는 찾지 못함). */
  public void listed(String messageId, String statusCode, String batchLeadId) {
    listed.put(messageId, new String[] {statusCode, batchLeadId});
  }

  private Reply listReply(String rawQuery) {
    String query = rawQuery == null ? "" : URLDecoder.decode(rawQuery, StandardCharsets.UTF_8);
    StringBuilder body = new StringBuilder("{\"limit\":500,\"messageList\":{");
    boolean first = true;
    for (Map.Entry<String, String[]> entry : listed.entrySet()) {
      if (!query.contains("\"" + entry.getKey() + "\"")) {
        continue;
      }
      if (!first) {
        body.append(',');
      }
      first = false;
      body.append(
          "\"%s\":{\"messageId\":\"%s\",\"statusCode\":\"%s\",\"customFields\":{\"batchLeadId\":\"%s\"}}"
              .formatted(entry.getKey(), entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
    }
    return Reply.of(200, body.append("}}").toString());
  }

  public URI baseUrl() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  public void reply(String path, Reply reply) {
    replies.computeIfAbsent(path, p -> new ConcurrentLinkedDeque<>()).add(reply);
  }

  public void reply(String path, int status, String body) {
    reply(path, Reply.of(status, body));
  }

  public List<Received> received() {
    return List.copyOf(received);
  }

  public void reset() {
    replies.clear();
    received.clear();
    listed.clear();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
