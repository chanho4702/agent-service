package com.platform.agentservice;

import com.platform.agentservice.budget.BudgetProperties;
import com.platform.agentservice.run.SchedulerProperties;
import com.platform.agentservice.worker.WorkerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({WorkerProperties.class, SchedulerProperties.class, BudgetProperties.class})
public class AgentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentServiceApplication.class, args);
    }
}
