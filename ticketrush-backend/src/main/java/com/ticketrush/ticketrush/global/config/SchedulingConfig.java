package com.ticketrush.ticketrush.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 주기적 Scheduler를 동작시키기 위한 설정 — 대기열 입장 토큰 발급(decisions.md 4번)과
 * 홀드 만료 처리(redis-design.md 4-1번)가 이 설정에 의존한다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
