package com.platform.agentrunner;

import com.platform.agentservice.worker.ProcessCommandExecutor;
import com.platform.agentservice.worker.WorkerProperties;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 서버와 같은 env 커튼({@link ProcessCommandExecutor})에 프로세스 추적을 더한다 — run 스레드마다 지금 도는 자식 프로세스(git·claude)를
 * 기억해 두었다가 서버가 {@code stopRunIds}로 멈추라고 하면(취소·차단) 그 프로세스 트리를 죽인다. 타임아웃 때도 자손(gradle 데몬·node
 * 등)까지 죽인다 — 러너는 오래 살아 있는 프로세스라 고아 프로세스가 쌓이면 안 된다.
 */
class TrackingCommandExecutor extends ProcessCommandExecutor implements ProcessKiller {

    private final Map<Thread, Process> running = new ConcurrentHashMap<>();

    TrackingCommandExecutor(WorkerProperties properties) {
        super(properties);
    }

    @Override
    protected void started(Process process) {
        running.put(Thread.currentThread(), process);
    }

    @Override
    protected void finished(Process process) {
        running.remove(Thread.currentThread(), process);
    }

    @Override
    protected void destroy(Process process) {
        killTree(process);
    }

    @Override
    public void kill(Thread runThread) {
        Process process = running.get(runThread);
        if (process != null) {
            killTree(process);
        }
    }

    static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
