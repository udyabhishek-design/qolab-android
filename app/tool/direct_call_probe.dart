// Direct Calling (Phase C1) — LIVE two-session signaling probe.
// NOT part of `flutter test`. Runs two REAL authenticated sessions against
// the Development backend + Reverb and verifies the call lifecycle
// end-to-end: incoming, accept (token), decline, cancel, expiry, recovery,
// authorization.
//
// Usage: dart run tool/direct_call_probe.dart
// Sanitized output only: phases, verdicts, no cookies/tokens printed.

import 'dart:async';
import 'dart:convert';
import 'dart:io';

const origin = 'https://workpulse-gbpedt.drytis.dev';
const appKey = 'E6bKH3QNfxtlNpk7ByiX';
const adminEmail = 'qa-admin-dev@qolab.test';
const adminPass = 'Qa!Admin-LiveHub-27';
const memberEmail = 'qa-listener-dev@qolab.test';
const memberPass = ')2#abD*bNS3JAXS2Y2Goiobca!%)';

void log(String m) => print('[c1] $m');

int passed = 0;
int failed = 0;
void check(String name, bool ok, [String? detail]) {
  if (ok) {
    passed++;
    log('PASS: $name');
  } else {
    failed++;
    log('FAIL: $name${detail == null ? '' : ' — $detail'}');
  }
}

class Session {
  final String label;
  final String email;
  final String password;
  Session(this.label, this.email, this.password);
  final HttpClient http = HttpClient()
    ..badCertificateCallback = ((cert, host, port) => true);
  final Map<String, String> cookies = {};
  String? xsrf;
  String? socketId;
  WebSocket? ws;
  int userId = 0;
  String name = '';
  final List<String> events = []; // "name:status:callId"
  final Completer<void> subscribed = Completer<void>();
}

String _cookieHeader(Session s) =>
    s.cookies.entries.map((e) => '${e.key}=${e.value}').join('; ');

void _storeCookies(Session s, HttpHeaders headers) {
  final setCookies = headers[HttpHeaders.setCookieHeader];
  if (setCookies == null) return;
  for (final raw in setCookies) {
    final parts = raw.split(';').first.split('=');
    if (parts.length >= 2) {
      s.cookies[parts[0]] = parts.sublist(1).join('=');
    }
  }
}

Future<bool> login(Session s) async {
  // CSRF bootstrap
  final boot = await s.http.getUrl(Uri.parse('$origin/login'));
  boot.followRedirects = false;
  final bootRes = await boot.close();
  _storeCookies(s, bootRes.headers);
  await bootRes.drain<void>();

  final xsrf = s.cookies['XSRF-TOKEN'];
  final req = await s.http.postUrl(Uri.parse('$origin/login'));
  req.followRedirects = false;
  req.headers.set('Content-Type', 'application/json');
  req.headers.set('Accept', 'application/json');
  req.headers.set('X-Requested-With', 'XMLHttpRequest');
  req.headers.set('Cookie', _cookieHeader(s));
  if (xsrf != null) req.headers.set('X-XSRF-TOKEN', Uri.decodeComponent(xsrf));
  req.write(jsonEncode({'email': s.email, 'password': s.password}));
  final res = await req.close();
  final body = await res.transform(utf8.decoder).join();
  if (res.statusCode != 200) {
    log('${s.label} login -> ${res.statusCode} $body');
    return false;
  }
  _storeCookies(s, res.headers);

  // Login JSON carries only {two_factor:false}; scrape /dashboard for the
  // authenticated user id + name (Inertia data-page).
  final dashReq = await s.http.getUrl(Uri.parse('$origin/dashboard'));
  dashReq.followRedirects = false;
  dashReq.headers.set('Cookie', _cookieHeader(s));
  final dashRes = await dashReq.close();
  final dash = await dashRes.transform(utf8.decoder).join();
  final idMatch = RegExp(r'"user":\{"id":(\d+),"name":"([^"]*)"').firstMatch(dash);
  if (idMatch == null) {
    log('${s.label} could not scrape user from dashboard (status ${dashRes.statusCode})');
    return false;
  }
  s.userId = int.parse(idMatch.group(1)!);
  s.name = idMatch.group(2)!;

  // connect websocket
  final ws = await WebSocket.connect(
      'wss://workpulse-gbpedt.drytis.dev/app/$appKey');
  ws.listen((m) => _onWs(s, m.toString()));
  s.ws = ws;
  ws.add(jsonEncode({'event': 'pusher:subscribe', 'data': {'channel': 'websocket'}}));
  // wait for connection_established (socket id)
  final deadline = DateTime.now().add(const Duration(seconds: 8));
  while (s.socketId == null && DateTime.now().isBefore(deadline)) {
    await Future<void>.delayed(const Duration(milliseconds: 100));
  }
  return s.socketId != null && s.userId > 0;
}

void _onWs(Session s, String m) {
  try {
    final msg = jsonDecode(m) as Map;
    final event = msg['event'] as String?;
    final data = msg['data'];
    if (event == 'pusher:connection_established' && data is String) {
      s.socketId = jsonDecode(data)['socket_id'] as String;
      return;
    }
    if (event == 'pusher_internal:subscription_succeeded') {
      if (!s.subscribed.isCompleted) s.subscribed.complete();
      return;
    }
    // call.* events carry {call_id, status, ...}
    if (event != null && event.startsWith('call.')) {
      Map? payload;
      if (data is Map) payload = data;
      if (data is String) {
        final d = jsonDecode(data);
        if (d is Map) payload = d;
      }
      final callId = payload?['call_id'];
      final status = payload?['status'];
      s.events.add('$event:$status:$callId');
    }
  } catch (_) {}
}

Future<bool> subscribePrivate(Session s, String channel) async {
  final sid = s.socketId;
  if (sid == null) return false;
  final body =
      'channel_name=${Uri.encodeQueryComponent('private-$channel')}&socket_id=${Uri.encodeQueryComponent(sid)}';
  final req = await s.http.postUrl(Uri.parse('$origin/broadcasting/auth'));
  req.followRedirects = false;
  req.headers.set('Content-Type', 'application/x-www-form-urlencoded');
  req.headers.set('Accept', 'application/json');
  req.headers.set('Cookie', _cookieHeader(s));
  final xsrf = s.cookies['XSRF-TOKEN'];
  if (xsrf != null) {
    req.headers.set('X-XSRF-TOKEN', Uri.decodeComponent(xsrf));
  }
  req.write(body);
  final res = await req.close();
  final out = await res.transform(utf8.decoder).join();
  if (res.statusCode != 200) {
    log('${s.label} auth $channel -> ${res.statusCode}');
    return false;
  }
  final auth = jsonDecode(out)['auth'] as String;
  s.ws!.add(jsonEncode({
    'event': 'pusher:subscribe',
    'data': {'channel': 'private-$channel', 'auth': auth},
  }));
  await Future<void>.delayed(const Duration(milliseconds: 400));
  return true;
}

Future<Map?> _postJson(Session s, String path, [Object? payload]) async {
  final req = await s.http.postUrl(Uri.parse('$origin$path'));
  req.followRedirects = false;
  req.headers.set('Content-Type', 'application/json');
  req.headers.set('Accept', 'application/json');
  req.headers.set('X-Requested-With', 'XMLHttpRequest');
  req.headers.set('Cookie', _cookieHeader(s));
  final xsrf = s.cookies['XSRF-TOKEN'];
  if (xsrf != null) {
    req.headers.set('X-XSRF-TOKEN', Uri.decodeComponent(xsrf));
  }
  if (payload != null) req.write(jsonEncode(payload));
  final res = await req.close();
  final body = await res.transform(utf8.decoder).join();
  try {
    return {'status': res.statusCode, 'json': jsonDecode(body)};
  } catch (_) {
    return {'status': res.statusCode, 'json': null};
  }
}

Future<Map?> _getJson(Session s, String path) async {
  final req = await s.http.getUrl(Uri.parse('$origin$path'));
  req.followRedirects = false;
  req.headers.set('Accept', 'application/json');
  req.headers.set('X-Requested-With', 'XMLHttpRequest');
  req.headers.set('Cookie', _cookieHeader(s));
  final res = await req.close();
  final body = await res.transform(utf8.decoder).join();
  try {
    return {'status': res.statusCode, 'json': jsonDecode(body)};
  } catch (_) {
    return {'status': res.statusCode, 'json': null};
  }
}

Future<void> pumpUntil(bool Function() test, Duration timeout) async {
  final end = DateTime.now().add(timeout);
  while (!test() && DateTime.now().isBefore(end)) {
    await Future<void>.delayed(const Duration(milliseconds: 250));
  }
}

bool saw(Session s, String prefix) =>
    s.events.any((e) => e.startsWith(prefix));

Future<void> main() async {
  final a = Session('A', adminEmail, adminPass);
  final b = Session('B', memberEmail, memberPass);
  if (!await login(a) || !await login(b)) {
    print('[c1] RESULT: BLOCKED — login failed');
    exit(2);
  }
  log('A=${a.name}(${a.userId}) B=${b.name}(${b.userId})');
  check('two sessions authenticated', true);

  check('A subscribed user channel',
      await subscribePrivate(a, 'user.${a.userId}'));
  check('B subscribed user channel',
      await subscribePrivate(b, 'user.${b.userId}'));

  // S1: A calls B — B receives call.incoming in real time
  log('S1: A calls B (audio)');
  final start = await _postJson(
      a, '/chat/calls', {'recipient_id': b.userId, 'call_type': 'audio'});
  final call = start?['json'] is Map ? start!['json']['call'] as Map? : null;
  final callId = call?['call_id'] as String?;
  check('start 201 + call payload', start?['status'] == 201 && callId != null,
      'status=${start?['status']}');
  check('no token in broadcast-visible payload (payload has no token key)',
      !call!.containsKey('token') && !call.containsKey('room_name'));
  await pumpUntil(() => saw(b, 'call.incoming:ringing:$callId'),
      const Duration(seconds: 10));
  check('B got call.incoming ringingly fast', saw(b, 'call.incoming:ringing:$callId'),
      b.events.join(', '));
  check('caller name present', call['caller'] is Map && (call['caller'] as Map)['name'] == a.name);

  // S2: B declines → A receives call.declined
  log('S2: B declines');
  b.events.clear();
  final decline = await _postJson(b, '/chat/calls/$callId/decline');
  check('decline 200', decline?['status'] == 200, 'status=${decline?['status']}');
  await pumpUntil(() => saw(a, 'call.declined:declined:$callId'),
      const Duration(seconds: 10));
  check('A received call.declined', saw(a, 'call.declined:declined:$callId'),
      a.events.join(', '));

  // S3: A calls, B accepts → both get tokens; A gets call.accepted
  log('S3: A calls B (video), B accepts');
  final start2 = await _postJson(
      a, '/chat/calls', {'recipient_id': b.userId, 'call_type': 'video'});
  final call2 = start2?['json'] is Map ? start2!['json']['call'] as Map? : null;
  final cid2 = call2?['call_id'] as String?;
  check('second call started', cid2 != null);
  await pumpUntil(() => saw(b, 'call.incoming:ringing:$cid2'),
      const Duration(seconds: 10));
  check('B ringing again', saw(b, 'call.incoming:ringing:$cid2'));
  a.events.clear();
  final accept = await _postJson(b, '/chat/calls/$cid2/accept');
  final tokB = accept?['json'] is Map ? accept!['json']['token'] : null;
  final urlB = accept?['json'] is Map ? accept!['json']['url'] : null;
  check('accept 200 + token + url',
      accept?['status'] == 200 && tokB is String && urlB is String,
      'status=${accept?['status']}');
  await pumpUntil(() => saw(a, 'call.accepted:accepted:$cid2'),
      const Duration(seconds: 10));
  check('A received call.accepted', saw(a, 'call.accepted:accepted:$cid2'),
      a.events.join(', '));
  // caller token fetch
  final tokA = await _postJson(a, '/chat/calls/$cid2/token');
  check('A fetched LiveKit token',
      tokA?['status'] == 200 && tokA?['json'] is Map && (tokA!['json'] as Map)['token'] is String);
  final urlA = (tokA!['json'] as Map)['url'];
  check('LiveKit url is wss', urlA is String && urlA.startsWith('wss://'),
      '$urlA');

  // S4: B ends → A receives call.ended
  log('S4: B hangs up');
  final end4 = await _postJson(b, '/chat/calls/$cid2/end');
  check('end 200', end4?['status'] == 200);
  await pumpUntil(() => saw(a, 'call.ended:ended:$cid2'),
      const Duration(seconds: 10));
  check('A received call.ended', saw(a, 'call.ended:ended:$cid2'),
      a.events.join(', '));

  // S5: A cancels before answer → B receives call.cancelled
  log('S5: A calls, cancels');
  final start5 = await _postJson(
      a, '/chat/calls', {'recipient_id': b.userId, 'call_type': 'audio'});
  final cid5 = (start5?['json'] as Map?)?['call']['call_id'] as String?;
  check('call started', cid5 != null);
  await pumpUntil(() => saw(b, 'call.incoming:ringing:$cid5'),
      const Duration(seconds: 10));
  b.events.clear();
  await _postJson(a, '/chat/calls/$cid5/cancel');
  await pumpUntil(() => saw(b, 'call.cancelled:cancelled:$cid5'),
      const Duration(seconds: 10));
  check('B ringing stopped via call.cancelled',
      saw(b, 'call.cancelled:cancelled:$cid5'), b.events.join(', '));

  // S6: expiry (~30s unanswered → missed; both notified)
  log('S6: expiry (waiting ~40s)');
  final start6 = await _postJson(
      a, '/chat/calls', {'recipient_id': b.userId, 'call_type': 'audio'});
  final cid6 = (start6?['json'] as Map?)?['call']['call_id'] as String?;
  check('call started', cid6 != null);
  a.events.clear();
  b.events.clear();
  await pumpUntil(
      () => saw(a, 'call.missed:missed:$cid6') && saw(b, 'call.missed:missed:$cid6'),
      const Duration(seconds: 55));
  check('both received call.missed on expiry',
      saw(a, 'call.missed:missed:$cid6') && saw(b, 'call.missed:missed:$cid6'),
      'a=${a.events.join(",")} b=${b.events.join(",")}');

  // S7: recovery — ringing call visible via GET /chat/calls for both
  log('S7: recovery');
  final start7 = await _postJson(
      a, '/chat/calls', {'recipient_id': b.userId, 'call_type': 'audio'});
  final cid7 = (start7?['json'] as Map?)?['call']['call_id'] as String?;
  final idxA = await _getJson(a, '/chat/calls');
  final idxB = await _getJson(b, '/chat/calls');
  bool hasCall(Map? r, String id) => (r?['json'] is Map)
      ? ((r!['json'] as Map)['calls'] as List?)
            ?.any((c) => c is Map && c['call_id'] == id) ?? false
      : false;
  check('recovery sees ringing call for A', hasCall(idxA, cid7!),
      jsonEncode(idxA?['json']));
  check('recovery sees ringing call for B', hasCall(idxB, cid7));
  await _postJson(a, '/chat/calls/$cid7/cancel');

  // S8: authorization — anonymous + third-party access denied
  log('S8: authorization');
  final anon = Session('anon', 'x@x.test', 'x');
  // no login: use a fresh HttpClient without cookies
  final anonReq = await anon.http.postUrl(
      Uri.parse('$origin/chat/calls/$cid7/token'));
  anonReq.followRedirects = false;
  anonReq.headers.set('Accept', 'application/json');
  final anonRes = await anonReq.close();
  await anonRes.drain<void>();
  check('anonymous token fetch denied (401/302/419)',
      anonRes.statusCode == 401 ||
          anonRes.statusCode == 302 ||
          anonRes.statusCode == 419,
      'status=${anonRes.statusCode}');

  // duplicate start guard: A already has ringing? (cancelled above) — start new, try second
  final d1 = await _postJson(
      a, '/chat/calls', {'recipient_id': b.userId, 'call_type': 'audio'});
  final d2 = await _postJson(
      a, '/chat/calls', {'recipient_id': b.userId, 'call_type': 'audio'});
  check('duplicate outgoing call blocked (409)',
      (d1?['status'] == 201) && (d2?['status'] == 409),
      'd1=${d1?['status']} d2=${d2?['status']}');
  final cid8 = (d1?['json'] as Map?)?['call']['call_id'] as String?;
  await _postJson(a, '/chat/calls/$cid8/cancel');

  print('');
  print('[c1] RESULT: ${failed == 0 ? "ALL PASS" : "FAILURES"} — $passed passed, $failed failed');
  exit(failed == 0 ? 0 : 1);
}
