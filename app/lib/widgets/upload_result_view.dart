import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../format.dart';
import '../models.dart';
import 'common.dart';

/// Everything the API reports about one processed or rejected file: counts,
/// rejection reason, failed rows and alerts raised.
class UploadResultView extends StatelessWidget {
  const UploadResultView({super.key, required this.result});

  final UploadResult result;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final c = result.counts;

    return SelectionArea(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (result.isRejected)
            Container(
              padding: const EdgeInsets.all(12),
              margin: const EdgeInsets.only(bottom: 12),
              decoration: BoxDecoration(
                color: scheme.errorContainer,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Row(
                children: [
                  Icon(Icons.block, color: scheme.onErrorContainer),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      'File rejected: ${result.rejectionReason ?? 'no reason given'}',
                      style: TextStyle(color: scheme.onErrorContainer),
                    ),
                  ),
                ],
              ),
            ),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _Count('Rows', c.rows),
              _Count('Applied', c.applied, color: scheme.primary),
              _Count('Unchanged', c.unchanged),
              _Count('Older ignored', c.olderIgnored),
              _Count('Summary rows', c.summary),
              _Count(
                'Errors',
                c.errors,
                color: c.errors > 0 ? scheme.error : null,
              ),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            [
              'Group ${result.groupId ?? '–'}',
              'layout ${result.layout ?? '–'}',
              'uploaded by ${result.uploadedBy}',
              formatDateTime(result.uploadedAt),
              if (result.sha256 != null)
                'sha256 ${result.sha256!.substring(0, math.min(12, result.sha256!.length))}…',
            ].join(' · '),
            style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12),
          ),
          if (result.alertsRaised.isNotEmpty) ...[
            const SizedBox(height: 16),
            Text(
              'Alerts raised (${result.alertsRaised.length})',
              style: theme.textTheme.titleSmall,
            ),
            const SizedBox(height: 8),
            for (final a in result.alertsRaised)
              Padding(
                padding: const EdgeInsets.only(bottom: 6),
                child: Wrap(
                  spacing: 8,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    ThresholdTag(a.threshold),
                    Text(
                      '${a.memberId}: ${formatMoney(a.spendCents)} of '
                      '${formatMoney(a.deductibleCents)} '
                      '(${formatPct(a.pctUsed)})',
                    ),
                  ],
                ),
              ),
          ],
          if (result.errors.isNotEmpty) ...[
            const SizedBox(height: 16),
            Text(
              'Failed rows (${result.errors.length})',
              style: theme.textTheme.titleSmall,
            ),
            const SizedBox(height: 8),
            RowErrorTable(errors: result.errors),
          ] else if (c.errors > 0) ...[
            const SizedBox(height: 12),
            Text(
              '${c.errors} rows failed; open the upload for details.',
              style: TextStyle(color: scheme.error),
            ),
          ],
        ],
      ),
    );
  }
}

class _Count extends StatelessWidget {
  const _Count(this.label, this.value, {this.color});

  final String label;
  final int value;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final fg = color ?? scheme.onSurface;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: scheme.outlineVariant),
      ),
      child: Text.rich(
        TextSpan(
          children: [
            TextSpan(
              text: formatCount(value),
              style: TextStyle(fontWeight: FontWeight.w700, color: fg),
            ),
            TextSpan(
              text: ' $label',
              style: TextStyle(color: scheme.onSurfaceVariant),
            ),
          ],
        ),
      ),
    );
  }
}

/// Row number, reason and raw line of every row the API skipped. Paginated so
/// a file with thousands of bad rows stays responsive.
class RowErrorTable extends StatefulWidget {
  const RowErrorTable({super.key, required this.errors});

  final List<RowError> errors;

  @override
  State<RowErrorTable> createState() => _RowErrorTableState();
}

class _RowErrorTableState extends State<RowErrorTable> {
  static const _pageSizes = [10, 25, 50, 100];
  int _rowsPerPage = 10;

  @override
  Widget build(BuildContext context) {
    final errors = widget.errors;
    final paged = errors.length > _pageSizes.first;
    return PaginatedDataTable(
      columns: const [
        DataColumn(label: Text('Row'), numeric: true),
        DataColumn(label: Text('Reason')),
        DataColumn(label: Text('Raw line')),
      ],
      source: _RowErrorSource(errors),
      rowsPerPage: paged ? _rowsPerPage : errors.length,
      availableRowsPerPage: _pageSizes,
      onRowsPerPageChanged: paged
          ? (n) => setState(() => _rowsPerPage = n ?? _rowsPerPage)
          : null,
      showEmptyRows: false,
      showFirstLastButtons: errors.length > 50,
      horizontalMargin: 16,
      columnSpacing: 24,
      dataRowMinHeight: 40,
      dataRowMaxHeight: 64,
    );
  }
}

class _RowErrorSource extends DataTableSource {
  _RowErrorSource(this.errors);

  final List<RowError> errors;

  @override
  DataRow? getRow(int index) {
    if (index >= errors.length) return null;
    final e = errors[index];
    return DataRow(
      cells: [
        DataCell(Text('${e.rowNumber}')),
        DataCell(
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 320),
            child: Text(e.reason),
          ),
        ),
        DataCell(
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 480),
            child: Text(
              e.raw,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
            ),
          ),
        ),
      ],
    );
  }

  @override
  bool get isRowCountApproximate => false;

  @override
  int get rowCount => errors.length;

  @override
  int get selectedRowCount => 0;
}
