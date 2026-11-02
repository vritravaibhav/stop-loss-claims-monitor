import 'package:flutter/material.dart';

import '../app_scope.dart';
import '../format.dart';
import '../models.dart';
import '../widgets/common.dart';
import 'group_spend_screen.dart';

/// Groups tab: one card per group visible to the user. A tab body, not a
/// page: the home shell supplies the app bar and its Refresh button.
class GroupsScreen extends StatefulWidget {
  const GroupsScreen({super.key, this.reloadSignal});

  /// Fired by the shell's Refresh button.
  final Listenable? reloadSignal;

  @override
  State<GroupsScreen> createState() => _GroupsScreenState();
}

class _GroupsScreenState extends State<GroupsScreen>
    with ReloadOnSignal<GroupsScreen> {
  Future<List<Group>>? _future;

  @override
  Listenable? get reloadSignal => widget.reloadSignal;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _future ??= context.api.groups();
  }

  @override
  void reload() => setState(() {
    _future = context.api.groups();
  });

  @override
  Widget build(BuildContext context) => AsyncView<List<Group>>(
    future: _future,
    onRetry: reload,
    builder: (context, groups) {
      if (groups.isEmpty) {
        return const EmptyState(
          icon: Icons.groups_outlined,
          message: 'No groups are visible to you.',
        );
      }
      return ListView.separated(
        padding: const EdgeInsets.all(16),
        itemCount: groups.length,
        separatorBuilder: (_, _) => const SizedBox(height: 12),
        itemBuilder: (context, i) =>
            ContentWidth(maxWidth: 1000, child: GroupCard(group: groups[i])),
      );
    },
  );
}

class GroupCard extends StatelessWidget {
  const GroupCard({super.key, required this.group});

  final Group group;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final current = group.currentPolicyYear;

    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => Navigator.of(context).push(
          MaterialPageRoute<void>(
            builder: (_) => GroupSpendScreen(group: group),
          ),
        ),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(group.name, style: theme.textTheme.titleMedium),
                        Text(
                          '${group.id} · ${group.brokerage?.name ?? 'No brokerage'}',
                          style: TextStyle(color: scheme.onSurfaceVariant),
                        ),
                      ],
                    ),
                  ),
                  if (group.openAlertCount > 0)
                    Tag(
                      '${group.openAlertCount} open '
                      '${group.openAlertCount == 1 ? 'alert' : 'alerts'}',
                      icon: Icons.notifications_active_outlined,
                      color: scheme.error,
                    )
                  else
                    const Tag('No open alerts'),
                  const SizedBox(width: 8),
                  Icon(Icons.chevron_right, color: scheme.onSurfaceVariant),
                ],
              ),
              const SizedBox(height: 16),
              Wrap(
                spacing: 40,
                runSpacing: 12,
                children: [
                  Stat(label: 'Members', value: formatCount(group.memberCount)),
                  Stat(
                    label: 'Current policy year',
                    value: current == null
                        ? 'None'
                        : formatDateRange(current.startDate, current.endDate),
                  ),
                  Stat(
                    label: 'Specific deductible',
                    value: current == null
                        ? '–'
                        : formatMoney(current.deductibleCents),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
