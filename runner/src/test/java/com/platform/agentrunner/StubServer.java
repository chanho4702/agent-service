package com.platform.agentrunner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 러너 프로토콜 스텁 서버(JDK HttpServer) — 요청을 기록하고 테스트가 응답을 조종한다. */
class StubServer implements AutoCloseable {

    static final ObjectMapper MAPPER = new ObjectMapper();

    final HttpServer server;
    final Queue<String> claims = new ConcurrentLinkedQueue<>();
    final Set<Long> stopRunIds = ConcurrentHashMap.newKeySet();
    final List<JsonNode> heartbeats = new CopyOnWriteArrayList<>();
    final List<String> authHeaders = new CopyOnWriteArrayList<>();
    final Map<Long, JsonNode> results = new ConcurrentHashMap<>();
    final AtomicInteger resultAttempts = new AtomicInteger();
    final AtomicInteger resultFailuresLeft = new AtomicInteger();
    final AtomicInteger harnessDownloads = new AtomicInteger();
    volatile String kind = "PLATFORM";
    volatile int heartbeatStatus = 200;
    byte[] harnessZip;
    String harnessSha;

    StubServer() throws IOException {
        harnessZip = zip(Map.of(".claude/agents/backend.md", "# backend", "CLAUDE.md", "# 플랫폼 규약", "AGENTS.md", "# agents"));
        harnessSha = HarnessCache.sha256(harnessZip);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/agent/runners/", this::handle);
        server.start();
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().substring("/api/agent/runners".length());
        authHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
        byte[] body = ex.getRequestBody().readAllBytes();
        try {
            if (path.equals("/heartbeat")) {
                if (heartbeatStatus != 200) {
                    send(ex, heartbeatStatus, "{\"error\":\"유효하지 않은 러너 토큰\"}");
                    return;
                }
                heartbeats.add(MAPPER.readTree(body));
                send(ex, 200, MAPPER.writeValueAsString(Map.of("runnerId", 7, "kind", kind, "serverTime", "2026-09-28T00:00:00Z",
                        "stopRunIds", List.copyOf(stopRunIds), "offlineAfterSeconds", 90)));
            } else if (path.equals("/claim")) {
                String claim = claims.poll();
                if (claim == null) {
                    ex.sendResponseHeaders(204, -1);
                    ex.close();
                } else {
                    send(ex, 200, claim);
                }
            } else if (path.equals("/harness")) {
                harnessDownloads.incrementAndGet();
                String inm = ex.getRequestHeaders().getFirst("If-None-Match");
                if (inm != null && inm.equals("\"" + harnessSha + "\"")) {
                    ex.sendResponseHeaders(304, -1);
                    ex.close();
                    return;
                }
                ex.getResponseHeaders().add("ETag", "\"" + harnessSha + "\"");
                ex.getResponseHeaders().add("Content-Type", "application/zip");
                ex.sendResponseHeaders(200, harnessZip.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(harnessZip);
                }
            } else if (path.matches("/runs/\\d+/result")) {
                resultAttempts.incrementAndGet();
                if (resultFailuresLeft.getAndDecrement() > 0) {
                    send(ex, 503, "{\"error\":\"잠시 후\"}");
                    return;
                }
                long runId = Long.parseLong(path.split("/")[2]);
                boolean dup = results.containsKey(runId);
                results.putIfAbsent(runId, MAPPER.readTree(body));
                send(ex, 200, "{\"accepted\":" + !dup + ",\"duplicate\":" + dup + ",\"runStatus\":\"DONE\"}");
            } else {
                send(ex, 404, "{\"error\":\"없음\"}");
            }
        } catch (RuntimeException e) {
            send(ex, 500, "{\"error\":\"stub\"}");
        }
    }

    private static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> e : new java.util.TreeMap<>(entries).entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(0);
                zip.putNextEntry(entry);
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
