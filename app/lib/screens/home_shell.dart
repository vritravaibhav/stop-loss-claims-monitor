import 'package:flutter/material.dart';

import '../app_scope.dart';
import '../upload_controller.dart';
import '../widgets/account_menu.dart';
import '../widgets/common.dart';
import 'alerts_screen.dart';
import 'groups_screen.dart';
import 'upload_history_screen.dart';
import 'upload_screen.dart';

class _TabSpec {
  const _TabSpec(
    this.label,
    this.icon,
    this.build, {
    this.refreshable = true,
    this.showsUploadBadge = false,
  });

  final String label;
  final IconData icon;
  final WidgetBuilder build;

  /// Whether the app bar offers Refresh while this tab is showing.
  final bool refreshable;

  /// Dot on the tab while a batch of files is uploading.
  final bool showsUploadBadge;
}

/// Signed-in layout, the same at every width: one app bar with the title,
/// Refresh, the account menu and a row of top tabs. There is no rail, drawer
/// or side panel; group spend, member and upload details are pushed as full
/// pages over the shell. Upload and History exist only for analysts (the API
/// enforces this too; hiding them is just UX).
class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  /// Below this width the tabs start at the left edge and scroll sideways;
  /// above it they are centred. They are never squeezed, so every label fits.
  static const narrowBreakpoint = 600.0;

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell>
    with SingleTickerProviderStateMixin {
  final _reload = ReloadSignal();
  UploadController? _uploads;
  TabController? _controller;
  List<_TabSpec> _tabs = const [];

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _uploads ??= UploadController(context.api);
    // AuthGate keys the shell by user and role, so the tab set is fixed for
    // the life of this state.
    if (_controller == null) {
      _tabs = _tabsFor(analyst: context.isAnalyst);
      _controller = TabController(length: _tabs.length, vsync: this)
        ..addListener(_onTabChanged);
    }
  }

  @override
  void dispose() {
    _controller?.dispose();
    _uploads?.dispose();
    _reload.dispose();
    super.dispose();
  }

  // The body swaps as soon as a tab is chosen; nothing slides across.
  void _onTabChanged() => setState(() {});

  List<_TabSpec> _tabsFor({required bool analyst}) => [
    _TabSpec(
      'Groups',
      Icons.groups_outlined,
      (_) => GroupsScreen(reloadSignal: _reload),
    ),
    _TabSpec(
      'Alerts',
      Icons.notifications_outlined,
      (_) => AlertsScreen(reloadSignal: _reload),
    ),
    if (analyst) ...[
      _TabSpec(
        'Upload',
        Icons.upload_file_outlined,
        (_) => UploadScreen(controller: _uploads!),
        refreshable: false,
        showsUploadBadge: true,
      ),
      _TabSpec(
        'History',
        Icons.history,
        (_) => UploadHistoryScreen(reloadSignal: _reload),
      ),
    ],
  ];

  Widget _tab(_TabSpec spec) {
    Widget icon = Icon(spec.icon, size: 20);
    if (spec.showsUploadBadge) {
      icon = ListenableBuilder(
        listenable: _uploads!,
        builder: (context, child) =>
            Badge(isLabelVisible: _uploads!.busy, smallSize: 8, child: child),
        child: icon,
      );
    }
    return Tab(
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [icon, const SizedBox(width: 8), Text(spec.label)],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final controller = _controller!;
    final current = _tabs[controller.index];
    final narrow =
        MediaQuery.sizeOf(context).width < HomeShell.narrowBreakpoint;
    final scheme = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            Icon(Icons.shield_outlined, color: scheme.primary),
            const SizedBox(width: 8),
            const Flexible(
              child: Text('Stop-loss monitor', overflow: TextOverflow.ellipsis),
            ),
          ],
        ),
        actions: [
          if (current.refreshable)
            IconButton(
              tooltip: 'Refresh',
              icon: const Icon(Icons.refresh),
              onPressed: _reload.fire,
            ),
          const AccountMenu(),
        ],
        bottom: TabBar(
          controller: controller,
          isScrollable: true,
          tabAlignment: narrow ? TabAlignment.start : TabAlignment.center,
          tabs: [for (final spec in _tabs) _tab(spec)],
        ),
      ),
      // A fresh state per tab visit, so switching tabs shows current data.
      body: KeyedSubtree(
        key: ValueKey(current.label),
        child: Builder(builder: current.build),
      ),
    );
  }
}
