package com.ticketrush.ticketrush.global.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 그룹 좌석 홀드 분산락(decisions.md 2번)용 Redisson 클라이언트.
 * build.gradle에 적어둔 대로 redisson-spring-boot-starter 대신 core만 추가하고 여기서 직접
 * 구성한다 — RLock만 쓰고 값 직렬화는 하지 않으므로 Redisson의 Spring 자동 설정(Jackson 2 기반
 * 코덱)을 거칠 필요가 없다.
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + host + ":" + port);
        return Redisson.create(config);
    }
}
