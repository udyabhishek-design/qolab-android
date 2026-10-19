import 'dart:async';

import 'package:flutter/material.dart';

import '../api/api_errors.dart';
import '../api/qolab_api.dart';
import '../chat/chat_controller.dart';
import '../chat/chat_models.dart';
import '../theme.dart';

/// New-conversation screen: pick an eligible employee to start/reopen a DM.
/// Employees come from the backend (/chat Inertia data) — nothing fabricated.
class NewChatScreen extends StatefulWidget {
  const NewChatScreen({
    super.key,
    required this.api,
    required this.chat,
    required this.employees,
  });

  final QolabApi api;
  final ChatListController chat;
  final List<ChatUser> employees;

  @override
  State<NewChatScreen> createState() => _NewChatScreenState();
}

class _NewChatScreenState extends State<NewChatScreen> {
  String _query = '';
  int? _busyUserId;

  List<ChatUser> get _filtered {
    if (_query.isEmpty) return widget.employees;
    final q = _query.toLowerCase();
    return widget.employees
        .where((u) =>
            u.name.toLowerCase().contains(q) ||
            (u.departmentName?.toLowerCase().contains(q) ?? false))
        .toList();
  }

  Future<void> _pick(ChatUser user) async {
    if (_busyUserId != null) return; // prevent duplicate sends
    setState(() => _busyUserId = user.id);
    try {
      final channelId = await widget.chat.startDm(user.id);
      if (!mounted) return;
      if (channelId != null) {
        Navigator.of(context).pop(channelId);
      }
    } on ApiException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(
          content: Text(e.message),
          duration: const Duration(seconds: 2),
          behavior: SnackBarBehavior.floating,
        ));
    } finally {
      if (mounted) setState(() => _busyUserId = null);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: QolabTheme.canvas,
      appBar: AppBar(
        backgroundColor: QolabTheme.canvas,
        elevation: 0,
        scrolledUnderElevation: 0,
        title: const Text('New conversation',
            style: TextStyle(
                color: QolabTheme.ink,
                fontSize: 17,
                fontWeight: FontWeight.w700)),
        leading: const BackButton(color: QolabTheme.ink),
      ),
      body: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
              child: TextField(
                onChanged: (v) => setState(() => _query = v),
                decoration: QolabTheme.field(label: 'Search people').copyWith(
                  prefixIcon: const Icon(Icons.search,
                      size: 20, color: QolabTheme.textSoft),
                  isDense: true,
                  contentPadding: const EdgeInsets.symmetric(vertical: 10),
                ),
                style: const TextStyle(fontSize: 14),
              ),
            ),
            Expanded(
              child: widget.employees.isEmpty
                  ? const Center(
                      child: Padding(
                        padding: EdgeInsets.all(32),
                        child: Text(
                          'No other members are available to message yet.',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                              color: QolabTheme.textSoft, fontSize: 13.5),
                        ),
                      ),
                    )
                  : ListView.builder(
                      padding: const EdgeInsets.only(bottom: 24),
                      itemCount: _filtered.length,
                      itemBuilder: (context, i) {
                        final u = _filtered[i];
                        final busy = _busyUserId == u.id;
                        return InkWell(
                          onTap: busy ? null : () => _pick(u),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(
                                horizontal: 20, vertical: 8),
                            child: Row(
                              children: [
                                _Avatar(name: u.name),
                                const SizedBox(width: 12),
                                Expanded(
                                  child: Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.start,
                                    children: [
                                      Text(u.name,
                                          maxLines: 1,
                                          overflow: TextOverflow.ellipsis,
                                          style: const TextStyle(
                                              fontSize: 15,
                                              fontWeight: FontWeight.w600,
                                              color: QolabTheme.ink)),
                                      if (u.departmentName != null ||
                                          u.jobTitle != null)
                                        Text(
                                          [u.jobTitle, u.departmentName]
                                              .whereType<String>()
                                              .join(' · '),
                                          maxLines: 1,
                                          overflow: TextOverflow.ellipsis,
                                          style: const TextStyle(
                                              color: QolabTheme.textSoft,
                                              fontSize: 13),
                                        ),
                                    ],
                                  ),
                                ),
                                if (busy)
                                  const SizedBox(
                                    width: 18,
                                    height: 18,
                                    child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                        color: QolabTheme.wine),
                                  )
                                else
                                  const Icon(Icons.chevron_right_rounded,
                                      color: QolabTheme.textSoft),
                              ],
                            ),
                          ),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Avatar extends StatelessWidget {
  const _Avatar({required this.name});

  final String name;

  String get initials {
    final parts =
        name.trim().split(RegExp(r'\s+')).where((p) => p.isNotEmpty);
    if (parts.isEmpty) return '?';
    if (parts.length == 1) return parts.first[0].toUpperCase();
    return (parts.first[0] + parts.last[0]).toUpperCase();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 44,
      height: 44,
      decoration: BoxDecoration(
        color: QolabTheme.wine,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Center(
        child: Text(initials,
            style: const TextStyle(
                color: Colors.white, fontWeight: FontWeight.w700)),
      ),
    );
  }
}
