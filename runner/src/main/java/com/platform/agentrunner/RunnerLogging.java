package com.platform.agentrunner;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 러너 로그 — worker-core(slf4j)와 러너 코드 로그를 JUL 한 곳으로 모아 stdout에 쓴다. 사용자 PC는 사람이 읽는 텍스트, 컨테이너는
 * 한 줄 JSON(플랫폼 규약 — stdout JSON만, Alloy가 수집)이다. 토큰·키·프롬프트를 싣는 로그 문장은 쓰지 않는다(호출부 규약).
 */
final class RunnerLogging {

    private static final DateTimeFormatter TEXT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private RunnerLogging() {
    }

    static void configure(boolean json, PrintStream out) {
        LogManager.getLogManager().reset();
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        root.addHandler(new StdoutHandler(out, json ? new JsonFormatter() : new TextFormatter()));
    }

    /** 레코드마다 바로 flush — 컨테이너 수집기가 줄 단위로 읽는다. */
    static final class StdoutHandler extends Handler {
        private final PrintStream out;

        StdoutHandler(PrintStream out, Formatter formatter) {
            this.out = out;
            setFormatter(formatter);
            setLevel(Level.ALL);
        }

        @Override
        public synchronized void publish(LogRecord record) {
            if (!isLoggable(record)) {
                return;
            }
            out.print(getFormatter().format(record));
            out.flush();
        }

        @Override
        public void flush() {
            out.flush();
        }

        @Override
        public void close() {
            out.flush();
        }
    }

    static final class TextFormatter extends Formatter {
        @Override
        public String format(LogRecord r) {
            StringBuilder sb = new StringBuilder()
                    .append(TEXT_TIME.format(r.getInstant())).append(' ')
                    .append(String.format("%-5s", levelName(r.getLevel()))).append(' ')
                    .append(formatMessage(r)).append(System.lineSeparator());
            if (r.getThrown() != null) {
                sb.append(stackTrace(r.getThrown()));
            }
            return sb.toString();
        }
    }

    /** ECS 필드 이름(게이트웨이 등 플랫폼 서비스와 같은 키) — {@code @timestamp}·{@code log.level}·{@code message}. */
    static final class JsonFormatter extends Formatter {
        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public String format(LogRecord r) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("@timestamp", Instant.ofEpochMilli(r.getMillis()).toString());
            line.put("log.level", levelName(r.getLevel()));
            line.put("log.logger", r.getLoggerName());
            line.put("process.thread.name", Thread.currentThread().getName());
            line.put("service.name", "agent-runner");
            line.put("message", formatMessage(r));
            if (r.getThrown() != null) {
                line.put("error.type", r.getThrown().getClass().getName());
                line.put("error.stack_trace", stackTrace(r.getThrown()));
            }
            try {
                return mapper.writeValueAsString(line) + "\n";
            } catch (Exception e) {
                return "{\"message\":\"로그 직렬화 실패\"}\n";
            }
        }
    }

    private static String levelName(Level level) {
        if (level.intValue() >= Level.SEVERE.intValue()) {
            return "ERROR";
        }
        if (level.intValue() >= Level.WARNING.intValue()) {
            return "WARN";
        }
        if (level.intValue() >= Level.INFO.intValue()) {
            return "INFO";
        }
        return "DEBUG";
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
