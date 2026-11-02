package com.example.stoploss.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTest {
  static long cents(String s) {
    Money.Amount r = Money.parseAmountToCents(s);
    assertThat(r.error()).isNull();
    return r.cents();
  }

  @Nested
  class ParseAmountToCents {
    @ParameterizedTest(name = "{0} → {1} cents")
    @CsvSource(delimiter = '|', quoteCharacter = '\'', value = {
        "790.68 | 79068",
        "14,455.00 | 1445500", // tpa_one quoted thousands (quotes already removed by the CSV reader)
        "$615.09 | 61509", // tpa_two
        "$4,532.16 | 453216",
        "$1343.78 | 134378", // tpa_two without a thousands separator
        "$30000.00 | 3000000",
        "$0.00 | 0",
        "'  $ 1,234.5 ' | 123450",
        "100 | 10000",
        "0.1 | 10",
        "0.07 | 7",
        "1,000,000.01 | 100000001",
        "999,999,999.99 | 99999999999",
    })
    void parses(String input, long expected) {
      assertThat(cents(input)).isEqualTo(expected);
    }

    @Test
    void neverGoesThroughFloatingPoint() {
      assertThat(cents("0.29")).isEqualTo(29);
      assertThat(cents("1.15")).isEqualTo(115);
      assertThat(cents("4.35")).isEqualTo(435);
      assertThat(cents("1,005.03")).isEqualTo(100503);
    }

    @ParameterizedTest(name = "rejects \"{0}\"")
    @CsvSource(delimiter = '|', quoteCharacter = '\'', value = {
        "N/A | invalid paid amount \"N/A\"",
        "'' | blank",
        "'   ' | blank",
        "abc | invalid",
        "12.345 | invalid", // more than 2 dp
        "1234,56 | invalid", // decimal comma must not become 123456
        "1,23.00 | invalid", // malformed grouping
        "12,34,567.00 | invalid",
        "1.2.3 | invalid",
        "$ | invalid",
        ". | invalid",
        "-12.00 | negative",
        "$-12.00 | negative",
        "-$12.00 | negative",
        "(12.00) | negative",
        "1e5 | invalid",
        "1,000,000,000.00 | out of range",
    })
    void rejects(String input, String reason) {
      Money.Amount r = Money.parseAmountToCents(input);
      assertThat(r.ok()).isFalse();
      assertThat(r.error()).contains(reason);
    }
  }

  @Nested
  class Formatting {
    @Test
    void convertsCentsToDollarsAtTheEdgeWithJavascriptNumberSpelling() {
      assertThat(Money.dollars(1800000).toString()).isEqualTo("18000");
      assertThat(Money.dollars(47899).toString()).isEqualTo("478.99");
      assertThat(Money.dollars(47890).toString()).isEqualTo("478.9");
      assertThat(Money.dollars(1).toString()).isEqualTo("0.01");
      assertThat(Money.dollars(0).toString()).isEqualTo("0");
      assertThat(Money.dollars(99_999_999_999L).toString()).isEqualTo("999999999.99");
      assertThat(Money.dollars(1800000)).isEqualByComparingTo(new BigDecimal("18000.00"));
    }

    @Test
    void formatsCentsForMessagesWithIntegerMaths() {
      assertThat(Money.formatCents(47899)).isEqualTo("478.99");
      assertThat(Money.formatCents(1445500)).isEqualTo("14,455.00");
      assertThat(Money.formatCents(5)).isEqualTo("0.05");
    }

    @Test
    void truncatesPctUsedTo1DpSo4999NeverDisplaysAs50() {
      assertThat(Money.pctUsed(2_499_950, 5_000_000).toString()).isEqualTo("49.9");
      assertThat(Money.pctUsed(2_500_000, 5_000_000).toString()).isEqualTo("50");
      assertThat(Money.pctUsed(5_600_000, 5_000_000).toString()).isEqualTo("112");
      assertThat(Money.pctUsed(1_845_323, 7_500_000).toString()).isEqualTo("24.6");
      assertThat(Money.pctUsed(0, 5_000_000).toString()).isEqualTo("0");
    }

    @Test
    void excessIsNeverNegative() {
      assertThat(Money.excessCents(5_600_000, 5_000_000)).isEqualTo(600_000);
      assertThat(Money.excessCents(100, 5_000_000)).isZero();
    }
  }
}
