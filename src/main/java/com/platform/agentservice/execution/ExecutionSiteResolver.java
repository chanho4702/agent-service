package com.platform.agentservice.execution;

import com.platform.agentservice.execution.dto.ProjectExecutionSiteResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 새 run의 실행 위치 결정(D-P4-1): <b>요청 지정 &gt; 프로젝트 설정 &gt; 전역 기본</b>. 단일 지점 — run을 새로 만드는 곳(USER run·
 * 스케줄러 픽업·회의 소집·회고/매니저 cron·자동 에스컬레이션)은 전부 이 빈을 거친다. 계보 run(REVIEW·반려-fix·continuation)은
 * 여기서 다시 풀지 않고 부모 run의 위치를 승계한다({@code Run} 팩터리) — 워크스페이스가 부모가 돈 곳에 있기 때문이다.
 */
@Slf4j
@Service
public class ExecutionSiteResolver {

    private final ProjectExecutionSiteRepository repository;
    private final ExecutionProperties properties;

    public ExecutionSiteResolver(ProjectExecutionSiteRepository repository, ExecutionProperties properties) {
        this.repository = repository;
        this.properties = properties;
        if (properties.defaultSiteInvalid()) {
            log.warn("platform.agent.execution.default-site 값이 SERVER·LOCAL이 아닙니다 — SERVER로 봅니다");
        }
    }

    /** 테스트·구 호출부용 — 프로젝트 설정 없이 요청 지정 또는 SERVER(P4a 이전 동작). */
    public static ExecutionSiteResolver serverOnly() {
        return new ExecutionSiteResolver(null, new ExecutionProperties("SERVER", true)) {
            @Override
            public ExecutionSite resolve(String requested, long projectId) {
                ExecutionSite parsed = ExecutionSite.parseOrNull(requested);
                return parsed == null ? ExecutionSite.SERVER : parsed;
            }
        };
    }

    /** {@code requested}가 모르는 값이면 400. */
    @Transactional(readOnly = true)
    public ExecutionSite resolve(String requested, long projectId) {
        ExecutionSite parsed = ExecutionSite.parseOrNull(requested);
        if (parsed != null) {
            return parsed;
        }
        return repository.findById(projectId).map(ProjectExecutionSite::getSite).orElse(properties.defaultSiteOrServer());
    }

    @Transactional(readOnly = true)
    public ProjectExecutionSiteResponse project(long projectId) {
        ExecutionSite configured = repository.findById(projectId).map(ProjectExecutionSite::getSite).orElse(null);
        ExecutionSite defaultSite = properties.defaultSiteOrServer();
        return new ProjectExecutionSiteResponse(projectId, configured, configured != null ? configured : defaultSite,
                defaultSite, properties.inProcessEnabled());
    }

    /** {@code site}가 null·공백이면 설정을 지워 전역 기본으로 되돌린다. 이미 만들어진 run의 위치는 바꾸지 않는다. */
    @Transactional
    public ProjectExecutionSiteResponse setProject(long projectId, String site, long actorId) {
        ExecutionSite parsed = ExecutionSite.parseOrNull(site);
        Instant now = Instant.now();
        if (parsed == null) {
            repository.findById(projectId).ifPresent(repository::delete);
        } else {
            repository.findById(projectId).ifPresentOrElse(
                    existing -> existing.change(parsed, actorId, now),
                    () -> repository.save(ProjectExecutionSite.of(projectId, parsed, actorId, now)));
        }
        repository.flush();
        return project(projectId);
    }
}
