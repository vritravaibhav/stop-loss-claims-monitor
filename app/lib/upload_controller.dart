import 'package:flutter/foundation.dart';

import 'models.dart';
import 'services/api_client.dart';

/// A file chosen in the picker, already read into memory.
class PickedFile {
  const PickedFile(this.name, this.bytes);

  final String name;
  final Uint8List bytes;
}

enum UploadItemState {
  queued,
  uploading,

  /// The API answered with an UploadResult (processed or rejected).
  done,

  /// The request itself failed (network, 5xx, 403...). No result.
  failed,

  /// Not sent because an earlier file failed; keeps file-name order intact.
  skipped,
}

class UploadItem {
  UploadItem(this.file);

  final PickedFile file;
  UploadItemState state = UploadItemState.queued;
  UploadResult? result;
  Object? error;

  String get name => file.name;
}

/// Uploads claim files one at a time, in file-name order.
///
/// Order matters: later files can carry newer claim versions, alerts are
/// evaluated at the end of each file, and on a conflict the first-loaded
/// values win. So files are never sent in parallel, and if one fails at the
/// transport level the rest are skipped rather than sent out of order. A
/// *rejected* file is a normal result and does not stop the batch.
///
/// Owned by the home shell so an upload keeps going when the user switches
/// tabs.
class UploadController extends ChangeNotifier {
  UploadController(this._api);

  final ApiClient _api;
  List<UploadItem> _items = const [];
  bool _busy = false;
  bool _disposed = false;

  List<UploadItem> get items => _items;
  bool get busy => _busy;
  bool get hasQueued => _items.any((i) => i.state == UploadItemState.queued);
  int get finishedCount => _items
      .where(
        (i) =>
            i.state == UploadItemState.done ||
            i.state == UploadItemState.failed ||
            i.state == UploadItemState.skipped,
      )
      .length;

  /// File-name order, by UTF-16 code unit (same as a plain `sort` in JS), so
  /// `GA_2026-04.csv` goes before `GA_2026-04_resend.csv`.
  static List<PickedFile> sortByName(Iterable<PickedFile> files) =>
      files.toList()..sort((a, b) => a.name.compareTo(b.name));

  /// Replaces the queue. Ignored while an upload is running.
  void setFiles(Iterable<PickedFile> files) {
    if (_busy) return;
    _items = [for (final f in sortByName(files)) UploadItem(f)];
    _notify();
  }

  /// Re-queues the file that failed and everything skipped after it.
  Future<void> retryRemaining() {
    if (_busy) return Future.value();
    for (final item in _items) {
      if (item.state == UploadItemState.failed ||
          item.state == UploadItemState.skipped) {
        item
          ..state = UploadItemState.queued
          ..error = null;
      }
    }
    return uploadAll();
  }

  void clear() {
    if (_busy) return;
    _items = const [];
    _notify();
  }

  Future<void> uploadAll() async {
    if (_busy) return;
    _busy = true;
    _notify();
    try {
      var stop = false;
      for (final item in _items) {
        if (item.state != UploadItemState.queued) continue;
        if (stop) {
          item.state = UploadItemState.skipped;
          continue;
        }
        item.state = UploadItemState.uploading;
        _notify();
        try {
          item.result = await _api.uploadFile(item.name, item.file.bytes);
          item.state = UploadItemState.done;
        } catch (e) {
          item.error = e;
          item.state = UploadItemState.failed;
          stop = true;
        }
        _notify();
      }
    } finally {
      _busy = false;
      _notify();
    }
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
