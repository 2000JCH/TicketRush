package com.ticketrush.ticketrush.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 대기열 입장 토큰 발급 Scheduler(decisions.md 4번)를 동작시키기 위한 설정. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
