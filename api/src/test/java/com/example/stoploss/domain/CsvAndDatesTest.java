package com.example.stoploss.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CsvAndDatesTest {

  @Nested
  class ParseDate {
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(quoteCharacter = '\'', value = {
        "12/13/2025, 2025-12-13",
        "01/02/2026, 2026-01-02",
        "1/2/2026, 2026-01-02",
        "2026-03-15, 2026-03-15",
        "' 2026-06-30 ', 2026-06-30",
        "02/29/2028, 2028-02-29", // leap year
        "2000-02-29, 2000-02-29", // divisible by 400
    })
    void accepts(String input, String expected) {
      assertThat(Dates.parseDate(input, "service date")).isEqualTo(new Dates.DateResult(expected, null));
    }

    @ParameterizedTest(name = "rejects \"{0}\"")
    @CsvSource(delimiter = '|', quoteCharacter = '\'', value = {
        "02/30/2026 | not a real calendar date",
        "02/29/2026 | not a real calendar date", // not a leap year
        "1900-02-29 | not a real calendar date", // divisible by 100, not 400
        "13/01/2026 | not a real calendar date", // DD/MM is not accepted
        "2026-13-01 | not a real calendar date",
        "2026-04-31 | not a real calendar date",
        "00/10/2026 | not a real calendar date",
        "2026/03/15 | expected MM/DD/YYYY or YYYY-MM-DD",
        "15-03-2026 | expected",
        "20260315 | expected",
        "03/15/26 | expected",
        "yesterday | expected",
        "'' | service date is blank",
    })
    void rejects(String input, String reason) {
      Dates.DateResult r = Dates.parseDate(input, "service date");
      assertThat(r.ok()).isFalse();
      assertThat(r.error()).contains(reason);
    }
  }

  @Nested
  class SplitLinesAndDecode {
    @Test
    void handlesCrlfLfLoneCrAndAMix() {
      assertThat(Csv.splitLines("a\r\nb\r\nc\r\n")).containsExactly("a", "b", "c");
      assertThat(Csv.splitLines("a\nb\nc")).containsExactly("a", "b", "c");
      // TPA Two: the comment line ends in LF, the rest in CRLF
      assertThat(Csv.splitLines("# comment\nh1,h2\r\n1,2\r\n")).containsExactly("# comment", "h1,h2", "1,2");
      assertThat(Csv.splitLines("a\rb")).containsExactly("a", "b");
      assertThat(Csv.splitLines("a\r\n\r\nb\r\n")).containsExactly("a", "", "b"); // blank lines keep their numbers
      assertThat(Csv.splitLines("")).isEmpty();
    }

    @Test
    void stripsAUtf8Bom() {
      byte[] bom = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
      byte[] text = "Claim ID,x".getBytes(StandardCharsets.UTF_8);
      byte[] buf = new byte[bom.length + text.length];
      System.arraycopy(bom, 0, buf, 0, bom.length);
      System.arraycopy(text, 0, buf, bom.length, text.length);
      assertThat(Csv.decodeText(buf)).isEqualTo("Claim ID,x");
    }
  }

  @Nested
  class ParseLine {
    @Test
    void splitsPlainAndQuotedFields() {
      assertThat(Csv.parseLine("100190,0,A-0099,12/13/2025,01/02/2026,790.68,PAID,J45.909").cells())
          .containsExactly("100190", "0", "A-0099", "12/13/2025", "01/02/2026", "790.68", "PAID", "J45.909");
      assertThat(Csv.parseLine("100624,0,A-0111,03/15/2026,03/28/2026,\"1,953.51\",PAID,E11.9").cells())
          .containsExactly("100624", "0", "A-0111", "03/15/2026", "03/28/2026", "1,953.51", "PAID", "E11.9");
      assertThat(Csv.parseLine("7700508,1, B-0200 ,2026-05-27,2026-06-22,\"$10,987.25\",P").cells())
          .containsExactly("7700508", "1", " B-0200 ", "2026-05-27", "2026-06-22", "$10,987.25", "P");
    }

    @Test
    void keepsEmptyCellsIncludingTrailingOnes() {
      assertThat(Csv.parseLine("TRAILER,221,,,,\"272,718.78\",,").cells())
          .containsExactly("TRAILER", "221", "", "", "", "272,718.78", "", "");
      assertThat(Csv.parseLine("").cells()).containsExactly("");
    }

    @Test
    void handlesEscapedQuotesAndWhitespaceAroundQuotedFields() {
      assertThat(Csv.parseLine("\"a \"\"b\"\" c\", \"x\" ,y").cells()).containsExactly("a \"b\" c", "x", "y");
    }

    @Test
    void reportsMalformedQuotingInsteadOfGuessing() {
      assertThat(Csv.parseLine("1,\"unterminated,2").ok()).isFalse();
      assertThat(Csv.parseLine("1,\"a\"b,2").ok()).isFalse();
    }
  }

  @Test
  void javascriptWhitespaceSetIsExactlyEcmascripts() {
    // What V8 reports for String.prototype.trim and /\s/ over U+0000..U+FFFF.
    List<Integer> expected = new ArrayList<>(List.of(0x9, 0xa, 0xb, 0xc, 0xd, 0x20, 0xa0, 0x1680));
    for (int c = 0x2000; c <= 0x200a; c++) expected.add(c);
    expected.addAll(List.of(0x2028, 0x2029, 0x202f, 0x205f, 0x3000, 0xfeff));
    List<Integer> actual = new ArrayList<>();
    for (int c = 0; c <= 0xffff; c++) if (Js.isSpace((char) c)) actual.add(c);
    assertThat(actual).isEqualTo(expected);
    assertThat(Js.trim(" ﻿ x 　")).isEqualTo("x");
    assertThat(Js.trim("\u0085x\u0085")).hasSize(3);
  }
}
