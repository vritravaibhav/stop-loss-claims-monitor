import 'dart:convert';

import 'package:flutter/cupertino.dart' show CupertinoPageTransition;
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stoploss_monitor/app.dart';
import 'package:stoploss_monitor/app_scope.dart';
import 'package:stoploss_monitor/models.dart';
import 'package:stoploss_monitor/screens/group_spend_screen.dart';
import 'package:stoploss_monitor/screens/home_shell.dart';
import 'package:stoploss_monitor/screens/upload_screen.dart';
import 'package:stoploss_monitor/services/api_client.dart';
import 'package:stoploss_monitor/services/auth_service.dart';
import 'package:stoploss_monitor/session.dart';
import 'package:stoploss_monitor/upload_controller.dart';

import 'fakes.dart';

const analystUser = AuthUser(uid: 'a', email: 'analyst@example.test');
const brokerUser = AuthUser(uid: 'b', email: 'broker.a@example.test');

void useSize(WidgetTester tester, Size size) {
  tester.view
    ..physicalSize = size
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
}

/// Pumps the whole app over fakes. [setup] runs before the session starts.
Future<({FakeApi api, FakeAuth auth, Session session})> pumpApp(
  WidgetTester tester, {
  AuthUser? user,
  Profile? profile,
  void Function(FakeApi api)? setup,
  Size size = const Size(1000, 900),
}) async {
  useSize(tester, size);
  final auth = FakeAuth(user);
  final api = FakeApi(profile: profile);
  setup?.call(api);
  final session = Session(auth: auth, api: api)..start();
  addTearDown(session.dispose);
  await tester.pumpWidget(StoplossApp(session: session, api: api));
  await tester.pumpAndSettle();
  return (api: api, auth: auth, session: session);
}

Finder tabLabel(String label) =>
    find.descendant(of: find.byType(TabBar), matching: find.text(label));

/// The top tab labels, left to right.
List<String?> tabLabels(WidgetTester tester) => [
  for (final text in tester.widgetList<Text>(
    find.descendant(of: find.byType(TabBar), matching: find.byType(Text)),
  ))
    text.data,
];

/// No rail, bottom bar, drawer or end drawer anywhere in the tree.
void expectNoSidePanels(WidgetTester tester) {
  expect(find.byType(NavigationRail, skipOffstage: false), findsNothing);
  expect(find.byType(NavigationBar, skipOffstage: false), findsNothing);
  expect(find.byType(Drawer, skipOffstage: false), findsNothing);
  expect(find.byType(NavigationDrawer, skipOffstage: false), findsNothing);
  for (final scaffold in tester.widgetList<Scaffold>(
    find.byType(Scaffold, skipOffstage: false),
  )) {
    expect(scaffold.drawer, isNull);
    expect(scaffold.endDrawer, isNull);
  }
}

void main() {
  group('sign-in and roles', () {
    testWidgets('signed out → login; a demo login signs in as analyst', (
      tester,
    ) async {
      await pumpApp(tester);
      expect(find.widgetWithText(FilledButton, 'Sign in'), findsOneWidget);

      await tester.tap(find.text('analyst@example.test'));
      await tester.pump();
      await tester.tap(find.widgetWithText(FilledButton, 'Sign in'));
      await tester.pumpAndSettle();

      expect(find.text('Group A'), findsOneWidget);
      expect(tabLabels(tester), ['Groups', 'Alerts', 'Upload', 'History']);
    });

    testWidgets('a wrong password shows the error and stays on login', (
      tester,
    ) async {
      await pumpApp(tester);
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Email'),
        'analyst@example.test',
      );
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Password'),
        'wrong',
      );
      await tester.tap(find.widgetWithText(FilledButton, 'Sign in'));
      await tester.pumpAndSettle();

      expect(find.text('Incorrect email or password.'), findsOneWidget);
      expect(find.byType(TabBar), findsNothing);
    });

    testWidgets('broker sees only Groups and Alerts tabs', (tester) async {
      await pumpApp(tester, user: brokerUser, profile: brokerProfile());
      expect(tabLabels(tester), ['Groups', 'Alerts']);
      expect(find.text('Broker · Brokerage A'), findsOneWidget);
      expectNoSidePanels(tester);
    });

    testWidgets('a 403 from /api/me returns to login with a notice', (
      tester,
    ) async {
      final app = await pumpApp(
        tester,
        user: analystUser,
        setup: (api) =>
            api.onMe = () async =>
                throw const ForbiddenException('Unknown user'),
      );
      expect(app.auth.signOutCount, 1);
      expect(find.textContaining('is not set up for this app'), findsOneWidget);
      expect(find.widgetWithText(FilledButton, 'Sign in'), findsOneWidget);
    });

    testWidgets('session expiry closes open dialogs and shows login', (
      tester,
    ) async {
      final app = await pumpApp(tester, user: analystUser);
      await tester.tap(tabLabel('Alerts'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Acknowledge'));
      await tester.pumpAndSettle();
      expect(find.byType(AlertDialog), findsOneWidget);

      app.session.expire();
      await tester.pumpAndSettle();

      expect(find.byType(AlertDialog), findsNothing);
      expect(find.textContaining('session has expired'), findsOneWidget);
    });

    testWidgets('session expiry on a detail page pops it and shows login', (
      tester,
    ) async {
      final app = await pumpApp(tester, user: analystUser);
      await tester.tap(find.text('Group A'));
      await tester.pumpAndSettle();
      expect(find.byType(GroupSpendScreen), findsOneWidget);

      app.session.expire();
      await tester.pumpAndSettle();

      expect(find.byType(GroupSpendScreen, skipOffstage: false), findsNothing);
      expect(find.textContaining('session has expired'), findsOneWidget);
    });
  });

  group('layout: top tabs, no side panels', () {
    for (final width in [360.0, 800.0, 1440.0]) {
      testWidgets('${width.toInt()} px wide: app bar with top tabs only', (
        tester,
      ) async {
        await pumpApp(tester, user: analystUser, size: Size(width, 900));

        expectNoSidePanels(tester);
        final appBar = tester.widget<AppBar>(find.byType(AppBar));
        expect(appBar.bottom, isA<TabBar>());
        expect(
          find.descendant(
            of: find.byType(AppBar),
            matching: find.text('Stop-loss monitor'),
          ),
          findsOneWidget,
        );
        expect(
          find.descendant(
            of: find.byType(AppBar),
            matching: find.byTooltip('Account'),
          ),
          findsOneWidget,
        );

        final bar = tester.widget<TabBar>(find.byType(TabBar));
        // Never squeezed: each label keeps its natural width.
        expect(bar.isScrollable, isTrue);
        expect(
          bar.tabAlignment,
          width < HomeShell.narrowBreakpoint
              ? TabAlignment.start
              : TabAlignment.center,
        );
        expect(tabLabels(tester), ['Groups', 'Alerts', 'Upload', 'History']);

        // The last tab can be reached (scrolled to on a phone) and opened.
        await tester.ensureVisible(tabLabel('History'));
        await tester.pumpAndSettle();
        await tester.tap(tabLabel('History'));
        await tester.pumpAndSettle();
        expect(find.text('GZ_2026-01.csv'), findsOneWidget);
        expectNoSidePanels(tester);
      });
    }

    testWidgets('the tab row scrolls on a phone-width screen', (tester) async {
      await pumpApp(tester, user: analystUser, size: const Size(360, 800));
      final tabsWidth = tester.getSize(find.byType(TabBar)).width;
      final lastRight = tester.getTopRight(tabLabel('History')).dx;
      expect(lastRight, greaterThan(tabsWidth), reason: 'History starts off');
      await tester.drag(find.byType(TabBar), const Offset(-400, 0));
      await tester.pumpAndSettle();
      expect(tester.getTopRight(tabLabel('History')).dx, lessThan(tabsWidth));
    });

    testWidgets(
      'opening a detail page on Apple platforms does not slide it in',
      (tester) async {
        await pumpApp(tester, user: analystUser);
        await tester.tap(find.text('Group A'));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 100)); // mid-transition
        final page = find.byType(GroupSpendScreen);
        expect(page, findsOneWidget);
        expect(
          find.byType(CupertinoPageTransition, skipOffstage: false),
          findsNothing,
        );
        expect(
          find.ancestor(of: page, matching: find.byType(SlideTransition)),
          findsNothing,
        );
        await tester.pumpAndSettle();
      },
      // Flutter web on a Mac or iPhone reports these platforms.
      variant: TargetPlatformVariant.only(TargetPlatform.macOS),
    );

    testWidgets('shell Refresh reloads the visible tab and keeps its filter', (
      tester,
    ) async {
      final app = await pumpApp(tester, user: analystUser);
      await tester.tap(tabLabel('Alerts'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('All'));
      await tester.pumpAndSettle();
      expect(app.api.calls.where((c) => c == 'alerts all -'), hasLength(1));

      await tester.tap(find.byTooltip('Refresh'));
      await tester.pumpAndSettle();
      expect(app.api.calls.where((c) => c == 'alerts all -'), hasLength(2));
      expect(find.text('Ken Ito · A-0007'), findsOneWidget);

      // Upload has nothing to refetch, so no Refresh there.
      await tester.tap(tabLabel('Upload'));
      await tester.pumpAndSettle();
      expect(find.byTooltip('Refresh'), findsNothing);
    });
  });

  group('groups and drill-down', () {
    testWidgets('group → ranked member spend → member, as full pages', (
      tester,
    ) async {
      final app = await pumpApp(tester, user: analystUser);
      await tester.tap(find.text('Group A'));
      await tester.pumpAndSettle();

      expect(find.text('Group A · member spend'), findsOneWidget);
      expect(app.api.calls, contains('groupSpend GA 1'));
      final ranked = ['Priya Shah', 'Ken Ito', 'Ryan Johnson'];
      final tops = [
        for (final name in ranked) tester.getTopLeft(find.text(name).first).dy,
      ];
      expect(tops, orderedEquals([...tops]..sort()));
      // A full page over the shell: its own back button, the tabs covered.
      expect(find.byType(BackButton), findsOneWidget);
      expect(find.byType(TabBar), findsNothing);
      expectNoSidePanels(tester);

      await tester.tap(find.text('Priya Shah').first);
      await tester.pumpAndSettle();
      expect(app.api.calls, contains('member A-0077'));
      expect(find.text('Claim 100159'), findsOneWidget);
      expectNoSidePanels(tester);

      // Back twice returns to the Groups tab.
      await tester.pageBack();
      await tester.pumpAndSettle();
      expect(find.text('Group A · member spend'), findsOneWidget);
      await tester.pageBack();
      await tester.pumpAndSettle();
      expect(find.byType(TabBar), findsOneWidget);
      expect(find.text('Group A'), findsOneWidget);
    });

    testWidgets('a failed load shows an error with retry', (tester) async {
      final app = await pumpApp(
        tester,
        user: analystUser,
        setup: (api) => api.readError = const NetworkException('offline'),
      );
      expect(find.text('Cannot reach the server'), findsOneWidget);

      app.api.readError = null;
      await tester.tap(find.text('Try again'));
      await tester.pumpAndSettle();
      expect(find.text('Group A'), findsOneWidget);
    });
  });

  group('alerts', () {
    testWidgets('analyst acknowledges an open alert; the note is required', (
      tester,
    ) async {
      final app = await pumpApp(tester, user: analystUser);
      await tester.tap(tabLabel('Alerts'));
      await tester.pumpAndSettle();
      expect(find.text('Priya Shah · A-0077'), findsOneWidget);
      // Default filter is open: the acknowledged alert is not listed.
      expect(find.text('Ken Ito · A-0007'), findsNothing);

      await tester.tap(find.text('Acknowledge'));
      await tester.pumpAndSettle();
      final dialogButton = find.descendant(
        of: find.byType(AlertDialog),
        matching: find.text('Acknowledge'),
      );

      await tester.tap(dialogButton);
      await tester.pumpAndSettle();
      expect(
        find.text('A note is required to acknowledge an alert.'),
        findsOneWidget,
      );
      expect(app.api.acknowledged, isEmpty);

      await tester.enterText(
        find.widgetWithText(TextFormField, 'Note'),
        '  Case manager assigned  ',
      );
      await tester.tap(dialogButton);
      await tester.pumpAndSettle();

      expect(app.api.acknowledged, [
        (alertId: '12', note: 'Case manager assigned'),
      ]);
      expect(find.text('Alert acknowledged.'), findsOneWidget);
    });

    testWidgets('a 409 on acknowledge is shown to the analyst', (tester) async {
      await pumpApp(
        tester,
        user: analystUser,
        setup: (api) =>
            api.onAcknowledge = (_, _) async =>
                throw const ConflictException('Alert is not open'),
      );
      await tester.tap(tabLabel('Alerts'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Acknowledge'));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Note'),
        'Reviewed',
      );
      await tester.tap(
        find.descendant(
          of: find.byType(AlertDialog),
          matching: find.text('Acknowledge'),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('Alert is not open'), findsOneWidget);
    });

    testWidgets('brokers see alerts but no Acknowledge button', (tester) async {
      await pumpApp(tester, user: brokerUser, profile: brokerProfile());
      await tester.tap(tabLabel('Alerts'));
      await tester.pumpAndSettle();
      expect(find.text('Priya Shah · A-0077'), findsOneWidget);
      expect(find.text('Acknowledge'), findsNothing);
    });
  });

  group('upload screen', () {
    Future<(FakeApi, UploadController)> pumpUpload(
      WidgetTester tester,
      List<String> picked,
    ) async {
      useSize(tester, const Size(1000, 2400));
      final api = FakeApi()
        ..onUpload = (name, _) async => name.endsWith('_resend.csv')
            ? rejectedResult(name, reason: 'Unknown header row')
            : processedResult(name);
      final controller = UploadController(api);
      addTearDown(controller.dispose);
      final session = Session(auth: FakeAuth(), api: api);
      addTearDown(session.dispose);
      await tester.pumpWidget(
        AppScope(
          api: api,
          session: session,
          child: MaterialApp(
            // A tab body: the shell's Scaffold normally hosts it.
            home: Scaffold(
              body: UploadScreen(
                controller: controller,
                picker: () async => [
                  for (final name in picked)
                    PickedFile(name, utf8.encode('header\r\n')),
                ],
              ),
            ),
          ),
        ),
      );
      return (api, controller);
    }

    testWidgets('picked files are queued and sent in file-name order', (
      tester,
    ) async {
      final (api, _) = await pumpUpload(tester, [
        'GA_2026-04_resend.csv',
        'notes.txt',
        'GA_2026-04.csv',
      ]);

      await tester.tap(find.text('Choose CSV files'));
      await tester.pumpAndSettle();
      expect(find.text('Ignored 1 file that is not .csv.'), findsOneWidget);
      expect(find.text('Upload 2 files'), findsOneWidget);
      expect(
        tester.getTopLeft(find.text('GA_2026-04.csv')).dy,
        lessThan(tester.getTopLeft(find.text('GA_2026-04_resend.csv')).dy),
      );

      await tester.tap(find.text('Upload 2 files'));
      await tester.pumpAndSettle();

      expect(api.uploadedNames, ['GA_2026-04.csv', 'GA_2026-04_resend.csv']);
      expect(find.text('Finished 2 of 2'), findsOneWidget);
      expect(find.textContaining('Processed: 37 applied'), findsOneWidget);
      expect(find.text('Rejected: Unknown header row'), findsOneWidget);
      // The processed file had row errors, so its failed-rows table is open.
      expect(find.textContaining('Unknown member B-0999'), findsOneWidget);
    });

    testWidgets('a transport failure skips the rest and offers a retry', (
      tester,
    ) async {
      final (api, _) = await pumpUpload(tester, [
        'GA_2026-02.csv',
        'GA_2026-01.csv',
      ]);
      var fail = true;
      api.onUpload = (name, _) async {
        if (fail) {
          fail = false;
          throw const ServerException('Internal error', statusCode: 500);
        }
        return processedResult(name);
      };

      await tester.tap(find.text('Choose CSV files'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Upload 2 files'));
      await tester.pumpAndSettle();

      expect(api.uploadedNames, ['GA_2026-01.csv']);
      expect(find.text('Failed: Internal error'), findsOneWidget);
      expect(
        find.textContaining('Not uploaded: an earlier file failed'),
        findsOneWidget,
      );

      await tester.tap(find.text('Retry remaining'));
      await tester.pumpAndSettle();
      expect(api.uploadedNames, [
        'GA_2026-01.csv',
        'GA_2026-01.csv',
        'GA_2026-02.csv',
      ]);
      expect(find.text('Finished 2 of 2'), findsOneWidget);
    });
  });
}
