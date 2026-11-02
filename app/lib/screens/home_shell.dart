import 'package:flutter/material.dart';

import '../app_scope.dart';
import '../upload_controller.dart';
import 'alerts_screen.dart';
import 'groups_screen.dart';
import 'upload_history_screen.dart';
import 'upload_screen.dart';

class _Destination {
  const _Destination(this.label, this.icon, this.selectedIcon, this.build);

  final String label;
  final IconData icon;
  final IconData selectedIcon;
  final WidgetBuilder build;
}

/// Signed-in layout: NavigationRail on wide screens, NavigationBar on narrow
/// ones. Each tab has its own nested Navigator so drill-downs keep the
/// navigation visible. Upload and History exist only for analysts (the API
/// enforces this too; hiding them is just UX).
class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  static const railBreakpoint = 640.0;

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _index = 0;
  GlobalKey<NavigatorState> _navigatorKey = GlobalKey<NavigatorState>();
  UploadController? _uploads;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _uploads ??= UploadController(context.api);
  }

  @override
  void dispose() {
    _uploads?.dispose();
    super.dispose();
  }

  List<_Destination> _destinations(bool analyst) => [
    _Destination(
      'Groups',
      Icons.groups_outlined,
      Icons.groups,
      (_) => const GroupsScreen(),
    ),
    _Destination(
      'Alerts',
      Icons.notifications_outlined,
      Icons.notifications,
      (_) => const AlertsScreen(),
    ),
    if (analyst) ...[
      _Destination(
        'Upload',
        Icons.upload_file_outlined,
        Icons.upload_file,
        (_) => UploadScreen(controller: _uploads!),
      ),
      _Destination(
        'History',
        Icons.history,
        Icons.history,
        (_) => const UploadHistoryScreen(),
      ),
    ],
  ];

  void _select(int index) {
    if (index == _index) {
      // Re-selecting the current tab returns to its first page.
      _navigatorKey.currentState?.popUntil((route) => route.isFirst);
      return;
    }
    setState(() {
      _index = index;
      _navigatorKey = GlobalKey<NavigatorState>();
    });
  }

  Widget _icon(_Destination d, {required bool selected}) {
    final icon = Icon(selected ? d.selectedIcon : d.icon);
    if (d.label != 'Upload') return icon;
    return ListenableBuilder(
      listenable: _uploads!,
      builder: (context, _) =>
          Badge(isLabelVisible: _uploads!.busy, smallSize: 8, child: icon),
    );
  }

  @override
  Widget build(BuildContext context) {
    final destinations = _destinations(context.isAnalyst);
    final index = _index.clamp(0, destinations.length - 1);
    final width = MediaQuery.sizeOf(context).width;
    final wide = width >= HomeShell.railBreakpoint;
    final destination = destinations[index];

    final body = NavigatorPopHandler(
      onPopWithResult: (_) => _navigatorKey.currentState?.maybePop(),
      child: Navigator(
        key: _navigatorKey,
        onGenerateRoute: (settings) => MaterialPageRoute<void>(
          settings: settings,
          builder: destination.build,
        ),
      ),
    );

    if (!wide) {
      return Scaffold(
        body: body,
        bottomNavigationBar: NavigationBar(
          selectedIndex: index,
          onDestinationSelected: _select,
          destinations: [
            for (final d in destinations)
              NavigationDestination(
                icon: _icon(d, selected: false),
                selectedIcon: _icon(d, selected: true),
                label: d.label,
              ),
          ],
        ),
      );
    }

    final extended = width >= 1280;
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      body: Row(
        children: [
          NavigationRail(
            selectedIndex: index,
            onDestinationSelected: _select,
            extended: extended,
            labelType: extended
                ? NavigationRailLabelType.none
                : NavigationRailLabelType.all,
            leading: Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: extended
                  ? Row(
                      children: [
                        Icon(Icons.shield_outlined, color: scheme.primary),
                        const SizedBox(width: 8),
                        Text(
                          'Stop-loss monitor',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                      ],
                    )
                  : Icon(Icons.shield_outlined, color: scheme.primary),
            ),
            destinations: [
              for (final d in destinations)
                NavigationRailDestination(
                  icon: _icon(d, selected: false),
                  selectedIcon: _icon(d, selected: true),
                  label: Text(d.label),
                ),
            ],
          ),
          const VerticalDivider(width: 1),
          Expanded(child: body),
        ],
      ),
    );
  }
}
