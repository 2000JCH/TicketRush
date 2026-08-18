package com.ticketrush.ticketrush.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** BaseTimeEntity의 @CreatedDate가 채워지도록 JPA Auditing을 켠다. */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
