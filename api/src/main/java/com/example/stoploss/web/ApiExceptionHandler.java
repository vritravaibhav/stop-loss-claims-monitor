package com.example.stoploss.web;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Every error leaves as {@code {"error": "<message>"}} with the status codes of docs/SPEC.md. */
@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(HttpError.class)
  ResponseEntity<Map<String, String>> httpError(HttpError e) {
    return ErrorResponses.entity(e.status(), e.getMessage());
  }

  /** Unknown route, or a known path with a method it does not have: both are simply "not found". */
  @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class,
      HttpRequestMethodNotSupportedException.class})
  ResponseEntity<Map<String, String>> notFound() {
    return ErrorResponses.entity(404, "Not found");
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<Map<String, String>> tooLarge() {
    return ErrorResponses.entity(413, "File is larger than " + UploadController.MAX_UPLOAD_MB + " MB");
  }

  @ExceptionHandler(MultipartException.class)
  ResponseEntity<Map<String, String>> malformedMultipart(MultipartException e) {
    return ErrorResponses.entity(400, "Malformed multipart body");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, String>> unexpected(Exception e) {
    log.error("unhandled error", e);
    return ErrorResponses.entity(500, "Internal server error");
  }
}
