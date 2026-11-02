import 'package:flutter/material.dart';

import '../format.dart';
import '../services/api_client.dart';
import '../services/auth_service.dart';
import '../theme.dart';

/// A message for any error the app can hit, safe to show the user.
String errorMessage(Object error) => switch (error) {
  ApiException(:final message) => message,
  AuthFailure(:final message) => message,
  _ => 'Something went wrong: $error',
};

/// Shows an API (or other) error in a SnackBar. 401s are skipped: the user is
/// already being sent back to the login screen with a notice.
void showError(BuildContext context, Object error) {
  if (error is UnauthorizedException) return;
  final messenger = ScaffoldMessenger.of(context);
  messenger.hideCurrentSnackBar();
  messenger.showSnackBar(
    SnackBar(
      content: Text(errorMessage(error)),
      backgroundColor: Theme.of(context).colorScheme.error,
    ),
  );
}

void showMessage(BuildContext context, String message) {
  final messenger = ScaffoldMessenger.of(context);
  messenger.hideCurrentSnackBar();
  messenger.showSnackBar(SnackBar(content: Text(message)));
}

/// [FutureBuilder] with the app's loading and error states. While a reload is
/// in flight the previous data stays visible under a thin progress bar.
class AsyncView<T> extends StatelessWidget {
  const AsyncView({
    super.key,
    required this.future,
    required this.onRetry,
    required this.builder,
  });

  final Future<T>? future;
  final VoidCallback onRetry;
  final Widget Function(BuildContext context, T data) builder;

  @override
  Widget build(BuildContext context) => FutureBuilder<T>(
    future: future,
    builder: (context, snapshot) {
      final loading = snapshot.connectionState == ConnectionState.waiting;
      if (snapshot.hasData) {
        final child = builder(context, snapshot.data as T);
        if (!loading) return child;
        return Stack(
          children: [
            child,
            const Positioned(
              top: 0,
              left: 0,
              right: 0,
              child: LinearProgressIndicator(),
            ),
          ],
        );
      }
      if (snapshot.hasError && !loading) {
        return ErrorPanel(error: snapshot.error!, onRetry: onRetry);
      }
      return const Center(child: CircularProgressIndicator());
    },
  );
}

class ErrorPanel extends StatelessWidget {
  const ErrorPanel({super.key, required this.error, this.onRetry});

  final Object error;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final title = switch (error) {
      NotFoundException() => 'Not found',
      ForbiddenException() => 'Not allowed',
      NetworkException() => 'Cannot reach the server',
      _ => 'Could not load this page',
    };
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 480),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                Icons.error_outline,
                size: 40,
                color: theme.colorScheme.error,
              ),
              const SizedBox(height: 12),
              Text(title, style: theme.textTheme.titleMedium),
              const SizedBox(height: 4),
              Text(errorMessage(error), textAlign: TextAlign.center),
              if (onRetry != null) ...[
                const SizedBox(height: 16),
                OutlinedButton.icon(
                  onPressed: onRetry,
                  icon: const Icon(Icons.refresh),
                  label: const Text('Try again'),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class EmptyState extends StatelessWidget {
  const EmptyState({super.key, required this.icon, required this.message});

  final IconData icon;
  final String message;

  @override
  Widget build(BuildContext context) {
    final color = Theme.of(context).colorScheme.onSurfaceVariant;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 40, color: color),
            const SizedBox(height: 12),
            Text(
              message,
              textAlign: TextAlign.center,
              style: TextStyle(color: color),
            ),
          ],
        ),
      ),
    );
  }
}

/// Centres page content and caps its width on large screens.
class ContentWidth extends StatelessWidget {
  const ContentWidth({super.key, required this.child, this.maxWidth = 1200});

  final Widget child;
  final double maxWidth;

  @override
  Widget build(BuildContext context) => Align(
    alignment: Alignment.topCenter,
    child: ConstrainedBox(
      constraints: BoxConstraints(maxWidth: maxWidth),
      child: child,
    ),
  );
}

/// Small rounded label, e.g. "Laser", "Reversed", "75%".
class Tag extends StatelessWidget {
  const Tag(this.label, {super.key, this.color, this.icon, this.tooltip});

  final String label;
  final Color? color;
  final IconData? icon;
  final String? tooltip;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final fg = color ?? scheme.onSurfaceVariant;
    final tag = Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: fg.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: fg.withValues(alpha: 0.4)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 14, color: fg),
            const SizedBox(width: 4),
          ],
          Text(
            label,
            style: Theme.of(context).textTheme.labelSmall
                ?.copyWith(color: fg, fontWeight: FontWeight.w600),
          ),
        ],
      ),
    );
    return tooltip == null ? tag : Tooltip(message: tooltip, child: tag);
  }
}

class LaserBadge extends StatelessWidget {
  const LaserBadge({super.key});

  @override
  Widget build(BuildContext context) => Tag(
    'Laser',
    icon: Icons.bolt,
    color: Theme.of(context).colorScheme.tertiary,
    tooltip: 'Member-specific deductible for this policy year',
  );
}

/// "50%" / "75%" / "100%" chip coloured like the spend band it marks.
class ThresholdTag extends StatelessWidget {
  const ThresholdTag(this.threshold, {super.key});

  final int threshold;

  @override
  Widget build(BuildContext context) => Tag(
    '$threshold%',
    icon: Icons.notifications_active_outlined,
    color: bandColor(
      bandForThreshold(threshold),
      Theme.of(context).colorScheme,
    ),
    tooltip: 'Alert raised at $threshold%',
  );
}

/// Progress bar of spend against deductible, coloured by band, with the %.
class SpendBar extends StatelessWidget {
  const SpendBar({
    super.key,
    required this.spendCents,
    required this.deductibleCents,
    required this.pctUsed,
    this.showLabel = true,
  });

  final int spendCents;
  final int deductibleCents;
  final double pctUsed;
  final bool showLabel;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final band = spendBand(spendCents, deductibleCents);
    final color = bandColor(band, scheme);
    final bar = Tooltip(
      message: bandLabel(band),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(4),
        child: LinearProgressIndicator(
          value: (pctUsed / 100).clamp(0.0, 1.0),
          minHeight: 8,
          color: color,
          backgroundColor: scheme.surfaceContainerHighest,
        ),
      ),
    );
    if (!showLabel) return bar;
    return Row(
      children: [
        Expanded(child: bar),
        const SizedBox(width: 8),
        SizedBox(
          width: 64,
          child: Text(
            formatPct(pctUsed),
            textAlign: TextAlign.end,
            style: TextStyle(
              color: band == SpendBand.normal ? null : color,
              fontWeight: FontWeight.w600,
              fontFeatures: const [FontFeature.tabularFigures()],
            ),
          ),
        ),
      ],
    );
  }
}

/// Label above a value, used in summary strips.
class Stat extends StatelessWidget {
  const Stat({super.key, required this.label, required this.value, this.color});

  final String label;
  final String value;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          label,
          style: theme.textTheme.labelMedium?.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          ),
        ),
        const SizedBox(height: 2),
        Text(
          value,
          style: theme.textTheme.titleMedium?.copyWith(
            color: color,
            fontFeatures: const [FontFeature.tabularFigures()],
          ),
        ),
      ],
    );
  }
}
