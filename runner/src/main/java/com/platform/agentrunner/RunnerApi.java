package com.platform.agentrunner;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * 러너 프로토콜 HTTP 클라이언트 — {@code <server>/api/agent/runners/*}에 {@code Authorization: Bearer agr_…}로 붙는다. 하네스 경로는
 * claim 응답의 {@code harness.path}를 따르지 않고 항상 같은 서버의 고정 경로를 쓴다(토큰을 다른 호스트로 보내지 않게).
 */
class RunnerApi {

    static final String BASE_PATH = "/api/agent/runners";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration HARNESS_TIMEOUT = Duration.ofMinutes(3);

    /** 2xx가 아닌 응답. {@code error}는 서버 {@code {"error": …}} 문구(토큰을 담지 않는다). */
    static final class HttpStatusException extends Exception {
        final int status;
        final String error;

        HttpStatusException(int status, String error) {
            super("HTTP " + status + (error == null ? "" : " — " + error));
            this.status = status;
            this.error = error;
        }

        /** 다시 보내도 결과가 바뀌지 않는 거부(요청 결함·권한) — 재시도하지 않는다. 408·429는 일시적이라 제외. */
        boolean permanent() {
            return status >= 400 && status < 500 && status != 408 && status != 429;
        }
    }

    /** 304면 {@code notModified=true}, 아니면 zip 바이트와 ETag. */
    record HarnessDownload(boolean notModified, byte[] zip, String etag) {
    }

    private final URI base;
    private final String authorization;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String userAgent;

    RunnerApi(URI server, String token, String version) {
        this(server, token, version, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    RunnerApi(URI server, String token, String version, HttpClient client) {
        this.base = URI.create(server.toString() + BASE_PATH);
        this.authorization = "Bearer " + token;
        this.client = client;
        this.mapper = mapper();
        this.userAgent = "agent-runner/" + version;
    }

    static ObjectMapper mapper() {
        return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    Protocol.HeartbeatResponse heartbeat(Protocol.HeartbeatRequest request)
            throws IOException, InterruptedException, HttpStatusException {
        HttpResponse<byte[]> res = send(post("/heartbeat", mapper.writeValueAsBytes(request), REQUEST_TIMEOUT));
        return mapper.readValue(res.body(), Protocol.HeartbeatResponse.class);
    }

    /** 줄 run이 없으면(204) 비어 있다. */
    Optional<Protocol.ClaimResponse> claim() throws IOException, InterruptedException, HttpStatusException {
        HttpResponse<byte[]> res = send(post("/claim", new byte[0], REQUEST_TIMEOUT));
        if (res.statusCode() == 204 || res.body().length == 0) {
            return Optional.empty();
        }
        return Optional.of(mapper.readValue(res.body(), Protocol.ClaimResponse.class));
    }

    HarnessDownload harness(String ifNoneMatch) throws IOException, InterruptedException, HttpStatusException {
        HttpRequest.Builder b = request("/harness", HARNESS_TIMEOUT).GET();
        if (ifNoneMatch != null) {
            b.header("If-None-Match", "\"" + ifNoneMatch + "\"");
        }
        HttpResponse<byte[]> res = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() == 304) {
            return new HarnessDownload(true, null, ifNoneMatch);
        }
        check(res);
        String etag = res.headers().firstValue("ETag").map(RunnerApi::unquote).orElse(null);
        return new HarnessDownload(false, res.body(), etag);
    }

    Protocol.ResultResponse result(long runId, byte[] reportJson) throws IOException, InterruptedException, HttpStatusException {
        HttpResponse<byte[]> res = send(post("/runs/" + runId + "/result", reportJson, REQUEST_TIMEOUT));
        return mapper.readValue(res.body(), Protocol.ResultResponse.class);
    }

    private HttpRequest post(String path, byte[] body, Duration timeout) {
        return request(path, timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    private HttpRequest.Builder request(String path, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(timeout)
                .header("Authorization", authorization)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json");
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException, HttpStatusException {
        HttpResponse<byte[]> res = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        check(res);
        return res;
    }

    private void check(HttpResponse<byte[]> res) throws HttpStatusException {
        int status = res.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        if (status >= 300 && status < 400) {
            // 리다이렉트는 따라가지 않는다(토큰을 다른 주소로 보내지 않게) — 사람이 --server를 고칠 수 있게 행선지만 알린다
            String location = res.headers().firstValue("Location").orElse("?");
            throw new HttpStatusException(status, "리다이렉트 → " + location + " (--server를 그 주소로 지정하세요)");
        }
        throw new HttpStatusException(status, errorOf(res.body()));
    }

    /** 서버 오류 계약 {@code {"error": 메시지}} — 아니면(게이트웨이·nginx HTML 등) 문구 없이 상태만. */
    private String errorOf(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(body);
            JsonNode error = node == null ? null : node.get("error");
            if (error != null && error.isTextual()) {
                String text = error.asText();
                return text.length() > 300 ? text.substring(0, 300) + "…" : text;
            }
        } catch (Exception ignored) {
            // JSON이 아니다
        }
        return null;
    }

    private static String unquote(String etag) {
        String e = etag.trim();
        if (e.startsWith("W/")) {
            e = e.substring(2);
        }
        if (e.length() >= 2 && e.startsWith("\"") && e.endsWith("\"")) {
            e = e.substring(1, e.length() - 1);
        }
        return e;
    }
}
