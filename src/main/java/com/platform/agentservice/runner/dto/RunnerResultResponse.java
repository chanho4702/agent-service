package com.platform.agentservice.runner.dto;

import com.platform.agentservice.run.RunStatus;

/** 결과 보고 응답 — 같은 보고를 다시 보내면 {@code accepted=false, duplicate=true}(처리는 한 번뿐), {@code runStatus}는 처리 후 상태. */
public record RunnerResultResponse(boolean accepted, boolean duplicate, RunStatus runStatus) {
}
