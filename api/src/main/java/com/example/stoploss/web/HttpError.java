package com.example.stoploss.web;

import java.io.Serial;

/** An error with an HTTP status whose message is safe to show the client ({@code {"error": message}}). */
public class HttpError extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;
  private final int status;

  public HttpError(int status, String message) {
    super(message, null, false, false);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
