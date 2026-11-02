import 'package:flutter/material.dart';

import '../app_scope.dart';
import '../format.dart';
import '../models.dart';
import '../widgets/account_menu.dart';
import '../widgets/common.dart';
import '../widgets/upload_result_view.dart';

/// Analyst-only History tab: every upload, newest first (audit of who loaded
/// what). A tab body, not a page: the home shell supplies the app bar and its
/// Refresh button. Each upload opens as a full page.
class UploadHistoryScreen extends StatefulWidget {
  const UploadHistoryScreen({super.key, this.reloadSignal});

  /// Fired by the shell's Refresh button.
  final Listenable? reloadSignal;

  @override
  State<UploadHistoryScreen> createState() => _UploadHistoryScreenState();
}

class _UploadHistoryScreenState extends State<UploadHistoryScreen>
    with ReloadOnSignal<UploadHistoryScreen> {
  Future<List<UploadResult>>? _future;

  @override
  Listenable? get reloadSignal => widget.reloadSignal;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _future ??= context.api.uploads();
  }

  @override
  void reload() => setState(() {
    _future = context.api.uploads();
  });

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return AsyncView<List<UploadResult>>(
      future: _future,
      onRetry: reload,
      builder: (context, uploads) {
        if (uploads.isEmpty) {
          return const EmptyState(
            icon: Icons.history,
            message: 'No files have been uploaded yet.',
          );
        }
        return ListView(
          padding: const EdgeInsets.all(16),
          children: [
            ContentWidth(
              maxWidth: 1000,
              child: Card(
                clipBehavior: Clip.antiAlias,
                child: Column(
                  children: [
                    for (var i = 0; i < uploads.length; i++) ...[
                      if (i > 0) const Divider(),
                      _UploadTile(upload: uploads[i], scheme: scheme),
                    ],
                  ],
                ),
              ),
            ),
          ],
        );
      },
    );
  }
}

class _UploadTile extends StatelessWidget {
  const _UploadTile({required this.upload, required this.scheme});

  final UploadResult upload;
  final ColorScheme scheme;

  @override
  Widget build(BuildContext context) {
    final c = upload.counts;
    final rejected = upload.isRejected;
    return ListTile(
      leading: Icon(
        rejected
            ? Icons.block
            : c.errors > 0
            ? Icons.warning_amber_rounded
            : Icons.check_circle_outline,
        color: rejected
            ? scheme.error
            : c.errors > 0
            ? const Color(0xFFD9480F)
            : scheme.primary,
      ),
      title: Text(upload.filename),
      subtitle: Text(
        [
          upload.groupId ?? '–',
          upload.uploadedBy,
          formatDateTime(upload.uploadedAt),
        ].join(' · '),
      ),
      trailing: Text(
        rejected
            ? 'Rejected'
            : '${formatCount(c.applied)} applied · '
                  '${formatCount(c.errors)} errors',
        style: TextStyle(color: rejected ? scheme.error : null),
      ),
      onTap: () => Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => UploadDetailScreen(
            uploadId: upload.id,
            filename: upload.filename,
          ),
        ),
      ),
    );
  }
}

class UploadDetailScreen extends StatefulWidget {
  const UploadDetailScreen({
    super.key,
    required this.uploadId,
    required this.filename,
  });

  final String uploadId;
  final String filename;

  @override
  State<UploadDetailScreen> createState() => _UploadDetailScreenState();
}

class _UploadDetailScreenState extends State<UploadDetailScreen> {
  Future<UploadResult>? _future;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _future ??= context.api.upload(widget.uploadId);
  }

  void _reload() => setState(() {
    _future = context.api.upload(widget.uploadId);
  });

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(widget.filename),
      actions: const [AccountMenu()],
    ),
    body: AsyncView<UploadResult>(
      future: _future,
      onRetry: _reload,
      builder: (context, result) => ListView(
        padding: const EdgeInsets.all(16),
        children: [
          ContentWidth(
            maxWidth: 1000,
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: UploadResultView(result: result),
              ),
            ),
          ),
        ],
      ),
    ),
  );
}
