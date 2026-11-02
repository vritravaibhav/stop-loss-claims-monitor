package com.example.stoploss.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** The one error body shape: {@code {"error": "<message>"}}. */
@Component
public class ErrorResponses {
  static final MediaType JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);
  private final JsonMapper json;

  public ErrorResponses(JsonMapper json) {
    this.json = json;
  }

  public static Map<String, String> body(String message) {
    return Map.of("error", message);
  }

  /**
   * For exception handlers. The charset is explicit because DispatcherServlet clears the response content type (and
   * with it the forced UTF-8 encoding) before an exception handler runs.
   */
  public static ResponseEntity<Map<String, String>> entity(int status, String message) {
    return ResponseEntity.status(status).contentType(JSON_UTF8).body(body(message));
  }

  /** For filters, which answer before Spring MVC is involved. */
  public void write(HttpServletResponse response, int status, String message) throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getOutputStream().write(json.writeValueAsBytes(body(message)));
  }
}
