package com.example.stoploss.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness for the compose healthcheck and Cloud Run; no auth, no DB. */
@RestController
public class HealthController {
  @GetMapping("/healthz")
  public Map<String, Boolean> healthz() {
    return Map.of("ok", true);
  }
}
