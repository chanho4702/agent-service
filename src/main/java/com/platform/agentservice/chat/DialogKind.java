package com.platform.agentservice.chat;

/** SAY = 수다(서버 적재), STATUS = 상태 답변, DIRECTIVE = 지시 코멘트, ASSIGN = 일 맡기기(run 생성) — 뒤 셋은 프론트가 POST로 남긴다. */
public enum DialogKind {
    SAY, STATUS, DIRECTIVE, ASSIGN
}
