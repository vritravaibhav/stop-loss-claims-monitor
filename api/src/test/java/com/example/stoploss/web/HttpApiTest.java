package com.example.stoploss.web;

import static com.example.stoploss.TestData.GA_HEADER;
import static com.example.stoploss.support.FakeTokenVerifier.ANALYST;
import static com.example.stoploss.support.FakeTokenVerifier.BROKER_A;
import static com.example.stoploss.support.FakeTokenVerifier.BROKER_B;
import static com.example.stoploss.support.FakeTokenVerifier.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.stoploss.TestData;
import com.example.stoploss.seed.LoadSampleService;
import com.example.stoploss.support.DbTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP layer through MockMvc against the real schema: authentication, broker scoping (enforced in SQL),
 * analyst-only writes, response shapes and the acknowledge / upload flows. All sample files are loaded first. Tests
 * run in order because later ones depend on earlier acknowledgements and uploads (as in the Node suite).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpApiTest extends DbTest {
  @Autowired MockMvc mvc;
  @Autowired JsonMapper json;
  @Autowired LoadSampleService loadSample;

  @BeforeAll
  void loadSamples() throws Exception {
    ensureSeeded();
    resetClaims();
    loadSample.loadSampleClaims(TestData.CLAIMS_DIR, "load-sample", r -> {});
  }

  // --- helpers ---------------------------------------------------------------------------------------------------

  MockHttpServletResponse perform(RequestBuilder req) throws Exception {
    return mvc.perform(req).andReturn().getResponse();
  }

  MockHttpServletResponse get(String path, String email) throws Exception {
    MockHttpServletRequestBuilder r = MockMvcRequestBuilders.get(path);
    return perform(email == null ? r : r.header("Authorization", bearer(email)));
  }

  MockHttpServletResponse get(String path) throws Exception {
    return get(path, ANALYST);
  }

  JsonNode body(MockHttpServletResponse r) throws Exception {
    return json.readTree(r.getContentAsString(StandardCharsets.UTF_8));
  }

  JsonNode getJson(String path, String email) throws Exception {
    MockHttpServletResponse r = get(path, email);
    assertThat(r.getStatus()).as(path).isEqualTo(200);
    return body(r);
  }

  JsonNode getJson(String path) throws Exception {
    return getJson(path, ANALYST);
  }

  MockHttpServletResponse ack(Object id, String jsonBody, String email) throws Exception {
    return perform(post("/api/alerts/" + id + "/acknowledge")
        .header("Authorization", bearer(email))
        .contentType(MediaType.APPLICATION_JSON)
        .content(jsonBody));
  }

  MockHttpServletResponse ack(Object id, String jsonBody) throws Exception {
    return ack(id, jsonBody, ANALYST);
  }

  MockHttpServletResponse upload(String name, byte[] content, String email) throws Exception {
    return perform(multipart("/api/uploads")
        .file(new MockMultipartFile("file", name, "text/csv", content))
        .header("Authorization", bearer(email)));
  }

  List<JsonNode> list(JsonNode array) {
    return StreamSupport.stream(array.spliterator(), false).toList();
  }

  List<JsonNode> allAlerts(String email) throws Exception {
    return list(getJson("/api/alerts?status=all", email));
  }

  static List<String> keys(JsonNode object) {
    return object.properties().stream().map(Map.Entry::getKey).toList();
  }

  JsonNode find(JsonNode array, String field, String value) {
    return list(array).stream().filter(n -> n.get(field).asString().equals(value)).findFirst().orElseThrow();
  }

  // --- authentication --------------------------------------------------------------------------------------------

  @Test
  @Order(1)
  void healthzNeedsNoToken() throws Exception {
    MockHttpServletResponse r = get("/healthz", null);
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(r.getContentAsString()).isEqualTo("{\"ok\":true}");
  }

  @Test
  @Order(2)
  void unauthorizedWithoutABearerTokenWithAMalformedHeaderOrWithATokenTheVerifierRejects() throws Exception {
    MockHttpServletResponse none = get("/api/groups", null);
    assertThat(none.getStatus()).isEqualTo(401);
    assertThat(keys(body(none))).containsExactly("error");
    assertThat(none.getHeader("WWW-Authenticate")).startsWith("Bearer");
    assertThat(perform(MockMvcRequestBuilders.get("/api/groups")
        .header("Authorization", "Basic YWJjOmRlZg==")).getStatus()).isEqualTo(401);
    assertThat(perform(MockMvcRequestBuilders.get("/api/groups")
        .header("Authorization", "Bearer")).getStatus()).isEqualTo(401);
    MockHttpServletResponse invalid = perform(MockMvcRequestBuilders.get("/api/groups").header("Authorization", "Bearer not-a-valid-token"));
    assertThat(invalid.getStatus()).isEqualTo(401);
    assertThat(invalid.getHeader("WWW-Authenticate")).isEqualTo("Bearer error=\"invalid_token\"");
    // every /api route, including unknown ones and writes, is behind the token check
    assertThat(get("/api/nope", null).getStatus()).isEqualTo(401);
    assertThat(perform(post("/api/uploads")).getStatus()).isEqualTo(401);
    assertThat(perform(post("/api/alerts/1/acknowledge").contentType(MediaType.APPLICATION_JSON)
        .content("{\"note\":\"x\"}")).getStatus()).isEqualTo(401);
  }

  @Test
  @Order(3)
  void forbiddenForAValidTokenWhoseEmailIsUnknownUnverifiedOrMissing() throws Exception {
    assertThat(get("/api/me", "stranger@example.test").getStatus()).isEqualTo(403);
    MockHttpServletResponse unverified = perform(MockMvcRequestBuilders.get("/api/me").header("Authorization", bearer(ANALYST, false)));
    assertThat(unverified.getStatus()).isEqualTo(403);
    assertThat(body(unverified).get("error").asString()).contains("not verified");
    assertThat(perform(MockMvcRequestBuilders.get("/api/me")
        .header("Authorization", "Bearer test:verified:")).getStatus()).isEqualTo(403);
  }

  @Test
  @Order(4)
  void meReturnsTheRoleAndBrokerageFromTheDatabaseEmailMatchedCaseInsensitively() throws Exception {
    assertThat(get("/api/me", "Analyst@Example.TEST").getContentAsString())
        .isEqualTo("{\"email\":\"analyst@example.test\",\"role\":\"analyst\",\"brokerage\":null}");
    assertThat(get("/api/me", BROKER_A).getContentAsString()).isEqualTo(
        "{\"email\":\"broker.a@example.test\",\"role\":\"broker\",\"brokerage\":{\"id\":\"brokerage-a\",\"name\":\"Brokerage A\"}}");
  }

  @Test
  @Order(5)
  void jsonErrorsNoCachingUnknownRoutes404() throws Exception {
    MockHttpServletResponse r = get("/api/does-not-exist");
    assertThat(r.getStatus()).isEqualTo(404);
    assertThat(r.getContentAsString()).isEqualTo("{\"error\":\"Not found\"}");
    assertThat(r.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(get("/api/groups").getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(get("/api/groups", null).getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(get("/api/groups").getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    // a known path with a method it does not have is simply not found, as in the Node API
    assertThat(perform(post("/api/groups").header("Authorization", bearer(ANALYST))).getStatus()).isEqualTo(404);
    assertThat(get("/nope", null).getStatus()).isEqualTo(404);
  }

  // --- broker scoping (read endpoints) ---------------------------------------------------------------------------

  @Test
  @Order(10)
  void groupsAnalystSeesEveryGroupEachBrokerOnlyTheirBrokerage() throws Exception {
    assertThat(list(getJson("/api/groups")).stream().map(g -> g.get("id").asString()))
        .containsExactly("GA", "GB", "GC", "GD");
    assertThat(list(getJson("/api/groups", BROKER_A)).stream().map(g -> g.get("id").asString())).containsExactly("GA");
    assertThat(list(getJson("/api/groups", BROKER_B)).stream().map(g -> g.get("id").asString())).containsExactly("GB");
  }

  @Test
  @Order(11)
  void groupsShapeBrokerageCountsPolicyYearsInDollarsCurrentPolicyYear() throws Exception {
    JsonNode groups = getJson("/api/groups");
    JsonNode gb = find(groups, "id", "GB");
    assertThat(keys(gb)).containsExactly("id", "name", "brokerage", "memberCount", "openAlertCount", "policyYears",
        "currentPolicyYearId");
    assertThat(gb.get("name").asString()).isEqualTo("Group B");
    assertThat(json.writeValueAsString(gb.get("brokerage"))).isEqualTo("{\"id\":\"brokerage-b\",\"name\":\"Brokerage B\"}");
    assertThat(gb.get("memberCount").asInt()).isEqualTo(250);
    assertThat(gb.get("openAlertCount").asInt()).isEqualTo(7);
    JsonNode years = gb.get("policyYears");
    assertThat(years).hasSize(2);
    long id0 = years.get(0).get("id").asLong();
    long id1 = years.get(1).get("id").asLong();
    assertThat(json.writeValueAsString(years)).isEqualTo(
        "[{\"id\":" + id0 + ",\"startDate\":\"2025-07-01\",\"endDate\":\"2026-06-30\",\"deductible\":75000},"
            + "{\"id\":" + id1 + ",\"startDate\":\"2026-07-01\",\"endDate\":\"2027-06-30\",\"deductible\":80000}]");
    assertThat(gb.get("currentPolicyYearId").asLong()).isEqualTo(id1); // 2026-11-02 is in 2026-27
    JsonNode gc = find(groups, "id", "GC");
    assertThat(gc.get("brokerage").isNull()).isTrue();
    assertThat(gc.get("memberCount").asInt()).isEqualTo(200);
    assertThat(gc.get("openAlertCount").asInt()).isZero();
    JsonNode ga = find(groups, "id", "GA");
    assertThat(ga.get("memberCount").asInt()).isEqualTo(300);
    assertThat(ga.get("openAlertCount").asInt()).isEqualTo(16);

    // no policy year contains "today" → the latest one
    CLOCK.set(Instant.parse("2031-01-01T00:00:00Z"));
    assertThat(find(getJson("/api/groups"), "id", "GB").get("currentPolicyYearId").asLong()).isEqualTo(id1);
  }

  @Test
  @Order(12)
  void aBrokerGets404ForAnotherBrokeragesOrAnAnalystOnlyGroupMemberAndAlerts() throws Exception {
    for (String path : List.of("/api/groups/GB/spend", "/api/groups/GC/spend", "/api/members/B-0002",
        "/api/members/C-0011")) {
      assertThat(get(path, BROKER_A).getStatus()).as(path).isEqualTo(404);
    }
    assertThat(get("/api/alerts?groupId=GB", BROKER_A).getStatus()).isEqualTo(404);
    // ... exactly like something that does not exist
    assertThat(get("/api/groups/NOPE/spend", BROKER_A).getStatus()).isEqualTo(404);
    assertThat(get("/api/members/A-9999", BROKER_A).getStatus()).isEqualTo(404);
    assertThat(get("/api/groups/GB/spend", BROKER_A).getContentAsString()).isEqualTo("{\"error\":\"Group not found\"}");
    // own group works
    assertThat(get("/api/groups/GA/spend", BROKER_A).getStatus()).isEqualTo(200);
    assertThat(get("/api/members/A-0003", BROKER_A).getStatus()).isEqualTo(200);
    assertThat(get("/api/members/B-0002", BROKER_B).getStatus()).isEqualTo(200);
    assertThat(get("/api/members/B-0002").getStatus()).isEqualTo(200);
  }

  @Test
  @Order(13)
  void aPolicyYearOfAnotherGroupIs404AndAMalformedPolicyYearIdIs400() throws Exception {
    JsonNode gb = find(getJson("/api/groups"), "id", "GB");
    long gbYear = gb.get("policyYears").get(0).get("id").asLong();
    assertThat(get("/api/groups/GA/spend?policyYearId=" + gbYear).getStatus()).isEqualTo(404);
    assertThat(get("/api/groups/GA/spend?policyYearId=abc").getStatus()).isEqualTo(400);
    assertThat(get("/api/groups/GA/spend?policyYearId=").getStatus()).isEqualTo(400);
    assertThat(get("/api/groups/GA/spend?policyYearId=1&policyYearId=2").getStatus()).isEqualTo(400);
    assertThat(get("/api/groups/GA/spend?policyYearId=999999").getStatus()).isEqualTo(404);
  }

  @Test
  @Order(14)
  void alertsAreScopedToVisibleGroups() throws Exception {
    List<JsonNode> a = allAlerts(BROKER_A);
    List<JsonNode> b = allAlerts(BROKER_B);
    assertThat(a).hasSize(16).allMatch(x -> x.get("groupId").asString().equals("GA"));
    assertThat(b).hasSize(7).allMatch(x -> x.get("groupId").asString().equals("GB"));
    assertThat(allAlerts(ANALYST)).hasSize(23);
    assertThat(getJson("/api/alerts?status=all&groupId=GB")).hasSize(7);
  }

  @Test
  @Order(15)
  void aBrokerWhoseBrokerageHasNoGroupsSeesNothing() throws Exception {
    jdbc.update("INSERT INTO brokerage (id, name) VALUES ('brokerage-c', 'Brokerage C') ON CONFLICT DO NOTHING");
    jdbc.update("""
        INSERT INTO app_user (email, role, brokerage_id) VALUES ('broker.c@example.test', 'broker', 'brokerage-c')
        ON CONFLICT DO NOTHING""");
    assertThat(get("/api/groups", "broker.c@example.test").getContentAsString()).isEqualTo("[]");
    assertThat(allAlerts("broker.c@example.test")).isEmpty();
    assertThat(get("/api/groups/GA/spend", "broker.c@example.test").getStatus()).isEqualTo(404);
  }

  // --- spend and drill-down --------------------------------------------------------------------------------------

  @Test
  @Order(20)
  void gaSpendEveryMemberRankedByPercentUsedMoneyInDollarsLaserApplied() throws Exception {
    MockHttpServletResponse resp = get("/api/groups/GA/spend");
    assertThat(resp.getStatus()).isEqualTo(200);
    JsonNode r = body(resp);
    assertThat(keys(r)).containsExactly("group", "policyYear", "members");
    assertThat(json.writeValueAsString(r.get("group"))).isEqualTo("{\"id\":\"GA\",\"name\":\"Group A\"}");
    assertThat(r.get("policyYear").get("startDate").asString()).isEqualTo("2026-01-01");
    assertThat(r.get("policyYear").get("endDate").asString()).isEqualTo("2026-12-31");
    assertThat(r.get("policyYear").get("deductible").asInt()).isEqualTo(50000);
    List<JsonNode> members = list(r.get("members"));
    assertThat(members).hasSize(300);
    JsonNode top = members.getFirst();
    assertThat(keys(top)).containsExactly("memberId", "firstName", "lastName", "relationship", "spend", "deductible",
        "hasLaser", "pctUsed", "excess", "claimCount", "alertThresholds");
    assertThat(top.get("memberId").asString()).isEqualTo("A-0005");
    // exact JSON number spelling: 56000 (not 56000.0 / 56000.00 / "56000")
    String raw = resp.getContentAsString();
    assertThat(raw).contains("\"memberId\":\"A-0005\",\"firstName\":\"" + top.get("firstName").asString() + "\"")
        .contains("\"spend\":56000,\"deductible\":50000,\"hasLaser\":false,\"pctUsed\":112,\"excess\":6000,");
    assertThat(json.writeValueAsString(top.get("alertThresholds"))).isEqualTo("[50,75,100]");

    JsonNode a7 = find(r.get("members"), "memberId", "A-0007");
    assertThat(a7.get("spend").asInt()).isEqualTo(65000);
    assertThat(a7.get("deductible").asInt()).isEqualTo(120000);
    assertThat(a7.get("hasLaser").asBoolean()).isTrue();
    assertThat(a7.get("pctUsed").decimalValue()).isEqualByComparingTo("54.1"); // truncated, not rounded
    assertThat(a7.get("excess").asInt()).isZero();
    assertThat(json.writeValueAsString(a7.get("alertThresholds"))).isEqualTo("[50]");
    assertThat(raw).contains("\"pctUsed\":54.1,\"excess\":0,");
    assertThat(json.writeValueAsString(find(r.get("members"), "memberId", "A-0009").get("alertThresholds")))
        .isEqualTo("[50]");
    for (int i = 1; i < members.size(); i++) {
      JsonNode p = members.get(i - 1);
      JsonNode c = members.get(i);
      int cmp = p.get("pctUsed").decimalValue().compareTo(c.get("pctUsed").decimalValue());
      assertThat(cmp > 0 || (cmp == 0 && p.get("memberId").asString().compareTo(c.get("memberId").asString()) < 0))
          .as("%s before %s", p.get("memberId"), c.get("memberId")).isTrue();
    }
    JsonNode last = members.getLast();
    assertThat(last.get("spend").asInt()).isZero();
    assertThat(last.get("pctUsed").asInt()).isZero();
    assertThat(last.get("claimCount").asInt()).isZero();
    assertThat(last.get("alertThresholds")).isEmpty();
  }

  @Test
  @Order(21)
  void gbSpendDefaultsToTheCurrentPolicyYearAndCanBeSwitchedTo2025To26() throws Exception {
    JsonNode current = getJson("/api/groups/GB/spend", BROKER_B);
    assertThat(current.get("policyYear").get("startDate").asString()).isEqualTo("2026-07-01");
    assertThat(current.get("policyYear").get("deductible").asInt()).isEqualTo(80000);
    JsonNode first = current.get("members").get(0);
    assertThat(first.get("memberId").asString()).isEqualTo("B-0010");
    assertThat(first.get("spend").asInt()).isEqualTo(58000);
    assertThat(first.get("pctUsed").decimalValue()).isEqualByComparingTo("72.5");
    long priorId = find(getJson("/api/groups"), "id", "GB").get("policyYears").get(0).get("id").asLong();
    JsonNode prior = getJson("/api/groups/GB/spend?policyYearId=" + priorId, BROKER_B);
    assertThat(prior.get("policyYear").get("startDate").asString()).isEqualTo("2025-07-01");
    JsonNode p0 = prior.get("members").get(0);
    assertThat(p0.get("memberId").asString()).isEqualTo("B-0006");
    assertThat(p0.get("spend").asInt()).isEqualTo(60000);
    assertThat(p0.get("pctUsed").asInt()).isEqualTo(80);
    assertThat(json.writeValueAsString(p0.get("alertThresholds"))).isEqualTo("[50,75]");
    assertThat(find(prior.get("members"), "memberId", "B-0008").get("spend").asInt()).isZero();
  }

  @Test
  @Order(22)
  void memberA0013Claim101991WithAllThreeVersionsV2CurrentLateV1Ignored() throws Exception {
    JsonNode r = getJson("/api/members/A-0013", BROKER_A);
    assertThat(keys(r)).containsExactly("member", "policyYears", "claims");
    assertThat(keys(r.get("member")))
        .containsExactly("memberId", "groupId", "firstName", "lastName", "dateOfBirth", "relationship");
    assertThat(r.get("member").get("memberId").asString()).isEqualTo("A-0013");
    assertThat(r.get("member").get("groupId").asString()).isEqualTo("GA");
    assertThat(r.get("member").get("dateOfBirth").asString()).matches("\\d{4}-\\d{2}-\\d{2}");
    JsonNode years = r.get("policyYears");
    assertThat(years).hasSize(1);
    assertThat(keys(years.get(0)))
        .containsExactly("policyYearId", "startDate", "endDate", "deductible", "hasLaser", "spend", "pctUsed", "excess");
    assertThat(years.get(0).get("startDate").asString()).isEqualTo("2026-01-01");
    assertThat(years.get(0).get("endDate").asString()).isEqualTo("2026-12-31");
    assertThat(years.get(0).get("deductible").asInt()).isEqualTo(50000);
    assertThat(years.get(0).get("hasLaser").asBoolean()).isFalse();
    JsonNode claim = find(r.get("claims"), "claimId", "101991");
    assertThat(keys(claim)).containsExactly("claimId", "policyYearId", "currentVersion", "status", "effectiveAmount",
        "serviceDate", "versions");
    assertThat(claim.get("policyYearId").asLong()).isEqualTo(years.get(0).get("policyYearId").asLong());
    assertThat(claim.get("currentVersion").asInt()).isEqualTo(2);
    assertThat(claim.get("status").asString()).isEqualTo("paid");
    assertThat(claim.get("effectiveAmount").asInt()).isEqualTo(18000);
    assertThat(claim.get("serviceDate").asString()).isEqualTo("2026-03-09");
    List<String> versions = list(claim.get("versions")).stream()
        .map(v -> v.get("version").asInt() + "|" + v.get("paidAmount") + "|" + v.get("filename").asString() + "|"
            + v.get("rowNumber").asInt() + "|" + v.get("isCurrent").asBoolean())
        .toList();
    assertThat(versions).containsExactly("2|18000|GA_2026-05.csv|9|true", "1|25000|GA_2026-06.csv|85|false",
        "0|9000|GA_2026-03.csv|174|false");
    JsonNode v0 = claim.get("versions").get(0);
    assertThat(keys(v0)).containsExactly("version", "memberId", "status", "serviceDate", "paidDate", "diagnosisCode",
        "filename", "rowNumber", "loadedAt", "isCurrent", "paidAmount");
    assertThat(v0.get("status").asString()).isEqualTo("paid");
    assertThat(v0.get("serviceDate").asString()).isEqualTo("2026-03-09");
    assertThat(v0.get("paidDate").isString()).isTrue();
    assertThat(v0.get("loadedAt").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
  }

  @Test
  @Order(23)
  void memberB0008ClaimMovedTo2026To27ByANewerVersionBothYearsShown() throws Exception {
    JsonNode r = getJson("/api/members/B-0008");
    assertThat(list(r.get("policyYears")).stream()
        .map(y -> y.get("startDate").asString() + "|" + y.get("spend") + "|" + y.get("deductible")))
        .containsExactly("2025-07-01|0|75000", "2026-07-01|45000|80000");
    JsonNode claim = find(r.get("claims"), "claimId", "7701086");
    assertThat(claim.get("policyYearId").asLong()).isEqualTo(r.get("policyYears").get(1).get("policyYearId").asLong());
    assertThat(claim.get("serviceDate").asString()).isEqualTo("2026-07-01");
    assertThat(list(claim.get("versions")).stream().map(v -> v.get("serviceDate").asString())).contains("2026-06-30");
  }

  @Test
  @Order(24)
  void aReversedClaimShowsStatusReversedWithEffectiveAmountZero() throws Exception {
    // A-0009, claim 101988
    JsonNode claim = find(getJson("/api/members/A-0009").get("claims"), "claimId", "101988");
    assertThat(claim.get("status").asString()).isEqualTo("reversed");
    assertThat(claim.get("effectiveAmount").toString()).isEqualTo("0");
    assertThat(claim.get("currentVersion").asInt()).isEqualTo(1);
  }

  // --- alerts list -----------------------------------------------------------------------------------------------

  @Test
  @Order(30)
  void defaultStatusIsOpenNewestFirstThenThresholdDescSpecFields() throws Exception {
    List<JsonNode> open = list(getJson("/api/alerts"));
    assertThat(open).hasSize(23);
    assertThat(open.getFirst().get("memberId").asString()).isEqualTo("B-0010");
    assertThat(open.getFirst().get("threshold").asInt()).isEqualTo(50);
    assertThat(open.getFirst().get("uploadFilename").asString()).isEqualTo("GB_2026-09.csv");
    for (int i = 1; i < open.size(); i++) {
      JsonNode p = open.get(i - 1);
      JsonNode c = open.get(i);
      int cmp = p.get("createdAt").asString().compareTo(c.get("createdAt").asString());
      assertThat(cmp > 0 || (cmp == 0 && p.get("threshold").asInt() >= c.get("threshold").asInt())).isTrue();
    }
    JsonNode a7 = open.stream().filter(a -> a.get("memberId").asString().equals("A-0007")).findFirst().orElseThrow();
    assertThat(keys(a7)).containsExactly("id", "groupId", "groupName", "memberId", "memberName", "policyYearId",
        "policyYearStart", "policyYearEnd", "threshold", "spendAtTrigger", "deductibleAtTrigger", "currentSpend",
        "currentPctUsed", "status", "createdAt", "uploadFilename", "acknowledgedBy", "acknowledgedAt", "note");
    assertThat(a7.get("id").isIntegralNumber()).isTrue();
    assertThat(a7.get("groupId").asString()).isEqualTo("GA");
    assertThat(a7.get("groupName").asString()).isEqualTo("Group A");
    assertThat(a7.get("memberName").asString()).matches("\\S+ \\S+.*");
    assertThat(a7.get("policyYearId").isIntegralNumber()).isTrue();
    assertThat(a7.get("policyYearStart").asString()).isEqualTo("2026-01-01");
    assertThat(a7.get("policyYearEnd").asString()).isEqualTo("2026-12-31");
    assertThat(a7.get("threshold").asInt()).isEqualTo(50);
    assertThat(a7.get("spendAtTrigger").isNumber()).isTrue();
    assertThat(a7.get("deductibleAtTrigger").toString()).isEqualTo("120000");
    assertThat(a7.get("currentSpend").toString()).isEqualTo("65000");
    assertThat(a7.get("currentPctUsed").toString()).isEqualTo("54.1");
    assertThat(a7.get("status").asString()).isEqualTo("open");
    assertThat(a7.get("createdAt").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    assertThat(a7.get("uploadFilename").asString()).isEqualTo("GA_2026-06.csv");
    assertThat(a7.get("acknowledgedBy").isNull()).isTrue();
    assertThat(a7.get("acknowledgedAt").isNull()).isTrue();
    assertThat(a7.get("note").isNull()).isTrue();
    // the alert stays after spend drops to 0
    JsonNode b12 = open.stream().filter(a -> a.get("memberId").asString().equals("B-0012")).findFirst().orElseThrow();
    assertThat(b12.get("threshold").asInt()).isEqualTo(50);
    assertThat(b12.get("currentSpend").toString()).isEqualTo("0");
    assertThat(b12.get("currentPctUsed").toString()).isEqualTo("0");
  }

  @Test
  @Order(31)
  void rejectsAnUnknownStatusFilter() throws Exception {
    assertThat(get("/api/alerts?status=closed").getStatus()).isEqualTo(400);
    assertThat(get("/api/alerts?status=").getStatus()).isEqualTo(400);
    assertThat(get("/api/alerts?status=open&status=all").getStatus()).isEqualTo(400);
    assertThat(get("/api/alerts?groupId=GA&groupId=GB").getStatus()).isEqualTo(400);
    assertThat(getJson("/api/alerts?groupId=")).hasSize(23); // empty groupId = no filter
  }

  // --- acknowledge (analyst only) --------------------------------------------------------------------------------

  @Test
  @Order(40)
  void brokersGet403ForTheirOwnAlertsAndForOtherBrokeragesNothingChanges() throws Exception {
    long gaAlert = allAlerts(ANALYST).stream().filter(a -> a.get("groupId").asString().equals("GA")).findFirst()
        .orElseThrow().get("id").asLong();
    long gbAlert = allAlerts(ANALYST).stream().filter(a -> a.get("groupId").asString().equals("GB")).findFirst()
        .orElseThrow().get("id").asLong();
    MockHttpServletResponse own = ack(gaAlert, "{\"note\":\"mine\"}", BROKER_A);
    assertThat(own.getStatus()).isEqualTo(403);
    assertThat(keys(body(own))).containsExactly("error");
    // analyst-only is checked first, so acknowledging another brokerage's alert is 403 too
    assertThat(ack(gbAlert, "{\"note\":\"not mine\"}", BROKER_A).getStatus()).isEqualTo(403);
    assertThat(ack(999_999, "{\"note\":\"x\"}", BROKER_A).getStatus()).isEqualTo(403);
    assertThat(allAlerts(ANALYST)).allMatch(a -> a.get("status").asString().equals("open"));
  }

  @Test
  @Order(41)
  void badRequestForAMissingEmptyOrBlankNoteAndNotFoundForUnknownIds() throws Exception {
    long id = allAlerts(ANALYST).stream().filter(a -> a.get("groupId").asString().equals("GA")).findFirst()
        .orElseThrow().get("id").asLong();
    assertThat(ack(id, "{\"note\":\"\"}").getStatus()).isEqualTo(400);
    assertThat(ack(id, "{\"note\":\"   \"}").getStatus()).isEqualTo(400);
    assertThat(ack(id, "{}").getStatus()).isEqualTo(400);
    assertThat(ack(id, "{\"note\":42}").getStatus()).isEqualTo(400);
    assertThat(ack(id, "{\"note\":\"" + "x".repeat(2001) + "\"}").getStatus()).isEqualTo(400);
    assertThat(ack(999_999, "{\"note\":\"x\"}").getStatus()).isEqualTo(404);
    assertThat(ack("abc", "{\"note\":\"x\"}").getStatus()).isEqualTo(404);
    MockHttpServletResponse malformed = ack(id, "{\"note\":");
    assertThat(malformed.getStatus()).isEqualTo(400);
    assertThat(malformed.getContentAsString()).isEqualTo("{\"error\":\"Malformed JSON body\"}");
    assertThat(ack(id, "\"just a string\"").getStatus()).isEqualTo(400);
    assertThat(ack(id, "{\"note\":\"x\"} trailing").getStatus()).isEqualTo(400);
    // no JSON content type: no body
    assertThat(perform(post("/api/alerts/" + id + "/acknowledge").header("Authorization", bearer(ANALYST))
        .contentType(MediaType.TEXT_PLAIN).content("{\"note\":\"x\"}")).getStatus()).isEqualTo(400);
    assertThat(ack(id, "{\"note\":\"" + "x".repeat(17_000) + "\"}").getStatus()).isEqualTo(413);
    assertThat(allAlerts(ANALYST)).allMatch(a -> a.get("status").asString().equals("open"));
  }

  @Test
  @Order(42)
  void acknowledgesOnceWithANoteTheSecondTimeIs409AndTheChangeShowsInListsAndCounts() throws Exception {
    long target = allAlerts(ANALYST).stream()
        .filter(a -> a.get("memberId").asString().equals("A-0011") && a.get("threshold").asInt() == 100)
        .findFirst().orElseThrow().get("id").asLong();
    MockHttpServletResponse r = ack(target, "{\"note\":\"  Case manager assigned  \"}");
    assertThat(r.getStatus()).isEqualTo(200);
    JsonNode body = body(r);
    assertThat(body.get("id").asLong()).isEqualTo(target);
    assertThat(body.get("status").asString()).isEqualTo("acknowledged");
    assertThat(body.get("acknowledgedBy").asString()).isEqualTo(ANALYST);
    assertThat(body.get("acknowledgedAt").asString()).endsWith("Z");
    assertThat(body.get("note").asString()).isEqualTo("Case manager assigned");
    assertThat(body.get("memberId").asString()).isEqualTo("A-0011");
    assertThat(body.get("threshold").asInt()).isEqualTo(100);
    MockHttpServletResponse again = ack(target, "{\"note\":\"again\"}");
    assertThat(again.getStatus()).isEqualTo(409);
    assertThat(again.getContentAsString()).isEqualTo("{\"error\":\"Alert is already acknowledged\"}");
    assertThat(getJson("/api/alerts")).hasSize(22);
    assertThat(list(getJson("/api/alerts?status=acknowledged", BROKER_A)).stream().map(a -> a.get("id").asLong()))
        .containsExactly(target);
    assertThat(getJson("/api/alerts?status=acknowledged", BROKER_B)).isEmpty();
    assertThat(find(getJson("/api/groups"), "id", "GA").get("openAlertCount").asInt()).isEqualTo(15);
  }

  @Test
  @Order(43)
  void twoConcurrentAcknowledgementsExactlyOneWinsTheOtherGets409() throws Exception {
    long target = getJson("/api/alerts?groupId=GB").get(0).get("id").asLong();
    List<Integer> statuses = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      List<Future<Integer>> f = pool.invokeAll(List.of(
          () -> ack(target, "{\"note\":\"first\"}").getStatus(),
          () -> ack(target, "{\"note\":\"second\"}").getStatus()));
      for (Future<Integer> x : f) statuses.add(x.get());
    }
    assertThat(statuses).containsExactlyInAnyOrder(200, 409);
  }

  // --- uploads (analyst only) ------------------------------------------------------------------------------------

  @Test
  @Order(50)
  void brokersCannotUploadOrReadUploadHistoryAndNothingIsRecorded() throws Exception {
    int before = getJson("/api/uploads").size();
    assertThat(upload("GB_2026-04.csv", TestData.sample("GB_2026-04.csv"), BROKER_B).getStatus()).isEqualTo(403);
    assertThat(get("/api/uploads", BROKER_A).getStatus()).isEqualTo(403);
    assertThat(get("/api/uploads/1", BROKER_A).getStatus()).isEqualTo(403);
    assertThat(getJson("/api/uploads").size()).isEqualTo(before);
  }

  @Test
  @Order(51)
  void reUploadingASampleFileIs201NothingAppliedNoAlertsSpendIdentical() throws Exception {
    String before = get("/api/groups/GA/spend").getContentAsString();
    MockHttpServletResponse r = upload("GA_2026-09.csv", TestData.sample("GA_2026-09.csv"), ANALYST);
    assertThat(r.getStatus()).isEqualTo(201);
    JsonNode b = body(r);
    assertThat(keys(b)).containsExactly("id", "filename", "groupId", "layout", "status", "rejectionReason",
        "uploadedBy", "uploadedAt", "sha256", "counts", "errors", "alertsRaised");
    assertThat(b.get("filename").asString()).isEqualTo("GA_2026-09.csv");
    assertThat(b.get("groupId").asString()).isEqualTo("GA");
    assertThat(b.get("layout").asString()).isEqualTo("tpa_one");
    assertThat(b.get("status").asString()).isEqualTo("processed");
    assertThat(b.get("rejectionReason").isNull()).isTrue();
    assertThat(b.get("uploadedBy").asString()).isEqualTo(ANALYST);
    assertThat(b.get("uploadedAt").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    assertThat(json.writeValueAsString(b.get("counts")))
        .isEqualTo("{\"rows\":269,\"applied\":0,\"unchanged\":268,\"olderIgnored\":0,\"summary\":1,\"errors\":0}");
    assertThat(b.get("errors")).isEmpty();
    assertThat(b.get("alertsRaised")).isEmpty();
    assertThat(b.get("sha256").asString()).matches("[0-9a-f]{64}");
    assertThat(get("/api/groups/GA/spend").getContentAsString()).isEqualTo(before);
  }

  @Test
  @Order(52)
  void aNewClaimReachingExactly50PercentRaisesOneAlertReturnedWithTheUploadResult() throws Exception {
    List<JsonNode> members = list(getJson("/api/groups/GA/spend").get("members"));
    String idle = members.stream().filter(m -> m.get("spend").asInt() == 0).reduce((x, y) -> y).orElseThrow()
        .get("memberId").asString();
    MockHttpServletResponse r = upload("GA_2026-10_extra.csv", TestData.csv(
        "# extra test file",
        GA_HEADER,
        "990001,0," + idle + ",10/01/2026,10/15/2026,\"$25,000.00\",PAID,I10",
        "990002,0," + idle + ",10/02/2026,10/15/2026,N/A,PAID,I10",
        "TRAILER,2,,,,,,"), ANALYST);
    assertThat(r.getStatus()).isEqualTo(201);
    JsonNode b = body(r);
    assertThat(json.writeValueAsString(b.get("counts")))
        .isEqualTo("{\"rows\":3,\"applied\":1,\"unchanged\":0,\"olderIgnored\":0,\"summary\":1,\"errors\":1}");
    assertThat(b.get("errors")).hasSize(1);
    JsonNode err = b.get("errors").get(0);
    assertThat(keys(err)).containsExactly("rowNumber", "reason", "raw");
    assertThat(err.get("rowNumber").asInt()).isEqualTo(4);
    assertThat(err.get("reason").asString()).contains("N/A");
    assertThat(err.get("raw").asString()).contains("990002");
    assertThat(b.get("alertsRaised")).hasSize(1);
    JsonNode a = b.get("alertsRaised").get(0);
    assertThat(json.writeValueAsString(a)).isEqualTo("{\"id\":" + a.get("id").asLong() + ",\"memberId\":\"" + idle
        + "\",\"threshold\":50,\"spend\":25000,\"deductible\":50000,\"pctUsed\":50}");
    JsonNode alert = list(getJson("/api/alerts?groupId=GA")).stream()
        .filter(x -> x.get("memberId").asString().equals(idle)).findFirst().orElseThrow();
    assertThat(alert.get("threshold").asInt()).isEqualTo(50);
    assertThat(alert.get("uploadFilename").asString()).isEqualTo("GA_2026-10_extra.csv");
    assertThat(alert.get("status").asString()).isEqualTo("open");
  }

  @Test
  @Order(53)
  void rejectedFilesAre400WithAnUploadResultBody() throws Exception {
    MockHttpServletResponse unknownGroup =
        upload("GZ_2026-01.csv", TestData.csv(GA_HEADER, "1,0,A-0001,01/01/2026,,1.00,PAID,"), ANALYST);
    assertThat(unknownGroup.getStatus()).isEqualTo(400);
    JsonNode u = body(unknownGroup);
    assertThat(u.get("status").asString()).isEqualTo("rejected");
    assertThat(u.get("groupId").isNull()).isTrue();
    assertThat(u.get("rejectionReason").asString()).contains("Unknown group \"GZ\"");
    MockHttpServletResponse badHeader = upload("GA_weird.csv", TestData.csv("foo,bar,baz", "1,2,3"), ANALYST);
    assertThat(badHeader.getStatus()).isEqualTo(400);
    JsonNode h = body(badHeader);
    assertThat(h.get("status").asString()).isEqualTo("rejected");
    assertThat(h.get("groupId").asString()).isEqualTo("GA");
    assertThat(h.get("layout").isNull()).isTrue();
    assertThat(h.get("counts").get("rows").asInt()).isZero();
  }

  @Test
  @Order(54)
  void badRequestWhenNoFileIsAttachedOrTheFieldNameIsWrong() throws Exception {
    MockHttpServletResponse none = perform(post("/api/uploads").header("Authorization", bearer(ANALYST))
        .contentType(MediaType.APPLICATION_JSON).content("{}"));
    assertThat(none.getStatus()).isEqualTo(400);
    MockHttpServletResponse wrongField = perform(multipart("/api/uploads")
        .file(new MockMultipartFile("upload", "GA_x.csv", "text/csv", TestData.csv(GA_HEADER)))
        .header("Authorization", bearer(ANALYST)));
    assertThat(wrongField.getStatus()).isEqualTo(400);
    assertThat(body(wrongField).get("error").asString()).contains("\"file\"");
    MockHttpServletResponse twoFiles = perform(multipart("/api/uploads")
        .file(new MockMultipartFile("file", "GA_x.csv", "text/csv", TestData.csv(GA_HEADER)))
        .file(new MockMultipartFile("file", "GA_y.csv", "text/csv", TestData.csv(GA_HEADER)))
        .header("Authorization", bearer(ANALYST)));
    assertThat(twoFiles.getStatus()).isEqualTo(400);
    MockHttpServletResponse noFile = perform(multipart("/api/uploads").param("note", "x")
        .header("Authorization", bearer(ANALYST)));
    assertThat(noFile.getStatus()).isEqualTo(400);
    assertThat(body(noFile).get("error").asString()).isEqualTo("Attach the claim file in the multipart field \"file\"");
  }

  @Test
  @Order(55)
  void uploadsListNewestFirstWithoutRowErrorsAndUploadByIdHasThem() throws Exception {
    List<JsonNode> uploads = list(getJson("/api/uploads"));
    assertThat(uploads.getFirst().get("filename").asString()).isEqualTo("GA_weird.csv");
    assertThat(uploads.getFirst().get("status").asString()).isEqualTo("rejected");
    assertThat(uploads).allMatch(x -> !x.has("errors"));
    assertThat(keys(uploads.getFirst())).containsExactly("id", "filename", "groupId", "layout", "status",
        "rejectionReason", "uploadedBy", "uploadedAt", "sha256", "counts", "alertsRaised");
    JsonNode ga02 = uploads.stream().filter(x -> x.get("filename").asString().equals("GA_2026-02.csv")).findFirst()
        .orElseThrow();
    assertThat(ga02.get("uploadedBy").asString()).isEqualTo("load-sample");
    JsonNode detail = getJson("/api/uploads/" + ga02.get("id").asLong());
    assertThat(keys(detail)).containsExactly("id", "filename", "groupId", "layout", "status", "rejectionReason",
        "uploadedBy", "uploadedAt", "sha256", "counts", "alertsRaised", "errors");
    assertThat(detail.get("errors")).hasSize(1);
    assertThat(detail.get("errors").get(0).get("rowNumber").asInt()).isEqualTo(45);
    assertThat(detail.get("errors").get(0).get("reason").asString()).isEqualTo("Unknown member \"A-0999\"");
    assertThat(detail.get("errors").get(0).get("raw").isString()).isTrue();
    JsonNode ga04 = uploads.stream().filter(x -> x.get("filename").asString().equals("GA_2026-04.csv")).findFirst()
        .orElseThrow();
    assertThat(list(ga04.get("alertsRaised")).stream().map(a -> a.get("memberId").asString() + "@"
        + a.get("threshold").asInt() + "=" + a.get("pctUsed"))).containsExactly("A-0011@50=110", "A-0011@75=110",
        "A-0011@100=110");
    assertThat(get("/api/uploads/999999").getStatus()).isEqualTo(404);
    assertThat(get("/api/uploads/abc").getStatus()).isEqualTo(404);
  }

  // --- CORS (dev only) -------------------------------------------------------------------------------------------

  @Test
  @Order(60)
  void corsIsOffByDefaultAndAllowsOnlyTheConfiguredOrigins() throws Exception {
    MockHttpServletResponse plain = perform(options("/api/groups").header("Origin", "http://localhost:8081"));
    assertThat(plain.getHeader("Access-Control-Allow-Origin")).isNull();

    MockMvc dev = MockMvcBuilders.standaloneSetup(new HealthController())
        .addFilters(new CorsFilter(List.of("http://localhost:8081")))
        .build();
    MockHttpServletResponse ok = dev.perform(options("/api/groups")
        .header("Origin", "http://localhost:8081")
        .header("Access-Control-Request-Method", "GET")
        .header("Access-Control-Request-Headers", "authorization")).andReturn().getResponse();
    assertThat(ok.getStatus()).isEqualTo(204);
    assertThat(ok.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:8081");
    assertThat(ok.getHeader("Access-Control-Allow-Headers")).isEqualTo("Authorization,Content-Type");
    MockHttpServletResponse other =
        dev.perform(options("/api/groups").header("Origin", "http://evil.example")).andReturn().getResponse();
    assertThat(other.getHeader("Access-Control-Allow-Origin")).isNull();
    // a request from another origin is not rejected, it just gets no CORS headers (the browser enforces)
    MockHttpServletResponse simple = dev.perform(MockMvcRequestBuilders.get("/healthz").header("Origin", "http://evil.example")).andReturn().getResponse();
    assertThat(simple.getStatus()).isEqualTo(200);
    assertThat(simple.getHeader("Access-Control-Allow-Origin")).isNull();
  }
}
