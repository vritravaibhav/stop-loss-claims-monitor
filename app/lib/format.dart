import 'package:intl/intl.dart';

final _money = NumberFormat.currency(
  locale: 'en_US',
  symbol: r'$',
  decimalDigits: 2,
);
final _pct = NumberFormat('#,##0.0', 'en_US');
final _count = NumberFormat.decimalPattern('en_US');
final _date = DateFormat.yMMMd('en_US');
final _dateTime = DateFormat('MMM d, y h:mm a', 'en_US');

/// Integer cents → `$12,345.67`.
String formatMoney(int cents) => _money.format(cents / 100);

/// A percentage number (already ×100) → `87.5%`.
String formatPct(double pct) => '${_pct.format(pct)}%';

String formatCount(int n) => _count.format(n);

String formatDate(DateTime date) => _date.format(date);

String formatDateTime(DateTime dateTime) => _dateTime.format(dateTime);

String formatDateRange(DateTime start, DateTime end) =>
    '${formatDate(start)} – ${formatDate(end)}';

/// Severity band of a member's spend against their deductible, at the same
/// 50/75/100 thresholds that raise alerts.
enum SpendBand { normal, watch, high, over }

/// Uses the alert rule's integer comparison
/// (`spend * 100 >= threshold * deductible`) instead of the rounded % so the
/// colour never disagrees with whether an alert fired.
SpendBand spendBand(int spendCents, int deductibleCents) {
  if (deductibleCents <= 0) {
    return spendCents > 0 ? SpendBand.over : SpendBand.normal;
  }
  bool reaches(int threshold) =>
      spendCents * 100 >= threshold * deductibleCents;
  if (reaches(100)) return SpendBand.over;
  if (reaches(75)) return SpendBand.high;
  if (reaches(50)) return SpendBand.watch;
  return SpendBand.normal;
}

SpendBand bandForThreshold(int threshold) => switch (threshold) {
  >= 100 => SpendBand.over,
  >= 75 => SpendBand.high,
  >= 50 => SpendBand.watch,
  _ => SpendBand.normal,
};
