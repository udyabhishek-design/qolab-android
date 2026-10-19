import 'package:flutter/material.dart';

import '../api/qolab_api.dart';
import '../auth/auth_controller.dart';
import '../home/home_controller.dart';
import '../home/home_models.dart';
import '../theme.dart';
import 'coming_soon_screen.dart';

/// Phase 3 Home: greeting, Next Meeting, Social Rooms, Department Room,
/// Continue Working + fixed bottom navigation (Home | Chat | Ask Drytis).
///
/// Data: HomeController fetches /dashboard and /rooms in parallel with
/// per-section loading/empty/error states; unread badge is independent.
class HomeShell extends StatefulWidget {
  const HomeShell({super.key, required this.auth, required this.api,
      this.homeController});

  final AuthController auth;
  final QolabApi api;

  /// Optional pre-built controller (tests seed loaded state directly).
  final HomeController? homeController;

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  late final HomeController home;
  int _tab = 0;

  @override
  void initState() {
    super.initState();
    home = widget.homeController ??
        HomeController(
          api: widget.api,
          onSessionExpired: () {
            // Session proven dead server-side — hand back to the existing
            // auth flow (clears local state, shows Login). Never a second
            // auth state machine.
            widget.auth.restore();
          },
        );
    home.refresh();
  }

  @override
  void dispose() {
    // Only dispose a controller we own (tests manage their own).
    if (widget.homeController == null) home.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final pages = [
      HomeScreen(auth: widget.auth, home: home),
      const ComingSoonScreen(
        title: 'Chat',
        description: 'Conversations, rooms chat and direct messages.',
        icon: Icons.chat_bubble_outline_rounded,
      ),
      const ComingSoonScreen(
        title: 'Ask Drytis',
        description: 'Your AI assistant for meetings, tasks and knowledge.',
        icon: Icons.auto_awesome_outlined,
      ),
    ];
    return Scaffold(
      backgroundColor: QolabTheme.canvas,
      body: IndexedStack(
        index: _tab,
        children: pages,
      ),
      bottomNavigationBar: _BottomNav(
        current: _tab,
        onChanged: (i) {
          setState(() => _tab = i);
          if (i == 0 && _homeIsStale) home.refresh();
        },
      ),
    );
  }

  bool get _homeIsStale => !home.refreshing;
}

class _BottomNav extends StatelessWidget {
  const _BottomNav({required this.current, required this.onChanged});

  final int current;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        color: QolabTheme.surface,
        border: Border(top: BorderSide(color: QolabTheme.line)),
      ),
      child: SafeArea(
        top: false,
        child: SizedBox(
          height: 62,
          child: Row(
            children: [
              _item(0, 'Home', Icons.home_outlined, Icons.home_rounded),
              _item(1, 'Chat', Icons.chat_bubble_outline_rounded,
                  Icons.chat_bubble_rounded),
              _item(2, 'Ask Drytis', Icons.auto_awesome_outlined,
                  Icons.auto_awesome),
            ],
          ),
        ),
      ),
    );
  }

  Widget _item(int index, String label, IconData icon, IconData activeIcon) {
    final active = index == current;
    return Expanded(
      child: InkWell(
        onTap: () => onChanged(index),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              active ? activeIcon : icon,
              size: 22,
              color: active ? QolabTheme.wine : QolabTheme.textSoft,
            ),
            const SizedBox(height: 3),
            Text(
              label,
              style: TextStyle(
                fontSize: 11.5,
                fontWeight: active ? FontWeight.w600 : FontWeight.w400,
                color: active ? QolabTheme.wine : QolabTheme.textSoft,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────
// The Home tab content
// ─────────────────────────────────────────────────────────────────────

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key, required this.auth, required this.home});

  final AuthController auth;
  final HomeController home;

  String _greetingWord(DateTime now) {
    final h = now.hour;
    if (h < 12) return 'Good morning';
    if (h < 17) return 'Good afternoon';
    return 'Good evening';
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: Listenable.merge([auth, home]),
      builder: (context, _) {
        final user = auth.user;
        final name = user?.name ?? '';
        final first = name.isEmpty ? 'there' : name.split(' ').first;
        return Scaffold(
          backgroundColor: QolabTheme.canvas,
          body: SafeArea(
            child: ListView(
              padding: const EdgeInsets.symmetric(
                  horizontal: 20, vertical: 12),
              children: [
                _header(context, first),
                const SizedBox(height: 18),
                _nextMeetingCard(),
                const SizedBox(height: 18),
                _socialRooms(),
                const SizedBox(height: 18),
                _departmentRoom(context),
                const SizedBox(height: 18),
                _continueWorking(),
                const SizedBox(height: 24),
              ],
            ),
          ),
        );
      },
    );
  }

  // ── Header ──────────────────────────────────────────────────────────

  Widget _header(BuildContext context, String firstName) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Container(
              width: 32,
              height: 32,
              decoration: BoxDecoration(
                color: QolabTheme.wine,
                borderRadius: BorderRadius.circular(9),
              ),
              child: const Center(
                child: Text('Q',
                    style: TextStyle(
                        color: Colors.white,
                        fontSize: 16,
                        fontWeight: FontWeight.w700)),
              ),
            ),
            const SizedBox(width: 9),
            const Text('Qolab',
                style: TextStyle(
                    color: QolabTheme.ink, fontWeight: FontWeight.bold)),
            const Spacer(),
            // Permanent notification bell — never depends on the unread
            // count (visible at zero, loading, and failure). The numeric
            // badge overlays the bell only when the count is positive and
            // a known value; unknown (null) is never fabricated.
            _NotificationBell(
              unreadCount: home.unreadCount,
              onTap: () => _comingSoon(context, 'Notifications'),
            ),
            const SizedBox(width: 6),
            // Logout preserved: single tap, immediate feedback.
            auth.busy
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: Center(
                        child: CircularProgressIndicator(
                            strokeWidth: 2.2, color: QolabTheme.wine)),
                  )
                : TextButton(
                    onPressed: () => auth.logout(),
                    style: TextButton.styleFrom(
                        padding: const EdgeInsets.symmetric(horizontal: 10)),
                    child: const Text('Sign out',
                        style: TextStyle(
                            color: QolabTheme.wine, fontSize: 13.5)),
                  ),
          ],
        ),
        const SizedBox(height: 16),
        Text('${_greetingWord(DateTime.now())}, $firstName',
            style: QolabTheme.headline),
        const SizedBox(height: 5),
        Text(
          auth.user?.departmentName ?? 'Welcome to your workspace',
          style: QolabTheme.caption,
        ),
      ],
    );
  }

  // ── Next Meeting ────────────────────────────────────────────────────

  Widget _nextMeetingCard() {
    return _SectionCard(
      title: 'Next Meeting',
      child: _sectionBody(
        section: home.dashboard,
        isEmpty: home.dashboard.value?.nextMeeting == null,
        build: () {
          final m = home.dashboard.value!.nextMeeting!;
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(m.title,
                        style: QolabTheme.title,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis),
                  ),
                  if (m.isLive)
                    Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 8, vertical: 3),
                      decoration: BoxDecoration(
                        color: QolabTheme.wine,
                        borderRadius: BorderRadius.circular(999),
                      ),
                      child: const Text('LIVE',
                          style: TextStyle(
                              color: Colors.white,
                              fontSize: 10.5,
                              fontWeight: FontWeight.w700)),
                    ),
                ],
              ),
              const SizedBox(height: 8),
              Text(_meetingWhen(m), style: QolabTheme.body),
              if (m.participantsCount != null) ...[
                const SizedBox(height: 4),
                Text('${m.participantsCount} participant'
                    '${m.participantsCount == 1 ? '' : 's'}',
                    style: QolabTheme.caption),
              ],
            ],
          );
        },
        empty: const Text('No meetings scheduled',
            style: QolabTheme.caption),
      ),
    );
  }

  String _meetingWhen(HomeMeeting m) {
    final at = m.scheduledAt;
    if (at == null) return 'Time to be confirmed';
    final local = at.toLocal();
    final today = DateTime.now();
    final sameDay = local.year == today.year &&
        local.month == today.month &&
        local.day == today.day;
    final time =
        '${_two(local.hour)}:${_two(local.minute)}';
    if (sameDay) return 'Today at $time';
    final tomorrow = today.add(const Duration(days: 1));
    final isTomorrow = local.year == tomorrow.year &&
        local.month == tomorrow.month &&
        local.day == tomorrow.day;
    if (isTomorrow) return 'Tomorrow at $time';
    return '${local.day}/${local.month} at $time';
  }

  String _two(int n) => n.toString().padLeft(2, '0');

  // ── Social Rooms ────────────────────────────────────────────────────

  Widget _socialRooms() {
    final list = home.dashboard.value?.socialRooms ?? const [];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _sectionTitle('Social Rooms'),
        const SizedBox(height: 10),
        _sectionBody(
          section: home.dashboard,
          isEmpty: list.isEmpty,
          build: () => SizedBox(
            height: 132,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              itemCount: list.length,
              separatorBuilder: (_, __) => const SizedBox(width: 12),
              itemBuilder: (context, i) =>
                  _socialRoomCard(context, list[i]),
            ),
          ),
          empty: const Text('No Social Rooms available right now',
              style: QolabTheme.caption),
        ),
      ],
    );
  }

  Widget _socialRoomCard(BuildContext context, HomeRoom room) {
    final color = Color(room.themeColorValue);
    return GestureDetector(
      onTap: () => _comingSoon(context, room.name),
      child: Container(
        width: 168,
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: color,
          borderRadius: BorderRadius.circular(16),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(_roomIcon(room.roomTheme), size: 22, color: Colors.white),
            const Spacer(),
            Text(room.name,
                style: const TextStyle(
                    color: Colors.white,
                    fontSize: 15.5,
                    fontWeight: FontWeight.w600)),
            const SizedBox(height: 3),
            Text(
              room.membersCount != null
                  ? '${room.membersCount} member'
                    '${room.membersCount == 1 ? '' : 's'}'
                  : (room.description ?? 'Join your team'),
              style: const TextStyle(
                  color: Colors.white70, fontSize: 12.5),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ],
        ),
      ),
    );
  }

  IconData _roomIcon(String? theme) {
    switch (theme) {
      case 'music':
        return Icons.music_note_rounded;
      case 'coffee':
        return Icons.local_cafe_outlined;
      default:
        return Icons.groups_outlined;
    }
  }

  // ── Department Room ─────────────────────────────────────────────────

  Widget _departmentRoom(BuildContext context) {
    final room = home.rooms.value?.departmentRoom;
    if (home.rooms.state != SectionState.loaded || room == null) {
      // Hidden until loaded; empty (no department) → omitted entirely
      // unless this is an error the user can retry.
      if (home.rooms.state == SectionState.error) {
        return _errorRetry(
            'Department Room', 'rooms', home.retrySection);
      }
      if (home.rooms.state == SectionState.loading) {
        return const _SkeletonCard(height: 92);
      }
      return const SizedBox.shrink();
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _sectionTitle('Department Room'),
        const SizedBox(height: 10),
        GestureDetector(
          onTap: () => _comingSoon(context, room.name),
          child: Container(
            width: double.infinity,
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: QolabTheme.surface,
              borderRadius: BorderRadius.circular(18),
              border: Border.all(
                  color: Color(room.themeColorValue), width: 1.4),
            ),
            child: Row(
              children: [
                Container(
                  width: 42,
                  height: 42,
                  decoration: BoxDecoration(
                    color: Color(room.themeColorValue),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: const Icon(Icons.business_center_outlined,
                      size: 20, color: Colors.white),
                ),
                const SizedBox(width: 13),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(room.name,
                          style: QolabTheme.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis),
                      const SizedBox(height: 3),
                      Text(
                        room.membersCount != null
                            ? '${room.membersCount} member'
                  '${room.membersCount == 1 ? '' : 's'}'
                            : (room.description ?? 'Your department space'),
                        style: QolabTheme.caption,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ),
                ),
                const Icon(Icons.chevron_right_rounded,
                    color: QolabTheme.textSoft),
              ],
            ),
          ),
        ),
      ],
    );
  }

  // ── Continue Working ────────────────────────────────────────────────

  Widget _continueWorking() {
    final tasks = home.dashboard.value?.continueTasks ?? const [];
    return _SectionCard(
      title: 'Continue Working',
      child: _sectionBody(
        section: home.dashboard,
        isEmpty: tasks.isEmpty,
        build: () => Column(
          children: [
            for (final t in tasks.take(3))
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Row(
                  children: [
                    Container(
                      width: 8,
                      height: 8,
                      decoration: BoxDecoration(
                        color: _taskColor(t),
                        shape: BoxShape.circle,
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(t.title,
                          style: QolabTheme.body,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis),
                    ),
                    if (t.dueDate != null)
                      Text(_shortDue(t.dueDate!),
                          style: QolabTheme.caption),
                  ],
                ),
              ),
          ],
        ),
        empty: const Row(
          children: [
            Icon(Icons.check_circle_outline_rounded,
                size: 17, color: QolabTheme.wine),
            SizedBox(width: 7),
            Expanded(
              child: Text("You're all caught up",
                  style: QolabTheme.caption),
            ),
          ],
        ),
      ),
    );
  }

  Color _taskColor(HomeTask t) {
    switch (t.priority) {
      case 'urgent':
        return const Color(0xFFC0392B);
      case 'high':
        return QolabTheme.wine;
      case 'low':
        return QolabTheme.textSoft;
      default:
        return QolabTheme.wineFaded;
    }
  }

  String _shortDue(String yyyymmdd) {
    final d = DateTime.tryParse(yyyymmdd);
    if (d == null) return yyyymmdd;
    final today = DateTime.now();
    if (d.year == today.year &&
        d.month == today.month &&
        d.day == today.day) {
      return 'Today';
    }
    if (d.isBefore(DateTime(today.year, today.month, today.day))) {
      return 'Overdue';
    }
    return '${d.day}/${d.month}';
  }

  // ── Shared section chrome ───────────────────────────────────────────

  Widget _sectionTitle(String s) => Text(s, style: QolabTheme.title);

  Widget _sectionBody({
    required Section<dynamic> section,
    required bool isEmpty,
    required Widget Function() build,
    required Widget empty,
  }) {
    switch (section.state) {
      case SectionState.loading:
        return const _SkeletonCard(height: 84);
      case SectionState.error:
        return _errorRetry(null, 'dashboard', home.retrySection);
      case SectionState.empty:
      case SectionState.loaded:
        if (isEmpty) {
          return Container(
            width: double.infinity,
            padding: const EdgeInsets.all(16),
            decoration: _cardDecoration(),
            child: empty,
          );
        }
        return build();
    }
  }

  BoxDecoration _cardDecoration() => BoxDecoration(
        color: QolabTheme.surface,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: QolabTheme.line),
      );

  Widget _errorRetry(String? title, String key,
      Future<void> Function(String) retry) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: _cardDecoration(),
      child: Column(
        children: [
          const Text("Couldn't load this section",
              style: QolabTheme.caption),
          const SizedBox(height: 8),
          TextButton.icon(
            onPressed: () => retry(key),
            icon: const Icon(Icons.refresh_rounded,
                size: 15, color: QolabTheme.wine),
            label: const Text('Retry',
                style:
                    TextStyle(color: QolabTheme.wine, fontSize: 13)),
          ),
        ],
      ),
    );
  }

  void _comingSoon(BuildContext context, String what) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(
        content: Text('$what opens in a future update'),
        duration: const Duration(seconds: 2),
        behavior: SnackBarBehavior.floating,
      ));
  }
}

class _SectionCard extends StatelessWidget {
  const _SectionCard({required this.title, required this.child});

  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title, style: QolabTheme.title),
        const SizedBox(height: 10),
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: QolabTheme.surface,
            borderRadius: BorderRadius.circular(18),
            border: Border.all(color: QolabTheme.line),
          ),
          child: child,
        ),
      ],
    );
  }
}

class _SkeletonCard extends StatelessWidget {
  const _SkeletonCard({required this.height});

  final double height;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      height: height,
      decoration: BoxDecoration(
        color: QolabTheme.surface,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: QolabTheme.line),
      ),
      child: const Center(
        child: SizedBox(
          width: 20,
          height: 20,
          child: CircularProgressIndicator(
              strokeWidth: 2.2, color: QolabTheme.wineFaded),
        ),
      ),
    );
  }
}

/// Permanent notification bell with a conditional unread badge.
///
/// The bell is ALWAYS visible (zero / loading / failure / positive); the
/// compact badge overlays the top-right corner of the bell only when
/// [unreadCount] is a known positive value. Unknown (null) never renders
/// a fabricated count.
class _NotificationBell extends StatelessWidget {
  const _NotificationBell({required this.unreadCount, required this.onTap});

  final int? unreadCount;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final count = unreadCount;
    final showBadge = count != null && count > 0;
    return Semantics(
      button: true,
      label: showBadge
          ? 'Notifications, $count unread'
          : 'Notifications',
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          IconButton(
            onPressed: onTap,
            icon: const Icon(Icons.notifications_none_rounded,
                size: 22, color: QolabTheme.wine),
            padding: const EdgeInsets.all(8),
            constraints: const BoxConstraints(
                minWidth: 40, minHeight: 40), // good touch target
            tooltip: 'Notifications',
            visualDensity: VisualDensity.compact,
          ),
          if (showBadge)
            Positioned(
              right: 2,
              top: 2,
              child: IgnorePointer(
                // Badge must not intercept the bell's tap.
                child: Container(
                  padding: const EdgeInsets.symmetric(
                      horizontal: 4.5, vertical: 1.5),
                  constraints: const BoxConstraints(minWidth: 16),
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: QolabTheme.wine,
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(
                        color: QolabTheme.canvas, width: 1.4),
                  ),
                  child: Text(
                    '$count',
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 9.5,
                      fontWeight: FontWeight.w700,
                      height: 1.1,
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}
