package com.platform.agentservice;

import com.platform.agentservice.alert.AlertProperties;
import com.platform.agentservice.budget.BudgetProperties;
import com.platform.agentservice.chat.ChatProperties;
import com.platform.agentservice.credential.CredentialProperties;
import com.platform.agentservice.execution.ExecutionProperties;
import com.platform.agentservice.runner.RunnerProperties;
import com.platform.agentservice.run.MeetingProperties;
import com.platform.agentservice.run.ReviewProperties;
import com.platform.agentservice.run.SchedulerProperties;
import com.platform.agentservice.run.WorklogProperties;
import com.platform.agentservice.worker.WorkerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({WorkerProperties.class, SchedulerProperties.class, BudgetProperties.class,
        ReviewProperties.class, MeetingProperties.class, AlertProperties.class, CredentialProperties.class,
        ChatProperties.class, ExecutionProperties.class, RunnerProperties.class, WorklogProperties.class})
public class AgentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentServiceApplication.class, args);
    }
}
