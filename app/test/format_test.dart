import 'package:flutter_test/flutter_test.dart';
import 'package:stoploss_monitor/format.dart';

void main() {
  group('formatMoney', () {
    test('groups thousands with 2 dp', () {
      expect(formatMoney(1234567), r'$12,345.67');
      expect(formatMoney(5000000), r'$50,000.00');
      expect(formatMoney(12000000000), r'$120,000,000.00');
    });

    test('small amounts', () {
      expect(formatMoney(0), r'$0.00');
      expect(formatMoney(5), r'$0.05');
      expect(formatMoney(79068), r'$790.68');
    });
  });

  test('formatPct shows one decimal place', () {
    expect(formatPct(0), '0.0%');
    expect(formatPct(50), '50.0%');
    expect(formatPct(87.46), '87.5%');
    expect(formatPct(122.5), '122.5%');
    expect(formatPct(1234.5), '1,234.5%');
  });

  test('dates', () {
    expect(formatDate(DateTime(2026, 7, 1)), 'Jul 1, 2026');
    expect(
      formatDateRange(DateTime(2025, 7, 1), DateTime(2026, 6, 30)),
      'Jul 1, 2025 – Jun 30, 2026',
    );
    expect(formatDateTime(DateTime(2026, 11, 2, 14, 5)), 'Nov 2, 2026 2:05 PM');
  });

  group('spendBand', () {
    const deductible = 5000000; // $50,000.00

    test('bands start exactly at 50 / 75 / 100 percent ("reaches" is >=)', () {
      expect(spendBand(0, deductible), SpendBand.normal);
      expect(spendBand(2499999, deductible), SpendBand.normal);
      expect(spendBand(2500000, deductible), SpendBand.watch);
      expect(spendBand(3749999, deductible), SpendBand.watch);
      expect(spendBand(3750000, deductible), SpendBand.high);
      expect(spendBand(4999999, deductible), SpendBand.high);
      expect(spendBand(5000000, deductible), SpendBand.over);
      expect(spendBand(9000000, deductible), SpendBand.over);
    });

    test('uses exact cents, not the rounded percentage', () {
      // $24,999.99 of $50,000 is 49.99998% which rounds to "50.0%", but it
      // has not reached the 50% threshold.
      expect(spendBand(2499999, deductible), SpendBand.normal);
    });

    test('zero deductible does not divide by zero', () {
      expect(spendBand(0, 0), SpendBand.normal);
      expect(spendBand(1, 0), SpendBand.over);
    });

    test('threshold chips map to the same bands', () {
      expect(bandForThreshold(50), SpendBand.watch);
      expect(bandForThreshold(75), SpendBand.high);
      expect(bandForThreshold(100), SpendBand.over);
    });
  });
}
