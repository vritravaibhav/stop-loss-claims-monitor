import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:stoploss_monitor/models.dart';
import 'package:stoploss_monitor/services/api_client.dart';
import 'package:stoploss_monitor/upload_controller.dart';

import 'fakes.dart';

PickedFile file(String name) => PickedFile(name, utf8.encode('Claim ID\r\n'));

List<String> names(Iterable<PickedFile> files) => [
  for (final f in files) f.name,
];

List<String> itemNames(UploadController c) => [for (final i in c.items) i.name];

List<UploadItemState> states(UploadController c) => [
  for (final i in c.items) i.state,
];

void main() {
  group('sortByName (file-name order = UTF-16 code-unit order)', () {
    test('GA_2026-04.csv is uploaded before GA_2026-04_resend.csv', () {
      // '.' (0x2E) < '_' (0x5F). A locale or natural collation (JS
      // localeCompare, ICU) ignores or reorders punctuation and puts the
      // resend first, which would apply the resend's versions before the
      // original's.
      expect(
        names(
          UploadController.sortByName([
            file('GA_2026-04_resend.csv'),
            file('GA_2026-04.csv'),
          ]),
        ),
        ['GA_2026-04.csv', 'GA_2026-04_resend.csv'],
      );
      expect(
        names(
          UploadController.sortByName([
            file('GA_2026-04.csv'),
            file('GA_2026-04_resend.csv'),
          ]),
        ),
        ['GA_2026-04.csv', 'GA_2026-04_resend.csv'],
      );
    });

    test('orders the sample claim files the way they must be loaded', () {
      const expected = [
        'GA_2026-01.csv',
        'GA_2026-02.csv',
        'GA_2026-03.csv',
        'GA_2026-04.csv',
        'GA_2026-04_resend.csv',
        'GA_2026-05.csv',
        'GA_2026-06.csv',
        'GA_2026-07.csv',
        'GA_2026-08.csv',
        'GA_2026-09.csv',
        'GB_2026-04.csv',
        'GB_2026-05.csv',
        'GB_2026-06.csv',
        'GB_2026-07.csv',
        'GB_2026-08.csv',
        'GB_2026-09.csv',
      ];
      final shuffled = [...expected.reversed]..shuffle();
      expect(names(UploadController.sortByName(shuffled.map(file))), expected);
    });

    test('setFiles queues files sorted, whatever order they were picked', () {
      final controller = UploadController(FakeApi())
        ..setFiles([
          file('GB_2026-04.csv'),
          file('GA_2026-04_resend.csv'),
          file('GA_2026-04.csv'),
        ]);
      expect(itemNames(controller), [
        'GA_2026-04.csv',
        'GA_2026-04_resend.csv',
        'GB_2026-04.csv',
      ]);
      expect(states(controller), everyElement(UploadItemState.queued));
      expect(controller.hasQueued, isTrue);
    });
  });

  group('uploadAll', () {
    test('sends one file at a time, in file-name order', () async {
      final api = FakeApi();
      final pending = <String, Completer<UploadResult>>{};
      api.onUpload = (name, _) => (pending[name] = Completer()).future;
      final controller = UploadController(api)
        ..setFiles([file('GA_2026-04_resend.csv'), file('GA_2026-04.csv')]);

      final run = controller.uploadAll();
      await settle();
      expect(api.uploadedNames, ['GA_2026-04.csv']);
      expect(controller.busy, isTrue);
      expect(states(controller), [
        UploadItemState.uploading,
        UploadItemState.queued,
      ]);

      // A second uploadAll while one is running must not send anything.
      await controller.uploadAll();
      // Nor may the queue be replaced mid-batch.
      controller.setFiles([file('GB_2026-04.csv')]);
      expect(api.uploadedNames, ['GA_2026-04.csv']);
      expect(itemNames(controller), [
        'GA_2026-04.csv',
        'GA_2026-04_resend.csv',
      ]);

      pending['GA_2026-04.csv']!.complete(processedResult('GA_2026-04.csv'));
      await settle();
      expect(api.uploadedNames, ['GA_2026-04.csv', 'GA_2026-04_resend.csv']);
      expect(states(controller), [
        UploadItemState.done,
        UploadItemState.uploading,
      ]);

      pending['GA_2026-04_resend.csv']!.complete(
        processedResult('GA_2026-04_resend.csv'),
      );
      await run;
      expect(controller.busy, isFalse);
      expect(states(controller), everyElement(UploadItemState.done));
      expect(controller.finishedCount, 2);
      expect(controller.items.last.result!.filename, 'GA_2026-04_resend.csv');
    });

    test('a rejected file is a result and does not stop the batch', () async {
      final api = FakeApi()
        ..onUpload = (name, _) async => name.startsWith('GZ')
            ? rejectedResult(name, reason: 'Unknown group GZ')
            : processedResult(name);
      final controller = UploadController(api)
        ..setFiles([file('GZ_2026-01.csv'), file('GA_2026-01.csv')]);

      await controller.uploadAll();

      expect(api.uploadedNames, ['GA_2026-01.csv', 'GZ_2026-01.csv']);
      expect(states(controller), everyElement(UploadItemState.done));
      expect(controller.items[0].result!.isRejected, isFalse);
      expect(controller.items[1].result!.isRejected, isTrue);
      expect(controller.items[1].result!.rejectionReason, 'Unknown group GZ');
    });

    test('a transport failure stops the batch so order is kept; '
        'retryRemaining resumes from the failed file', () async {
      var failNext = true;
      final api = FakeApi()
        ..onUpload = (name, _) async {
          if (name == 'GA_2026-02.csv' && failNext) {
            failNext = false;
            throw const NetworkException('Could not reach the API');
          }
          return processedResult(name);
        };
      final controller = UploadController(api)
        ..setFiles([
          file('GA_2026-03.csv'),
          file('GA_2026-01.csv'),
          file('GA_2026-02.csv'),
        ]);

      await controller.uploadAll();

      expect(api.uploadedNames, ['GA_2026-01.csv', 'GA_2026-02.csv']);
      expect(states(controller), [
        UploadItemState.done,
        UploadItemState.failed,
        UploadItemState.skipped,
      ]);
      expect(controller.items[1].error, isA<NetworkException>());
      expect(controller.items[1].result, isNull);
      expect(controller.finishedCount, 3);
      expect(controller.hasQueued, isFalse);

      await controller.retryRemaining();

      // The first file is not sent again; the other two go in order.
      expect(api.uploadedNames, [
        'GA_2026-01.csv',
        'GA_2026-02.csv',
        'GA_2026-02.csv',
        'GA_2026-03.csv',
      ]);
      expect(states(controller), everyElement(UploadItemState.done));
      expect(controller.items[1].error, isNull);
    });

    test('clear empties the queue, but not while uploading', () async {
      final api = FakeApi();
      final gate = Completer<UploadResult>();
      api.onUpload = (_, _) => gate.future;
      final controller = UploadController(api)
        ..setFiles([file('GA_2026-01.csv')]);

      final run = controller.uploadAll();
      await settle();
      controller.clear();
      expect(controller.items, hasLength(1));

      gate.complete(processedResult('GA_2026-01.csv'));
      await run;
      controller.clear();
      expect(controller.items, isEmpty);
    });
  });
}
