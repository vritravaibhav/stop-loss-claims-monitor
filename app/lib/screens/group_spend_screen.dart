import 'package:flutter/material.dart';

import '../app_scope.dart';
import '../format.dart';
import '../models.dart';
import '../theme.dart';
import '../widgets/account_menu.dart';
import '../widgets/common.dart';
import 'member_screen.dart';

/// Members of one group ranked by % of deductible used, for one policy year.
class GroupSpendScreen extends StatefulWidget {
  const GroupSpendScreen({super.key, required this.group});

  final Group group;

  @override
  State<GroupSpendScreen> createState() => _GroupSpendScreenState();
}

class _GroupSpendScreenState extends State<GroupSpendScreen> {
  late String? _policyYearId = widget.group.currentPolicyYearId;
  Future<GroupSpend>? _future;
  final _search = TextEditingController();
  String _query = '';

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _future ??= _fetch();
  }

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  Future<GroupSpend> _fetch() =>
      context.api.groupSpend(widget.group.id, policyYearId: _policyYearId);

  void _reload() => setState(() {
    _future = _fetch();
  });

  @override
  Widget build(BuildContext context) {
    final years = [...widget.group.policyYears]
      ..sort((a, b) => b.startDate.compareTo(a.startDate));

    return Scaffold(
      appBar: AppBar(
        title: Text('${widget.group.name} · member spend'),
        actions: [
          IconButton(
            tooltip: 'Refresh',
            icon: const Icon(Icons.refresh),
            onPressed: _reload,
          ),
          const AccountMenu(),
        ],
      ),
      body: years.isEmpty
          ? const EmptyState(
              icon: Icons.event_busy_outlined,
              message: 'This group has no policy years.',
            )
          : Column(
              children: [
                ContentWidth(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
                    child: Wrap(
                      spacing: 16,
                      runSpacing: 12,
                      children: [
                        SizedBox(
                          width: 360,
                          child: DropdownButtonFormField<String>(
                            initialValue: _policyYearId,
                            isExpanded: true,
                            decoration: const InputDecoration(
                              labelText: 'Policy year',
                              isDense: true,
                            ),
                            items: [
                              for (final py in years)
                                DropdownMenuItem(
                                  value: py.id,
                                  child: Text(
                                    '${formatDateRange(py.startDate, py.endDate)}'
                                    ' · ${formatMoney(py.deductibleCents)}',
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                ),
                            ],
                            onChanged: (id) {
                              if (id == null || id == _policyYearId) return;
                              _policyYearId = id;
                              _reload();
                            },
                          ),
                        ),
                        SizedBox(
                          width: 300,
                          child: TextField(
                            controller: _search,
                            decoration: InputDecoration(
                              labelText: 'Search name or member ID',
                              isDense: true,
                              prefixIcon: const Icon(Icons.search),
                              suffixIcon: _query.isEmpty
                                  ? null
                                  : IconButton(
                                      tooltip: 'Clear search',
                                      icon: const Icon(Icons.clear),
                                      onPressed: () {
                                        _search.clear();
                                        setState(() => _query = '');
                                      },
                                    ),
                            ),
                            onChanged: (v) =>
                                setState(() => _query = v.trim().toLowerCase()),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                Expanded(
                  child: AsyncView<GroupSpend>(
                    future: _future,
                    onRetry: _reload,
                    builder: (context, spend) =>
                        _SpendTable(spend: spend, query: _query),
                  ),
                ),
              ],
            ),
    );
  }
}

class _SpendTable extends StatelessWidget {
  const _SpendTable({required this.spend, required this.query});

  final GroupSpend spend;
  final String query;

  @override
  Widget build(BuildContext context) {
    // Rank is the server's order (pctUsed desc, then member id), kept stable
    // while searching.
    final ranked = [
      for (var i = 0; i < spend.members.length; i++)
        (rank: i + 1, member: spend.members[i]),
    ];
    final visible = query.isEmpty
        ? ranked
        : ranked
              .where(
                (r) =>
                    r.member.memberId.toLowerCase().contains(query) ||
                    r.member.fullName.toLowerCase().contains(query),
              )
              .toList();

    return LayoutBuilder(
      builder: (context, constraints) {
        final wide = constraints.maxWidth >= 900;
        return ListView.builder(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
          itemCount: visible.length + 2,
          itemBuilder: (context, i) {
            if (i == 0) {
              return ContentWidth(child: _Summary(spend: spend));
            }
            if (i == 1) {
              if (visible.isEmpty) {
                return const EmptyState(
                  icon: Icons.person_search_outlined,
                  message: 'No members match your search.',
                );
              }
              return wide
                  ? const ContentWidth(child: _HeaderRow())
                  : const SizedBox(height: 8);
            }
            final row = visible[i - 2];
            void open() => Navigator.of(context).push(
              MaterialPageRoute<void>(
                builder: (_) => MemberScreen(
                  memberId: row.member.memberId,
                  title: row.member.fullName,
                ),
              ),
            );
            return ContentWidth(
              child: wide
                  ? _WideRow(rank: row.rank, member: row.member, onTap: open)
                  : _NarrowRow(rank: row.rank, member: row.member, onTap: open),
            );
          },
        );
      },
    );
  }
}

class _Summary extends StatelessWidget {
  const _Summary({required this.spend});

  final GroupSpend spend;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final members = spend.members;
    int countAt(SpendBand band) => members
        .where(
          (m) => spendBand(m.spendCents, m.deductibleCents).index >= band.index,
        )
        .length;
    final totalExcess = members.fold<int>(0, (sum, m) => sum + m.excessCents);
    final py = spend.policyYear;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Wrap(
          spacing: 40,
          runSpacing: 12,
          children: [
            Stat(
              label: 'Policy year',
              value: formatDateRange(py.startDate, py.endDate),
            ),
            Stat(
              label: 'Specific deductible',
              value: formatMoney(py.deductibleCents),
            ),
            Stat(label: 'Members', value: formatCount(members.length)),
            Stat(
              label: '≥ 75% used',
              value: formatCount(countAt(SpendBand.high)),
              color: bandColor(SpendBand.high, scheme),
            ),
            Stat(
              label: 'At or over deductible',
              value: formatCount(countAt(SpendBand.over)),
              color: bandColor(SpendBand.over, scheme),
            ),
            Stat(label: 'Total excess', value: formatMoney(totalExcess)),
          ],
        ),
      ),
    );
  }
}

// Column flex shared by the header and the wide rows.
const _flex = (member: 4, money: 2, pct: 3, alerts: 2);

class _HeaderRow extends StatelessWidget {
  const _HeaderRow();

  @override
  Widget build(BuildContext context) {
    final style = Theme.of(context).textTheme.labelMedium
        ?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant);
    Widget cell(String text, int flex, {bool end = false}) => Expanded(
      flex: flex,
      child: Text(
        text,
        style: style,
        textAlign: end ? TextAlign.end : TextAlign.start,
      ),
    );
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
      child: Row(
        children: [
          SizedBox(width: 40, child: Text('#', style: style)),
          cell('Member', _flex.member),
          cell('Spend', _flex.money, end: true),
          cell('Deductible', _flex.money, end: true),
          const SizedBox(width: 24),
          cell('% used', _flex.pct),
          cell('Excess', _flex.money, end: true),
          const SizedBox(width: 24),
          cell('Alerts', _flex.alerts),
          const SizedBox(width: 24),
        ],
      ),
    );
  }
}

class _WideRow extends StatelessWidget {
  const _WideRow({
    required this.rank,
    required this.member,
    required this.onTap,
  });

  final int rank;
  final MemberSpend member;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    const tabular = TextStyle(fontFeatures: [FontFeature.tabularFigures()]);
    Widget money(int cents, {bool muted = false}) => Expanded(
      flex: _flex.money,
      child: Text(
        formatMoney(cents),
        textAlign: TextAlign.end,
        style: tabular.copyWith(color: muted ? scheme.onSurfaceVariant : null),
      ),
    );

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(8),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          decoration: BoxDecoration(
            border: Border(bottom: BorderSide(color: scheme.outlineVariant)),
          ),
          child: Row(
            children: [
              SizedBox(
                width: 40,
                child: Text(
                  '$rank',
                  style: tabular.copyWith(color: scheme.onSurfaceVariant),
                ),
              ),
              Expanded(
                flex: _flex.member,
                child: _MemberLabel(member: member),
              ),
              money(member.spendCents),
              Expanded(
                flex: _flex.money,
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    if (member.hasLaser) ...[
                      const LaserBadge(),
                      const SizedBox(width: 6),
                    ],
                    Flexible(
                      child: Text(
                        formatMoney(member.deductibleCents),
                        style: tabular,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 24),
              Expanded(
                flex: _flex.pct,
                child: SpendBar(
                  spendCents: member.spendCents,
                  deductibleCents: member.deductibleCents,
                  pctUsed: member.pctUsed,
                ),
              ),
              money(member.excessCents, muted: member.excessCents == 0),
              const SizedBox(width: 24),
              Expanded(
                flex: _flex.alerts,
                child: _Thresholds(member.alertThresholds),
              ),
              Icon(Icons.chevron_right, color: scheme.onSurfaceVariant),
            ],
          ),
        ),
      ),
    );
  }
}

class _NarrowRow extends StatelessWidget {
  const _NarrowRow({
    required this.rank,
    required this.member,
    required this.onTap,
  });

  final int rank;
  final MemberSpend member;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Card(
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Text(
                      '#$rank  ',
                      style: TextStyle(color: scheme.onSurfaceVariant),
                    ),
                    Expanded(child: _MemberLabel(member: member)),
                  ],
                ),
                const SizedBox(height: 8),
                SpendBar(
                  spendCents: member.spendCents,
                  deductibleCents: member.deductibleCents,
                  pctUsed: member.pctUsed,
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 12,
                  runSpacing: 6,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    Text(
                      '${formatMoney(member.spendCents)} of '
                      '${formatMoney(member.deductibleCents)}',
                    ),
                    if (member.hasLaser) const LaserBadge(),
                    if (member.excessCents > 0)
                      Text(
                        'Excess ${formatMoney(member.excessCents)}',
                        style: TextStyle(
                          color: scheme.error,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    for (final t in member.alertThresholds) ThresholdTag(t),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _MemberLabel extends StatelessWidget {
  const _MemberLabel({required this.member});

  final MemberSpend member;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          member.fullName,
          style: const TextStyle(fontWeight: FontWeight.w600),
          overflow: TextOverflow.ellipsis,
        ),
        Text(
          '${member.memberId} · ${member.relationship} · '
          '${member.claimCount} ${member.claimCount == 1 ? 'claim' : 'claims'}',
          style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12),
          overflow: TextOverflow.ellipsis,
        ),
      ],
    );
  }
}

class _Thresholds extends StatelessWidget {
  const _Thresholds(this.thresholds);

  final List<int> thresholds;

  @override
  Widget build(BuildContext context) {
    if (thresholds.isEmpty) {
      return Text(
        '–',
        style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant),
      );
    }
    return Wrap(
      spacing: 4,
      runSpacing: 4,
      children: [for (final t in thresholds) ThresholdTag(t)],
    );
  }
}
