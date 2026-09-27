package com.platform.agentservice.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 프로젝트 실행 위치 설정(V13 {@code project_execution_site}) — 행이 없으면 전역 기본을 따른다. */
@Entity
@Table(name = "project_execution_site")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectExecutionSite {

    @Id private Long projectId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private ExecutionSite site;
    @Column(nullable = false) private Long updatedBy;
    @Column(nullable = false) private Instant updatedAt;

    public static ProjectExecutionSite of(long projectId, ExecutionSite site, long updatedBy, Instant now) {
        ProjectExecutionSite p = new ProjectExecutionSite();
        p.projectId = projectId;
        p.site = site;
        p.updatedBy = updatedBy;
        p.updatedAt = now;
        return p;
    }

    public void change(ExecutionSite site, long updatedBy, Instant now) {
        this.site = site;
        this.updatedBy = updatedBy;
        this.updatedAt = now;
    }
}
