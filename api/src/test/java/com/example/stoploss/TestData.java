package com.example.stoploss;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** The repository's {@code data/} directory (tests run from {@code api/}) and small CSV builders. */
public final class TestData {
  private TestData() {}

  public static final Path DATA_DIR = Path.of("..", "data").toAbsolutePath().normalize();
  public static final Path CLAIMS_DIR = DATA_DIR.resolve("claims");

  public static final String GA_HEADER = "Claim ID,Adj Seq,Member ID,Service Date,Paid Date,Paid Amount,Status,Diagnosis Code";
  public static final String GB_HEADER = "claim_number,version,member,dos,paid_dt,amount,claim_status";

  public static byte[] sample(String name) {
    try {
      return Files.readAllBytes(CLAIMS_DIR.resolve(name));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A CSV given as text lines, joined with CRLF like the real files. */
  public static byte[] csv(String... lines) {
    return (String.join("\r\n", List.of(lines)) + "\r\n").getBytes(StandardCharsets.UTF_8);
  }
}
