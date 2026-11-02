import 'package:flutter/material.dart';

import '../app_scope.dart';
import '../models.dart';

String roleLabel(Profile profile) => switch (profile.role) {
  Role.analyst => 'Analyst',
  Role.broker =>
    profile.brokerage == null
        ? 'Broker'
        : 'Broker · ${profile.brokerage!.name}',
};

/// App bar action: who is signed in, their role, and sign-out.
class AccountMenu extends StatelessWidget {
  const AccountMenu({super.key});

  @override
  Widget build(BuildContext context) {
    final session = AppScope.of(context).session;
    final profile = session.profile;
    if (profile == null) return const SizedBox.shrink();
    final wide = MediaQuery.sizeOf(context).width >= 720;
    final scheme = Theme.of(context).colorScheme;

    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (wide)
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: Text(
                roleLabel(profile),
                style: TextStyle(color: scheme.onSurfaceVariant),
              ),
            ),
          PopupMenuButton<void>(
            tooltip: 'Account',
            itemBuilder: (context) => [
              PopupMenuItem<void>(
                enabled: false,
                child: ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text(profile.email),
                  subtitle: Text(roleLabel(profile)),
                ),
              ),
              const PopupMenuDivider(),
              PopupMenuItem<void>(
                onTap: session.signOut,
                child: const ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: Icon(Icons.logout),
                  title: Text('Sign out'),
                ),
              ),
            ],
            child: CircleAvatar(
              radius: 16,
              backgroundColor: scheme.primaryContainer,
              child: Text(
                profile.email.isEmpty ? '?' : profile.email[0].toUpperCase(),
                style: TextStyle(color: scheme.onPrimaryContainer),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
