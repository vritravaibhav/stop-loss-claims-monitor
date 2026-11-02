import 'package:flutter/material.dart';

import '../app_scope.dart';
import '../format.dart';
import '../models.dart';
import '../widgets/account_menu.dart';
import '../widgets/common.dart';

/// Every claim and version behind a member's spend, per policy year.
class MemberScreen extends StatefulWidget {
  const MemberScreen({super.key, required this.memberId, this.title});

  final String memberId;

  /// Shown in the app bar until the member has loaded.
  final String? title;

  @override
  State<MemberScreen> createState() => _MemberScreenState();
}

class _MemberScreenState extends State<MemberScreen> {
  Future<MemberDetail>? _future;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _future ??= context.api.member(widget.memberId);
  }

  void _reload() =>
      setState(() => _future = context.api.member(widget.memberId));

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.title ?? widget.memberId),
        actions: [
          IconButton(
            tooltip: 'Refresh',
            icon: const Icon(Icons.refresh),
            onPressed: _reload,
          ),
          const AccountMenu(),
        ],
      ),
      body: AsyncView<MemberDetail>(
        future: _future,
        onRetry: _reload,
        builder: (context, detail) => _MemberBody(detail: detail),
      ),
    );
  }
}

class _MemberBody extends StatelessWidget {
  const _MemberBody({required this.detail});

  final MemberDetail detail;

  @override
  Widget build(BuildContext context) {
    final years = [...detail.policyYears]
      ..sort((a, b) => b.startDate.compareTo(a.startDate));
    final knownYearIds = {for (final y in years) y.policyYearId};

    List<Claim> sorted(Iterable<Claim> claims) => claims.toList()
      ..sort((a, b) {
        final byDate = b.serviceDate.compareTo(a.serviceDate);
        return byDate != 0 ? byDate : a.claimId.compareTo(b.claimId);
      });

    final outside = sorted(detail.claims.where((c) => c.policyYearId == null));
    // Claims tied to a policy year the summary doesn't list (shouldn't happen,
    // but never hide a claim).
    final otherYear = sorted(
      detail.claims.where(
        (c) => c.policyYearId != null && !knownYearIds.contains(c.policyYearId),
      ),
    );

    return SelectionArea(
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          ContentWidth(
            maxWidth: 1000,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _MemberHeader(member: detail.member),
                const SizedBox(height: 16),
                if (years.isEmpty)
                  const EmptyState(
                    icon: Icons.event_busy_outlined,
                    message: 'No policy years for this member.',
                  ),
                for (final year in years) ...[
                  _PolicyYearCard(year: year),
                  const SizedBox(height: 8),
                  _ClaimSection(
                    title: 'Claims',
                    claims: sorted(
                      detail.claims.where(
                        (c) => c.policyYearId == year.policyYearId,
                      ),
                    ),
                  ),
                  const SizedBox(height: 24),
                ],
                if (otherYear.isNotEmpty) ...[
                  _ClaimSection(
                    title: 'Claims in other policy years',
                    claims: otherYear,
                  ),
                  const SizedBox(height: 24),
                ],
                if (outside.isNotEmpty)
                  _ClaimSection(
                    title: 'Outside any policy year',
                    subtitle:
                        'Service date falls in none of the group\'s policy '
                        'years, so these do not count toward any spend.',
                    claims: outside,
                    muted: true,
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _MemberHeader extends StatelessWidget {
  const _MemberHeader({required this.member});

  final MemberInfo member;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(member.fullName, style: theme.textTheme.headlineSmall),
            const SizedBox(height: 12),
            Wrap(
              spacing: 40,
              runSpacing: 12,
              children: [
                Stat(label: 'Member ID', value: member.memberId),
                Stat(label: 'Group', value: member.groupId),
                Stat(label: 'Relationship', value: member.relationship),
                if (member.dateOfBirth != null)
                  Stat(
                    label: 'Date of birth',
                    value: formatDate(member.dateOfBirth!),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _PolicyYearCard extends StatelessWidget {
  const _PolicyYearCard({required this.year});

  final MemberPolicyYear year;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    'Policy year ${formatDateRange(year.startDate, year.endDate)}',
                    style: theme.textTheme.titleMedium,
                  ),
                ),
                if (year.hasLaser) const LaserBadge(),
              ],
            ),
            const SizedBox(height: 12),
            SpendBar(
              spendCents: year.spendCents,
              deductibleCents: year.deductibleCents,
              pctUsed: year.pctUsed,
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 40,
              runSpacing: 12,
              children: [
                Stat(label: 'Spend', value: formatMoney(year.spendCents)),
                Stat(
                  label: year.hasLaser
                      ? 'Deductible (laser)'
                      : 'Specific deductible',
                  value: formatMoney(year.deductibleCents),
                ),
                Stat(
                  label: 'Excess',
                  value: formatMoney(year.excessCents),
                  color: year.excessCents > 0 ? theme.colorScheme.error : null,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _ClaimSection extends StatelessWidget {
  const _ClaimSection({
    required this.title,
    required this.claims,
    this.subtitle,
    this.muted = false,
  });

  final String title;
  final String? subtitle;
  final List<Claim> claims;
  final bool muted;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final section = Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(4, 8, 4, 8),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '$title (${claims.length})',
                style: theme.textTheme.titleSmall,
              ),
              if (subtitle != null)
                Text(
                  subtitle!,
                  style: TextStyle(color: theme.colorScheme.onSurfaceVariant),
                ),
            ],
          ),
        ),
        if (claims.isEmpty)
          Padding(
            padding: const EdgeInsets.all(4),
            child: Text(
              'No claims in this policy year.',
              style: TextStyle(color: theme.colorScheme.onSurfaceVariant),
            ),
          )
        else
          Card(
            clipBehavior: Clip.antiAlias,
            color: muted ? theme.colorScheme.surfaceContainerLow : null,
            child: Column(
              children: [
                for (var i = 0; i < claims.length; i++) ...[
                  if (i > 0) const Divider(),
                  _ClaimTile(claim: claims[i], muted: muted),
                ],
              ],
            ),
          ),
      ],
    );
    return muted ? Opacity(opacity: 0.7, child: section) : section;
  }
}

class _ClaimTile extends StatelessWidget {
  const _ClaimTile({required this.claim, required this.muted});

  final Claim claim;
  final bool muted;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final reversed = claim.status == ClaimStatus.reversed;
    final versions = [...claim.versions]
      ..sort((a, b) => b.version.compareTo(a.version));

    return ExpansionTile(
      shape: const Border(),
      collapsedShape: const Border(),
      title: Wrap(
        spacing: 8,
        runSpacing: 4,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          Text(
            'Claim ${claim.claimId}',
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
          Tag('v${claim.currentVersion}'),
          if (reversed) Tag('Reversed', color: scheme.error),
        ],
      ),
      subtitle: Text(
        'Service ${formatDate(claim.serviceDate)} · '
        '${versions.length} ${versions.length == 1 ? 'version' : 'versions'}',
      ),
      trailing: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          Text(
            formatMoney(claim.effectiveAmountCents),
            style: TextStyle(
              fontWeight: FontWeight.w600,
              decoration: muted ? TextDecoration.lineThrough : null,
              fontFeatures: const [FontFeature.tabularFigures()],
            ),
          ),
          Text(
            muted ? 'not counted' : 'counts',
            style: TextStyle(fontSize: 11, color: scheme.onSurfaceVariant),
          ),
        ],
      ),
      childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
      children: [for (final v in versions) _VersionRow(version: v)],
    );
  }
}

class _VersionRow extends StatelessWidget {
  const _VersionRow({required this.version});

  final ClaimVersion version;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final reversed = version.status == ClaimStatus.reversed;
    final labelStyle = TextStyle(fontSize: 12, color: scheme.onSurfaceVariant);

    Widget field(String label, String value, {TextStyle? style}) => Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(label, style: labelStyle),
        Text(value, style: style),
      ],
    );

    return Container(
      margin: const EdgeInsets.only(top: 8),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: version.isCurrent
            ? scheme.primaryContainer.withValues(alpha: 0.45)
            : scheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: version.isCurrent ? scheme.primary : scheme.outlineVariant,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(
            spacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Text(
                'Version ${version.version}',
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
              if (version.isCurrent)
                Tag('Current', color: scheme.primary, icon: Icons.check),
              reversed
                  ? Tag('Reversed', color: scheme.error, icon: Icons.undo)
                  : const Tag('Paid'),
            ],
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 32,
            runSpacing: 8,
            children: [
              field(
                reversed ? 'Paid amount (pays \$0)' : 'Paid amount',
                formatMoney(version.paidAmountCents),
                style: reversed
                    ? const TextStyle(decoration: TextDecoration.lineThrough)
                    : null,
              ),
              field('Service date', formatDate(version.serviceDate)),
              field(
                'Paid date',
                version.paidDate == null ? '–' : formatDate(version.paidDate!),
              ),
              field('Diagnosis', version.diagnosisCode ?? '–'),
              field('Source', '${version.filename} · row ${version.rowNumber}'),
              field('Loaded', formatDateTime(version.loadedAt)),
            ],
          ),
        ],
      ),
    );
  }
}
