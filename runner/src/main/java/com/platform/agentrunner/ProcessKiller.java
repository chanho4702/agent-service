package com.platform.agentrunner;

/** run 스레드가 지금 돌리는 자식 프로세스(트리)를 죽인다 — 도는 게 없으면 아무것도 안 한다. */
interface ProcessKiller {

    void kill(Thread runThread);
}
