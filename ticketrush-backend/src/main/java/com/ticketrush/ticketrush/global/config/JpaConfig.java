package com.ticketrush.ticketrush.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** BaseTimeEntity의 @CreatedDate가 채워지도록 JPA Auditing을 켠다. */
@Configuration
@EnableJpaAuditing
public class JpaConfig {

    /**
     * 메서드 전체가 아니라 그 안의 일부 구간만 트랜잭션(DB 커넥션)으로 묶고 싶을 때 쓴다
     * (`ReservationService.requestPayment` — 한계 테스트 원인 진단에서 DB와 무관한 Redis
     * 호출까지 트랜잭션에 끌려들어가 커넥션을 필요 이상으로 오래 붙잡고 있던 것을 발견,
     * 2026-09-05). `@Transactional`은 같은 클래스 안에서 호출하면(self-invocation) 프록시를
     * 안 거쳐 적용되지 않으므로, 메서드 일부만 감싸려면 이렇게 프로그래밍 방식 트랜잭션이 필요하다.
     */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
