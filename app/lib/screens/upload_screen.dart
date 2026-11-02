import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';

import '../format.dart';
import '../upload_controller.dart';
import '../widgets/common.dart';
import '../widgets/upload_result_view.dart';

/// Opens the browser's file chooser for one or more CSV files and reads them.
typedef FilesPicker = Future<List<PickedFile>> Function();

Future<List<PickedFile>> pickCsvFiles() async {
  final picked = await FilePicker.pickFiles(
    dialogTitle: 'Choose claim files',
    type: FileType.custom,
    allowedExtensions: const ['csv'],
  );
  return [for (final f in picked) PickedFile(f.name, await f.readAsBytes())];
}

/// Analyst-only Upload tab: upload claim files sequentially in file-name
/// order and show each file's result as it comes back. A tab body, not a
/// page: the home shell supplies the app bar.
class UploadScreen extends StatelessWidget {
  const UploadScreen({
    super.key,
    required this.controller,
    this.picker = pickCsvFiles,
  });

  final UploadController controller;
  final FilesPicker picker;

  Future<void> _choose(BuildContext context) async {
    try {
      final files = await picker();
      if (files.isEmpty) return;
      final csv = files
          .where((f) => f.name.toLowerCase().endsWith('.csv'))
          .toList();
      final skipped = files.length - csv.length;
      controller.setFiles(csv);
      if (skipped > 0 && context.mounted) {
        showMessage(
          context,
          'Ignored $skipped ${skipped == 1 ? 'file' : 'files'} that '
          '${skipped == 1 ? 'is' : 'are'} not .csv.',
        );
      }
    } catch (e) {
      if (context.mounted) showError(context, e);
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final items = controller.items;
        final busy = controller.busy;
        final queued = items
            .where((i) => i.state == UploadItemState.queued)
            .length;
        final retryable = items.any(
          (i) =>
              i.state == UploadItemState.failed ||
              i.state == UploadItemState.skipped,
        );

        return ListView(
          padding: const EdgeInsets.all(16),
          children: [
            ContentWidth(
              maxWidth: 1000,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Upload claim files',
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                          const SizedBox(height: 8),
                          const Text(
                            'Choose one or more TPA claim files (.csv). They '
                            'are uploaded one at a time in file-name order; '
                            'each file is processed on its own, and '
                            're-uploading a file changes no totals.',
                          ),
                          const SizedBox(height: 16),
                          Wrap(
                            spacing: 12,
                            runSpacing: 12,
                            children: [
                              OutlinedButton.icon(
                                onPressed: busy ? null : () => _choose(context),
                                icon: const Icon(Icons.folder_open),
                                label: const Text('Choose CSV files'),
                              ),
                              if (queued > 0)
                                FilledButton.icon(
                                  onPressed: busy ? null : controller.uploadAll,
                                  icon: const Icon(Icons.upload),
                                  label: Text(
                                    'Upload $queued '
                                    '${queued == 1 ? 'file' : 'files'}',
                                  ),
                                ),
                              if (retryable && !busy)
                                FilledButton.tonalIcon(
                                  onPressed: controller.retryRemaining,
                                  icon: const Icon(Icons.replay),
                                  label: const Text('Retry remaining'),
                                ),
                              if (items.isNotEmpty && !busy)
                                TextButton(
                                  onPressed: controller.clear,
                                  child: const Text('Clear'),
                                ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                  if (items.isNotEmpty) ...[
                    const SizedBox(height: 16),
                    _Progress(controller: controller),
                    const SizedBox(height: 8),
                    for (final item in items)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 8),
                        child: _UploadItemCard(item: item),
                      ),
                  ],
                ],
              ),
            ),
          ],
        );
      },
    );
  }
}

class _Progress extends StatelessWidget {
  const _Progress({required this.controller});

  final UploadController controller;

  @override
  Widget build(BuildContext context) {
    final total = controller.items.length;
    final finished = controller.finishedCount;
    final current = controller.items
        .where((i) => i.state == UploadItemState.uploading)
        .firstOrNull;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          current != null
              ? 'Uploading ${finished + 1} of $total: ${current.name}'
              : finished == total
              ? 'Finished $total of $total'
              : '$finished of $total done',
        ),
        const SizedBox(height: 6),
        LinearProgressIndicator(value: total == 0 ? 0 : finished / total),
      ],
    );
  }
}

class _UploadItemCard extends StatelessWidget {
  const _UploadItemCard({required this.item});

  final UploadItem item;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final result = item.result;
    final size = item.file.bytes.length;

    final (Widget icon, String status) = switch (item.state) {
      UploadItemState.queued => (
        Icon(Icons.schedule, color: scheme.onSurfaceVariant),
        'Queued · ${_formatSize(size)}',
      ),
      UploadItemState.uploading => (
        const SizedBox.square(
          dimension: 22,
          child: CircularProgressIndicator(strokeWidth: 2.5),
        ),
        'Uploading and processing…',
      ),
      UploadItemState.done when result!.isRejected => (
        Icon(Icons.block, color: scheme.error),
        'Rejected: ${result.rejectionReason ?? 'no reason given'}',
      ),
      UploadItemState.done => (
        Icon(
          result!.counts.errors > 0
              ? Icons.warning_amber_rounded
              : Icons.check_circle,
          color: result.counts.errors > 0
              ? const Color(0xFFD9480F)
              : scheme.primary,
        ),
        _summary(
          result.counts.applied,
          result.counts.errors,
          result.alertsRaised.length,
        ),
      ),
      UploadItemState.failed => (
        Icon(Icons.error, color: scheme.error),
        'Failed: ${errorMessage(item.error!)}',
      ),
      UploadItemState.skipped => (
        Icon(Icons.remove_circle_outline, color: scheme.onSurfaceVariant),
        'Not uploaded: an earlier file failed, so the order was kept',
      ),
    };

    if (result == null) {
      return Card(
        child: ListTile(
          leading: icon,
          title: Text(item.name),
          subtitle: Text(status),
        ),
      );
    }
    return Card(
      clipBehavior: Clip.antiAlias,
      child: ExpansionTile(
        // Rebuilt per state so a fresh result opens when it needs attention.
        key: ValueKey('${item.name}/${item.state}'),
        shape: const Border(),
        collapsedShape: const Border(),
        initiallyExpanded:
            result.isRejected ||
            result.counts.errors > 0 ||
            result.alertsRaised.isNotEmpty,
        leading: icon,
        title: Text(item.name),
        subtitle: Text(status),
        childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        children: [UploadResultView(result: result)],
      ),
    );
  }

  static String _summary(int applied, int errors, int alerts) => [
    'Processed: ${formatCount(applied)} applied',
    '${formatCount(errors)} ${errors == 1 ? 'error' : 'errors'}',
    '$alerts ${alerts == 1 ? 'alert' : 'alerts'} raised',
  ].join(' · ');

  static String _formatSize(int bytes) => bytes < 1024
      ? '$bytes B'
      : bytes < 1024 * 1024
      ? '${(bytes / 1024).toStringAsFixed(1)} KB'
      : '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
}
