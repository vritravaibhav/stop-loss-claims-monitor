package com.example.stoploss.web;

import static com.example.stoploss.support.FakeTokenVerifier.ANALYST;
import static com.example.stoploss.support.FakeTokenVerifier.BROKER_B;
import static com.example.stoploss.support.FakeTokenVerifier.bearer;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.stoploss.TestData;
import com.example.stoploss.support.DbTest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Through the embedded Tomcat: real multipart parsing, the upload size limit, and when the body is read. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealServerTest extends DbTest {
  @LocalServerPort int port;
  final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void reset() {
    resetClaims();
  }

  static byte[] multipartBody(String boundary, String field, String filename, byte[] content) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field + "\"; filename=\""
        + filename + "\"\r\nContent-Type: text/csv\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    out.writeBytes(content);
    out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return out.toByteArray();
  }

  HttpResponse<String> upload(String email, String contentType, byte[] body) throws IOException, InterruptedException {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/uploads"))
        .header("Authorization", bearer(email))
        .header("Content-Type", contentType)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build(), HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> uploadFile(String email, String filename, byte[] content) throws Exception {
    String boundary = UUID.randomUUID().toString().replace("-", "");
    return upload(email, "multipart/form-data; boundary=" + boundary, multipartBody(boundary, "file", filename, content));
  }

  @Test
  void aMultipartUploadLikeTheBrowsersIsProcessed() throws Exception {
    HttpResponse<String> r = uploadFile(ANALYST, "GA_2026-03.csv", TestData.sample("GA_2026-03.csv"));
    assertThat(r.statusCode()).isEqualTo(201);
    assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
    assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
    assertThat(r.body()).contains("\"filename\":\"GA_2026-03.csv\"")
        .contains("\"counts\":{\"rows\":230,\"applied\":228,\"unchanged\":0,\"olderIgnored\":0,\"summary\":1,\"errors\":1}")
        .contains("{\"rowNumber\":140,\"reason\":\"Member \\\"B-0004\\\" belongs to group GB, not GA\"");
  }

  @Test
  void aFileOver25MbIs413() throws Exception {
    byte[] big = new byte[25 * 1024 * 1024 + 1];
    Arrays.fill(big, (byte) 'x');
    HttpResponse<String> r = uploadFile(ANALYST, "GA_big.csv", big);
    assertThat(r.statusCode()).isEqualTo(413);
    assertThat(r.body()).isEqualTo("{\"error\":\"File is larger than 25 MB\"}");
    assertThat(countRows("upload")).isZero();
  }

  @Test
  void aFileOfExactly25MbIsAccepted() throws Exception {
    byte[] header = (TestData.GA_HEADER + "\r\n").getBytes(StandardCharsets.UTF_8);
    byte[] file = new byte[25 * 1024 * 1024];
    Arrays.fill(file, (byte) '\n'); // blank lines are ignored
    System.arraycopy(header, 0, file, 0, header.length);
    HttpResponse<String> r = uploadFile(ANALYST, "GA_blank.csv", file);
    assertThat(r.statusCode()).isEqualTo(201);
    assertThat(r.body()).contains("\"counts\":{\"rows\":0,");
  }

  @Test
  void aBrokersUploadIsRefusedBeforeTheBodyIsParsed() throws Exception {
    // a truncated multipart body (no closing boundary) makes the parser fail: an analyst gets 400 for it, a broker
    // gets 403 because the body is never parsed for them
    byte[] garbage = "--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"GA_x.csv\"\r\n\r\nClaim ID"
        .getBytes(StandardCharsets.UTF_8);
    String type = "multipart/form-data; boundary=b";
    HttpResponse<String> broker = upload(BROKER_B, type, garbage);
    assertThat(broker.statusCode()).isEqualTo(403);
    assertThat(broker.body()).isEqualTo("{\"error\":\"Only analysts can do this\"}");
    HttpResponse<String> analyst = upload(ANALYST, type, garbage);
    assertThat(analyst.statusCode()).isEqualTo(400);
    assertThat(analyst.body()).isEqualTo("{\"error\":\"Malformed multipart body\"}");
  }

  @Test
  void errorsFromTheAuthFilterAreJsonToo() throws Exception {
    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/me")).build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(401);
    assertThat(r.body()).isEqualTo("{\"error\":\"Missing bearer token\"}");
    assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
    assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
    HttpResponse<String> health = http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/healthz")).build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(health.body()).isEqualTo("{\"ok\":true}");
  }
}
