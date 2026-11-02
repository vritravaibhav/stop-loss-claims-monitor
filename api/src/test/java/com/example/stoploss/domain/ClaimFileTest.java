package com.example.stoploss.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.example.stoploss.TestData;
import com.example.stoploss.domain.ClaimFile.ClaimLine;
import com.example.stoploss.domain.ClaimFile.ClaimRow;
import com.example.stoploss.domain.ClaimFile.ErrorLine;
import com.example.stoploss.domain.ClaimFile.ParsedFile;
import com.example.stoploss.domain.ClaimFile.ParsedLine;
import com.example.stoploss.domain.ClaimFile.SummaryLine;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ClaimFileTest {
  static final String ONE_HEADER = "Claim ID,Adj Seq,Member ID,Service Date,Paid Date,Paid Amount,Status,Diagnosis Code";
  static final String TWO_HEADER = "claim_number,version,member,dos,paid_dt,amount,claim_status";

  static ParsedFile parseOk(String text) {
    ParsedFile r = ClaimFile.parse(text);
    if (!r.ok()) fail(r.error());
    return r;
  }

  static ParsedFile parseOk(byte[] bytes) {
    ParsedFile r = ClaimFile.parse(bytes);
    if (!r.ok()) fail(r.error());
    return r;
  }

  static ParsedLine at(List<ParsedLine> lines, int rowNumber) {
    return lines.stream().filter(l -> l.rowNumber() == rowNumber).findFirst().orElseThrow();
  }

  static ClaimRow claimAt(List<ParsedLine> lines, int rowNumber) {
    return ((ClaimLine) at(lines, rowNumber)).claim();
  }

  static List<ParsedLine> errors(ParsedFile f) {
    return f.lines().stream().filter(l -> l instanceof ErrorLine).toList();
  }

  @Nested
  class LayoutDetection {
    @Test
    void recognisesBothTpaHeaders() {
      assertThat(parseOk(ONE_HEADER + "\r\n").layout().id()).isEqualTo("tpa_one");
      assertThat(parseOk("# TPA Two claims extract\n" + TWO_HEADER + "\r\n").layout().id()).isEqualTo("tpa_two");
    }

    @Test
    void ignoresCaseSurroundingWhitespaceAndPunctuationInHeaderNames() {
      Layouts.Match r = Layouts.detectLayout(List.of("CLAIM ID", " adj seq ", "member_id", "Service Date", "PAID DATE",
          "paid amount", "status", "diagnosis code"));
      assertThat(r.ok()).isTrue();
      assertThat(r.layout().id()).isEqualTo("tpa_one");
    }

    @Test
    void mapsColumnsByNameNotPositionAndIgnoresUnknownExtraColumns() {
      String text = String.join("\r\n",
          "claim_status,extra,member,claim_number,amount,dos,version,paid_dt,another",
          "V,zzz, B-0001 ,77001,\"$1,000.00\",2026-04-01,2,2026-04-20,");
      ParsedFile r = parseOk(text);
      assertThat(r.layout().id()).isEqualTo("tpa_two");
      ClaimLine line = (ClaimLine) r.lines().getFirst();
      assertThat(line.rowNumber()).isEqualTo(2);
      assertThat(line.claim())
          .isEqualTo(new ClaimRow("77001", 2, "B-0001", "2026-04-01", "2026-04-20", 100000, "reversed", null));
    }

    @Test
    void rejectsAnUnknownHeader() {
      ParsedFile r = ClaimFile.parse("foo,bar,baz\r\n1,2,3\r\n");
      assertThat(r.ok()).isFalse();
      assertThat(r.error()).contains("unrecognised header");
    }

    @Test
    void explainsWhichColumnsAreMissingWhenTheHeaderIsCloseToALayout() {
      ParsedFile r = ClaimFile.parse("Claim ID,Adj Seq,Member ID,Service Date,Paid Date,Paid Amount\r\n");
      assertThat(r.ok()).isFalse();
      assertThat(r.error()).isEqualTo("header looks like layout tpa_one but is missing column(s): Status");
    }

    @Test
    void rejectsDuplicateHeaderColumns() {
      ParsedFile r = ClaimFile.parse(ONE_HEADER + ",Status\r\n");
      assertThat(r.ok()).isFalse();
      assertThat(r.error()).contains("duplicate column \"Status\"");
    }

    @Test
    void rejectsAnEmptyFileAndACommentOnlyFile() {
      assertThat(ClaimFile.parse("").ok()).isFalse();
      assertThat(ClaimFile.parse("# just a comment\n\n").ok()).isFalse();
    }

    @Test
    void handlesBomLfOnlyFilesBlankLinesAndTrailingWhitespace() {
      String text = "﻿\n" + ONE_HEADER + "\n\n100,0,A-0001,01/05/2026,01/09/2026,10.00,PAID,I10   \n\n,,,,,,,\n";
      for (ParsedFile r : List.of(parseOk(text), parseOk(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
        assertThat(r.headerRowNumber()).isEqualTo(2);
        assertThat(r.lines()).hasSize(1);
        ClaimLine l = (ClaimLine) r.lines().getFirst();
        assertThat(l.rowNumber()).isEqualTo(4);
        assertThat(l.claim().diagnosisCode()).isEqualTo("I10");
        assertThat(l.claim().amountCents()).isEqualTo(1000);
      }
    }
  }

  @Nested
  class TpaOneSampleFiles {
    @Test
    void ga202601TrailerOnLine223IsASummaryRowAndQuotedThousandsParse() {
      ParsedFile r = parseOk(TestData.sample("GA_2026-01.csv"));
      assertThat(r.layout().id()).isEqualTo("tpa_one");
      assertThat(errors(r)).isEmpty();
      assertThat(at(r.lines(), 223)).isEqualTo(new SummaryLine(223, "TRAILER,221,,,,\"272,718.78\",,"));
      assertThat(r.lines().stream().filter(l -> l instanceof ClaimLine)).hasSize(221);
      // line 2 is the first data row, with a 2025 service date (stored, counts toward no GA policy year)
      assertThat(claimAt(r.lines(), 2))
          .isEqualTo(new ClaimRow("100190", 0, "A-0099", "2025-12-13", "2026-01-02", 79068, "paid", "J45.909"));
    }

    @Test
    void ga202605Line18AmountNaIsARowError() {
      ParsedFile r = parseOk(TestData.sample("GA_2026-05.csv"));
      assertThat(errors(r)).containsExactly(new ErrorLine(18, "101995,0,A-0030,05/02/2026,05/12/2026,N/A,PAID,Z00.00",
          "Invalid paid amount \"N/A\""));
    }

    @Test
    void ga202606Line35ReversedMapsToReversedAndKeepsTheRowAmount() {
      ClaimRow c = claimAt(parseOk(TestData.sample("GA_2026-06.csv")).lines(), 35);
      assertThat(c.claimId()).isEqualTo("101988");
      assertThat(c.version()).isEqualTo(1);
      assertThat(c.memberId()).isEqualTo("A-0009");
      assertThat(c.amountCents()).isEqualTo(3000000);
      assertThat(c.status()).isEqualTo("reversed");
    }
  }

  @Nested
  class TpaTwoSampleFiles {
    @Test
    void gb202604CommentLineLfThenHeaderTrimsPaddedMemberIdsParsesDollarAmounts() {
      ParsedFile r = parseOk(TestData.sample("GB_2026-04.csv"));
      assertThat(r.layout().id()).isEqualTo("tpa_two");
      assertThat(r.headerRowNumber()).isEqualTo(2);
      assertThat(r.lines().stream().filter(l -> !(l instanceof ClaimLine))).isEmpty();
      assertThat(r.lines()).hasSize(181);
      ClaimLine l3 = (ClaimLine) at(r.lines(), 3);
      assertThat(l3.raw()).isEqualTo("7700076,1, B-0179 ,2026-03-15,2026-04-09,$615.09,P");
      assertThat(l3.claim())
          .isEqualTo(new ClaimRow("7700076", 1, "B-0179", "2026-03-15", "2026-04-09", 61509, "paid", null));
      assertThat(claimAt(r.lines(), 77).memberId()).isEqualTo("B-0046");
      assertThat(claimAt(r.lines(), 77).amountCents()).isEqualTo(453216); // $4,532.16
      assertThat(claimAt(r.lines(), 166).amountCents()).isEqualTo(3000000); // $30000.00
    }

    @ParameterizedTest
    @ValueSource(strings = {"GA_2026-02.csv", "GA_2026-03.csv", "GA_2026-04.csv", "GA_2026-07.csv", "GA_2026-08.csv",
        "GA_2026-09.csv", "GB_2026-05.csv", "GB_2026-06.csv", "GB_2026-07.csv", "GB_2026-08.csv", "GB_2026-09.csv"})
    void everySampleFileParsesWithOnlyTheKnownBadRows(String file) {
      // Unknown/foreign members and conflicts are DB checks; parsing alone finds no errors in these files.
      assertThat(errors(parseOk(TestData.sample(file)))).as(file).isEmpty();
    }

    @Test
    void mapsVToReversedVoid() {
      ClaimRow c = claimAt(parseOk(TestData.sample("GB_2026-09.csv")).lines(), 219);
      assertThat(c.claimId()).isEqualTo("7700742");
      assertThat(c.version()).isEqualTo(2);
      assertThat(c.status()).isEqualTo("reversed");
      assertThat(c.amountCents()).isEqualTo(37649);
    }
  }

  @Nested
  class BadRows {
    ParsedFile file(String... rows) {
      List<String> all = new ArrayList<>();
      all.add(ONE_HEADER);
      all.addAll(List.of(rows));
      return parseOk(String.join("\r\n", all));
    }

    String reasonOf(String row) {
      ParsedLine l = file(row).lines().getFirst();
      assertThat(l).isInstanceOf(ErrorLine.class);
      return ((ErrorLine) l).reason();
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '\'', textBlock = """
        1,0,A-0001,01/05/2026,01/09/2026,10.00,PAID | Expected 8 columns, got 7
        1,0,A-0001,01/05/2026,01/09/2026,14,455.00,PAID,I10 | Expected 8 columns, got 9
        ,0,A-0001,01/05/2026,01/09/2026,10.00,PAID,I10 | Claim ID is blank
        1,,A-0001,01/05/2026,01/09/2026,10.00,PAID,I10 | Version is blank
        1,x,A-0001,01/05/2026,01/09/2026,10.00,PAID,I10 | Invalid version "x" (expected a whole number ≥ 0)
        1,-1,A-0001,01/05/2026,01/09/2026,10.00,PAID,I10 | Invalid version "-1" (expected a whole number ≥ 0)
        1,1.5,A-0001,01/05/2026,01/09/2026,10.00,PAID,I10 | Invalid version "1.5" (expected a whole number ≥ 0)
        '1,0,  ,01/05/2026,01/09/2026,10.00,PAID,I10' | Member ID is blank
        1,0,A-0001,,01/09/2026,10.00,PAID,I10 | Service date is blank
        1,0,A-0001,02/30/2026,01/09/2026,10.00,PAID,I10 | Invalid service date "02/30/2026" (not a real calendar date)
        1,0,A-0001,01/05/2026,31/01/2026,10.00,PAID,I10 | Invalid paid date "31/01/2026" (not a real calendar date)
        1,0,A-0001,01/05/2026,01/09/2026,,PAID,I10 | Paid amount is blank
        1,0,A-0001,01/05/2026,01/09/2026,-10.00,PAID,I10 | Negative paid amount "-10.00" (reversals must use the status column)
        1,0,A-0001,01/05/2026,01/09/2026,10.00,PENDING,I10 | Unknown status "PENDING" (expected PAID/P or REVERSED/R/VOID/V)
        1,0,A-0001,01/05/2026,01/09/2026,10.00,,I10 | Status is blank
        1,0,A-0001,01/05/2026,01/09/2026,"10.00,PAID,I10 | Malformed CSV: unterminated quoted field
        """)
    void reportsTheReason(String row, String reason) {
      assertThat(reasonOf(row)).isEqualTo(reason);
    }

    @Test
    void reportsEveryProblemInARowAtOnce() {
      assertThat(reasonOf("1,0,A-0001,2026-02-30,,N/A,X,I10")).isEqualTo(
          "Invalid service date \"2026-02-30\" (not a real calendar date); invalid paid amount \"N/A\"; "
              + "unknown status \"X\" (expected PAID/P or REVERSED/R/VOID/V)");
    }

    @Test
    void acceptsOptionalBlanksStatusSynonymsAndTrailingEmptyCells() {
      ParsedFile r = file(
          "1,0,A-0001,01/05/2026,,10.00,paid,",
          "2,3,A-0001,2026-01-05,,10.00,Void,I10,,",
          "3,0,A-0001,01/05/2026,,10.00,r,I10",
          "total,,,,,\"30.00\",,",
          "Summary:,1,,,,,,");
      assertThat(r.lines().stream().map(l -> l.getClass().getSimpleName()))
          .containsExactly("ClaimLine", "ClaimLine", "ClaimLine", "SummaryLine", "SummaryLine");
      ClaimRow first = ((ClaimLine) r.lines().get(0)).claim();
      assertThat(first.paidDate()).isNull();
      assertThat(first.diagnosisCode()).isNull();
      assertThat(first.status()).isEqualTo("paid");
      ClaimRow second = ((ClaimLine) r.lines().get(1)).claim();
      assertThat(second.version()).isEqualTo(3);
      assertThat(second.status()).isEqualTo("reversed");
      assertThat(second.serviceDate()).isEqualTo("2026-01-05");
      assertThat(((ClaimLine) r.lines().get(2)).claim().status()).isEqualTo("reversed");
    }

    @Test
    void numbersRowsByPhysicalLineCountingCommentHeaderAndBlankLines() {
      String text = "# extract\n\r\n" + TWO_HEADER + "\r\n\r\n1,1,B-0001,2026-04-01,,$1.00,P\r\nbad\r\n";
      ParsedFile r = parseOk(text);
      assertThat(r.headerRowNumber()).isEqualTo(3);
      assertThat(r.lines().stream().map(l -> l.rowNumber() + ":" + l.getClass().getSimpleName()))
          .containsExactly("5:ClaimLine", "6:ErrorLine");
    }

    @Test
    void javascriptWhitespaceRulesApplyToCellsNotJustAscii() {
      // A no-break space around a member id is trimmed (JS trim), and a NEL after an amount is not whitespace in JS.
      ParsedFile r = file("1,0, A-0001 ,01/05/2026,,10.00,PAID,", "2,0,A-0001,01/05/2026,,10.00\u0085,PAID,");
      assertThat(((ClaimLine) r.lines().get(0)).claim().memberId()).isEqualTo("A-0001");
      assertThat(((ErrorLine) r.lines().get(1)).reason()).isEqualTo("Invalid paid amount \"10.00\u0085\"");
    }
  }
}
