package com.example.stoploss.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Small JSON request bodies, read the way the Node API's {@code express.json({ limit: '16kb' })} did: only for
 * {@code Content-Type: application/json}; otherwise there is no body (null). An empty body is {@code {}}; anything but an
 * object or array is malformed (400); more than 16 KiB is 413.
 */
@Component
public class JsonBody {
  public static final int LIMIT_BYTES = 16 * 1024;

  private final JsonMapper json;

  public JsonBody(JsonMapper json) {
    this.json = json;
  }

  public JsonNode read(HttpServletRequest request) throws IOException {
    if (!isJson(request.getContentType())) return null;
    if (request.getContentLengthLong() > LIMIT_BYTES) throw new HttpError(413, "Request body too large");
    byte[] bytes;
    try (InputStream in = request.getInputStream()) {
      bytes = in.readNBytes(LIMIT_BYTES + 1);
    }
    if (bytes.length > LIMIT_BYTES) throw new HttpError(413, "Request body too large");
    String text = new String(bytes, StandardCharsets.UTF_8);
    String trimmed = text.strip();
    if (trimmed.isEmpty()) return json.createObjectNode();
    // strict: only objects and arrays, like body-parser.
    if (trimmed.charAt(0) != '{' && trimmed.charAt(0) != '[') throw new HttpError(400, "Malformed JSON body");
    try {
      return json.readTree(text);
    } catch (JacksonException e) {
      throw new HttpError(400, "Malformed JSON body");
    }
  }

  static boolean isJson(String contentType) {
    if (contentType == null) return false;
    try {
      MediaType type = MediaType.parseMediaType(contentType);
      return type.getType().equalsIgnoreCase("application") && type.getSubtype().equalsIgnoreCase("json");
    } catch (InvalidMediaTypeException e) {
      return false;
    }
  }
}
