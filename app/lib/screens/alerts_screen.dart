import 'package:flutter/material.dart';

import '../app_scope.dart';
import '../format.dart';
import '../models.dart';
import '../services/api_client.dart';
import '../widgets/account_menu.dart';
import '../widgets/common.dart';
import 'member_screen.dart';

class AlertsScreen extends StatefulWidget {
  const AlertsScreen({super.key});

  @override
  State<AlertsScreen> createState() => _AlertsScreenState();
}

class _AlertsScreenState extends State<AlertsScreen> {
  AlertFilter _filter = AlertFilter.open;
  String? _groupId;
  Future<List<Alert>>? _future;
  Future<List<Group>>? _groups;
  final Set<String> _acknowledging = {};

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _future ??= _fetch();
    _groups ??= context.api.groups();
  }

  Future<List<Alert>> _fetch() =>
      context.api.alerts(status: _filter, groupId: _groupId);

  void _reload() => setState(() {
    _future = _fetch();
  });

  Future<void> _acknowledge(Alert alert) async {
    final note = await showDialog<String>(
      context: context,
      builder: (_) => AcknowledgeDialog(alert: alert),
    );
    if (note == null || !mounted) return;

    setState(() => _acknowledging.add(alert.id));
    try {
      await context.api.acknowledgeAlert(alert.id, note);
      if (mounted) showMessage(context, 'Alert acknowledged.');
    } catch (e) {
      // e.g. 409 when someone else acknowledged it first.
      if (mounted) showError(context, e);
    } finally {
      if (mounted) {
        setState(() => _acknowledging.remove(alert.id));
        _reload();
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final analyst = context.isAnalyst;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Alerts'),
        actions: [
          IconButton(
            tooltip: 'Refresh',
            icon: const Icon(Icons.refresh),
            onPressed: _reload,
          ),
          const AccountMenu(),
        ],
      ),
      body: Column(
        children: [
          ContentWidth(
            maxWidth: 1000,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
              child: Wrap(
                spacing: 16,
                runSpacing: 12,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  SegmentedButton<AlertFilter>(
                    segments: const [
                      ButtonSegment(
                        value: AlertFilter.open,
                        label: Text('Open'),
                      ),
                      ButtonSegment(
                        value: AlertFilter.acknowledged,
                        label: Text('Acknowledged'),
                      ),
                      ButtonSegment(value: AlertFilter.all, label: Text('All')),
                    ],
                    selected: {_filter},
                    onSelectionChanged: (s) {
                      _filter = s.single;
                      _reload();
                    },
                  ),
                  FutureBuilder<List<Group>>(
                    future: _groups,
                    builder: (context, snapshot) => SizedBox(
                      width: 240,
                      child: DropdownButtonFormField<String?>(
                        initialValue: _groupId,
                        isExpanded: true,
                        decoration: const InputDecoration(
                          labelText: 'Group',
                          isDense: true,
                        ),
                        items: [
                          const DropdownMenuItem(
                            value: null,
                            child: Text('All groups'),
                          ),
                          for (final g in snapshot.data ?? const <Group>[])
                            DropdownMenuItem(
                              value: g.id,
                              child: Text(
                                '${g.name} (${g.id})',
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                        ],
                        onChanged: (id) {
                          _groupId = id;
                          _reload();
                        },
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
          Expanded(
            child: AsyncView<List<Alert>>(
              future: _future,
              onRetry: _reload,
              builder: (context, alerts) {
                if (alerts.isEmpty) {
                  return EmptyState(
                    icon: Icons.notifications_none,
                    message: switch (_filter) {
                      AlertFilter.open => 'No open alerts.',
                      AlertFilter.acknowledged => 'No acknowledged alerts.',
                      AlertFilter.all => 'No alerts yet.',
                    },
                  );
                }
                return ListView.separated(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                  itemCount: alerts.length,
                  separatorBuilder: (_, _) => const SizedBox(height: 12),
                  itemBuilder: (context, i) {
                    final alert = alerts[i];
                    return ContentWidth(
                      maxWidth: 1000,
                      child: AlertCard(
                        alert: alert,
                        busy: _acknowledging.contains(alert.id),
                        onAcknowledge: analyst && alert.isOpen
                            ? () => _acknowledge(alert)
                            : null,
                      ),
                    );
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class AlertCard extends StatelessWidget {
  const AlertCard({
    super.key,
    required this.alert,
    this.onAcknowledge,
    this.busy = false,
  });

  final Alert alert;

  /// Null hides the button (brokers, or the alert is not open).
  final VoidCallback? onAcknowledge;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                ThresholdTag(alert.threshold),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      InkWell(
                        onTap: () => Navigator.of(context).push(
                          MaterialPageRoute<void>(
                            builder: (_) => MemberScreen(
                              memberId: alert.memberId,
                              title: alert.memberName,
                            ),
                          ),
                        ),
                        child: Text(
                          '${alert.memberName} · ${alert.memberId}',
                          style: theme.textTheme.titleMedium?.copyWith(
                            color: scheme.primary,
                          ),
                        ),
                      ),
                      Text(
                        '${alert.groupName} · policy year '
                        '${formatDateRange(alert.policyYearStart, alert.policyYearEnd)}',
                        style: TextStyle(color: scheme.onSurfaceVariant),
                      ),
                    ],
                  ),
                ),
                alert.isOpen
                    ? Tag('Open', color: scheme.error)
                    : Tag('Acknowledged', color: scheme.primary),
              ],
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 32,
              runSpacing: 12,
              children: [
                Stat(
                  label: 'Spend at trigger',
                  value:
                      '${formatMoney(alert.spendAtTriggerCents)} of '
                      '${formatMoney(alert.deductibleAtTriggerCents)}',
                ),
                Stat(
                  label: 'Current spend',
                  value:
                      '${formatMoney(alert.currentSpendCents)} '
                      '(${formatPct(alert.currentPctUsed)})',
                ),
                Stat(label: 'Raised', value: formatDateTime(alert.createdAt)),
                Stat(label: 'From file', value: alert.uploadFilename ?? '–'),
              ],
            ),
            if (!alert.isOpen) ...[
              const SizedBox(height: 12),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: scheme.surfaceContainerLow,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Acknowledged by ${alert.acknowledgedBy ?? 'unknown'}'
                      '${alert.acknowledgedAt == null ? '' : ' on ${formatDateTime(alert.acknowledgedAt!)}'}',
                      style: theme.textTheme.labelMedium,
                    ),
                    if ((alert.note ?? '').isNotEmpty) ...[
                      const SizedBox(height: 4),
                      Text(alert.note!),
                    ],
                  ],
                ),
              ),
            ],
            if (onAcknowledge != null) ...[
              const SizedBox(height: 12),
              Align(
                alignment: Alignment.centerRight,
                child: FilledButton.tonalIcon(
                  onPressed: busy ? null : onAcknowledge,
                  icon: busy
                      ? const SizedBox.square(
                          dimension: 16,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.check),
                  label: const Text('Acknowledge'),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// Asks for the (required) acknowledgement note. Pops with the trimmed note,
/// or null on cancel. The API also rejects an empty note with 400.
class AcknowledgeDialog extends StatefulWidget {
  const AcknowledgeDialog({super.key, required this.alert});

  final Alert alert;

  @override
  State<AcknowledgeDialog> createState() => _AcknowledgeDialogState();
}

class _AcknowledgeDialogState extends State<AcknowledgeDialog> {
  final _formKey = GlobalKey<FormState>();
  final _note = TextEditingController();

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  void _submit() {
    if (!_formKey.currentState!.validate()) return;
    Navigator.of(context).pop(_note.text.trim());
  }

  @override
  Widget build(BuildContext context) {
    final alert = widget.alert;
    return AlertDialog(
      title: const Text('Acknowledge alert'),
      content: SizedBox(
        width: 420,
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '${alert.memberName} (${alert.memberId}) reached '
                '${alert.threshold}% of their deductible.',
              ),
              const SizedBox(height: 16),
              TextFormField(
                controller: _note,
                autofocus: true,
                minLines: 2,
                maxLines: 4,
                decoration: const InputDecoration(
                  labelText: 'Note',
                  hintText: 'e.g. Case manager assigned',
                ),
                validator: (v) => (v ?? '').trim().isEmpty
                    ? 'A note is required to acknowledge an alert.'
                    : null,
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(onPressed: _submit, child: const Text('Acknowledge')),
      ],
    );
  }
}
