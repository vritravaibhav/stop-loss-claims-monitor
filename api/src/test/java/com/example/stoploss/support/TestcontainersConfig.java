package com.example.stoploss.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One disposable Postgres 16 (the production major version) for the whole test run; the API's real Flyway migrations
 * run into it on context start. Every Spring test context in the run shares this one container.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return POSTGRES;
  }
}
