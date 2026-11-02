package com.example.stoploss.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** The one error body shape: {@code {"error": "<message>"}}. */
@Component
public class ErrorResponses {
  private final JsonMapper json;

  public ErrorResponses(JsonMapper json) {
    this.json = json;
  }

  public static Map<String, String> body(String message) {
    return Map.of("error", message);
  }

  public static ResponseEntity<Map<String, String>> entity(int status, String message) {
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body(message));
  }

  /** For filters, which answer before Spring MVC is involved. */
  public void write(HttpServletResponse response, int status, String message) throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getOutputStream().write(json.writeValueAsBytes(body(message)));
  }
}
