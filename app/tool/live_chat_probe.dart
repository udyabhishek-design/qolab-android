// Live integration probe (NOT part of `flutter test`) — runs two REAL
// authenticated sessions against the Development backend + Reverb and
// verifies cross-session message delivery end-to-end.
//
// Usage: dart run tool/live_chat_probe.dart <senderEmail> <senderPass> <receiverEmail> <receiverPass>
// Sanitized output only: connection/subscription state, event names,
// delivery verdicts. No cookies/tokens/message bodies are printed.
import 'dart:async';
import 'dart:convert';
import 'dart:io';

const origin = 'https://workpulse-gbpedt.drytis.dev';
const wsOrigin = 'wss://workpulse-gbpedt.drytis.dev';
const appKey = 'E6bKH3QNfxtlNpk7ByiX';

void log(String m) => print('[probe] $m');

class Session {
  final String email;
  final String password;
  final String label;
  Session(this.email, this.password, this.label);
  final HttpClient http = HttpClient()
    ..badCertificateCallback = ((cert, host, port) => true);
  final Map<String, String> cookies = {};
  String? xsrf;
  String? socketId;
  WebSocket? ws;
  int userId = 0;
  final List<(String, String, Map)> events = [];
}

Future<bool> login(Session s) async {
  final uri = Uri.parse('$origin/login');
  final req = await s.http.getUrl(uri);
  req.followRedirects = false;
  final res = await req.close();
  _absorb(s, res);
  await res.drain<void>();
  final xsrf = s.cookies['XSRF-TOKEN'];
  if (xsrf == null) return false;
  final req2 = await s.http.postUrl(uri);
  req2.followRedirects = false;
  req2.headers.set('Content-Type', 'application/json');
  req2.headers.set('Accept', 'application/json');
  req2.headers.set('X-XSRF-TOKEN', Uri.decodeComponent(xsrf));
  req2.headers.set('Cookie', _cookieHeader(s));
  req2.write(jsonEncode({'email': s.email, 'password': s.password}));
  final res2 = await req2.close();
  _absorb(s, res2);
  await res2.drain<void>();
  if (res2.statusCode != 200) {
    log('${s.label} login status ${res2.statusCode}');
    return false;
  }
  log('${s.label} login ok');
  return true;
}

void _absorb(Session s, HttpClientResponse res) {
  res.headers.forEach((name, values) {
    if (name.toLowerCase() == 'set-cookie') {
      for (final v in values) {
        final kv = v.split(';').first;
        final eq = kv.indexOf('=');
        if (eq > 0) s.cookies[kv.substring(0, eq)] = kv.substring(eq + 1);
      }
    }
  });
}

String _cookieHeader(Session s) =>
    s.cookies.entries.map((e) => '${e.key}=${e.value}').join('; ');

Future<int?> findDirectChannel(Session a, Session b) async {
  // fetch chat index HTML and scrape both user ids + a direct channel id
  final page = await _get(a, '/chat');
  if (page == null) return null;
  // dm_partner pattern is unreliable; use the messages API from the
  // conversation between the two accounts via /chat/channels/{id}/messages
  // Instead: use user ids from dashboard data-page
  for (final s in [a, b]) {
    final dash = await _get(s, '/dashboard');
    if (dash == null) continue;
    final m = RegExp(r'"user":\{"id":(\d+),').firstMatch(dash);
    if (m != null) s.userId = int.parse(m.group(1)!);
  }
  if (a.userId == 0 || b.userId == 0) return null;
  // (DM discovery handled by startDm below)
  // fall back: start a DM via API
  return await startDm(a, b.userId);
}

Future<int?> startDm(Session s, int userId) async {
  final req = await s.http.postUrl(Uri.parse('$origin/chat/dm/$userId'));
  req.followRedirects = false;
  req.headers.set('Content-Type', 'application/json');
  req.headers.set('Accept', 'application/json');
  req.headers.set('Cookie', _cookieHeader(s));
  final xsrf = s.cookies['XSRF-TOKEN'];
  if (xsrf != null) {
    req.headers.set('X-XSRF-TOKEN', Uri.decodeComponent(xsrf));
  }
  final res = await req.close();
  final out = await res.transform(utf8.decoder).join();
  if (res.statusCode >= 400) {
    log('${s.label} POST /chat/dm -> ${res.statusCode}');
    return null;
  }
  // Redirects to /chat/channels/{id} (Location header or meta refresh body).
  final loc = res.headers.value('location') ?? out;
  final m = RegExp(r'/chat/channels/(\d+)').firstMatch(loc);
  return m == null ? null : int.tryParse(m.group(1)!);
}

Future<String?> _get(Session s, String path) async {
  final req = await s.http.getUrl(Uri.parse('$origin$path'));
  req.followRedirects = false;
  req.headers.set('Cookie', _cookieHeader(s));
  final res = await req.close();
  if (res.statusCode != 200) {
    log('${s.label} GET $path -> ${res.statusCode}');
    await res.drain<void>();
    return null;
  }
  return res.transform(utf8.decoder).join();
}

Future<String?> _post(Session s, String path, String body,
    {String accept = 'application/json'}) async {
  final req = await s.http.postUrl(Uri.parse('$origin$path'));
  req.followRedirects = false;
  req.headers.set('Content-Type', 'application/json');
  req.headers.set('Accept', accept);
  req.headers.set('Cookie', _cookieHeader(s));
  final xsrf = s.cookies['XSRF-TOKEN'];
  if (xsrf != null) {
    req.headers.set('X-XSRF-TOKEN', Uri.decodeComponent(xsrf));
  }
  req.write(body);
  final res = await req.close();
  final out = await res.transform(utf8.decoder).join();
  if (res.statusCode >= 400) {
    log('${s.label} POST $path -> ${res.statusCode}');
    return null;
  }
  return out;
}

Future<void> connect(Session s) async {
  s.ws = await WebSocket.connect('$wsOrigin/app/$appKey?protocol=7&client=flutter');
  s.ws!.listen((raw) {
    if (raw is! String) return;
    final msg = jsonDecode(raw);
    if (msg is! Map) return;
    final event = msg['event'];
    if (event == 'pusher:connection_established') {
      final data = msg['data'] is String ? jsonDecode(msg['data']) : msg['data'];
      s.socketId = data['socket_id'];
    } else if (event == 'pusher:ping') {
      s.ws!.add(jsonEncode({'event': 'pusher:pong'}));
    } else if (event == 'pusher_internal:subscription_succeeded') {
      log('${s.label} subscribed ${msg['channel']}');
    } else if (event is String && msg['channel'] is String) {
      final data = msg['data'] is String
          ? (jsonDecode(msg['data'] as String) as Map)
          : (msg['data'] as Map? ?? {});
      s.events.add((msg['channel'] as String, event, data));
      log('${s.label} EVENT ${msg['channel']} $event');
    }
  });
  // wait for socket_id
  while (s.socketId == null) {
    await Future<void>.delayed(const Duration(milliseconds: 50));
  }
  log('${s.label} connected');
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
  await Future<void>.delayed(const Duration(milliseconds: 300));
  return true;
}

Future<bool> sendMessage(Session s, int channelId, String body) async {
  final res = await _post(s, '/chat/channels/$channelId/messages',
      jsonEncode({'body': body}));
  if (res == null) return false;
  log('${s.label} message sent');
  return true;
}

Future<void> main(List<String> args) async {
  if (args.length != 4) {
    print('usage: dart run tool/live_chat_probe.dart <senderEmail> <senderPass> <receiverEmail> <receiverPass>');
    exit(2);
  }
  final sender = Session(args[0], args[1], 'sender');
  final receiver = Session(args[2], args[3], 'receiver');
  var failures = 0;

  for (final s in [sender, receiver]) {
    if (!await login(s)) {
      print('RESULT: FAIL (${s.label} login failed)');
      exit(1);
    }
  }

  final convId = await findDirectChannel(sender, receiver);
  if (convId == null) {
    print('RESULT: FAIL (no direct channel between accounts)');
    exit(1);
  }
  log('direct channel id: $convId');

  await connect(receiver);
  await subscribePrivate(receiver, 'user.${receiver.userId}');
  await subscribePrivate(receiver, 'channel.$convId');
  await connect(sender);
  await subscribePrivate(sender, 'user.${sender.userId}');
  await subscribePrivate(sender, 'channel.$convId');

  final bodyProbe = 'probe ${DateTime.now().millisecondsSinceEpoch}';
  final sent = await sendMessage(sender, convId, bodyProbe);
  if (!sent) {
    print('RESULT: FAIL (send failed)');
    exit(1);
  }

  final deadline = DateTime.now().add(const Duration(seconds: 15));
  bool receiverGotMessage = false,
      receiverGotNotification = false,
      senderGotEcho = false;
  while (DateTime.now().isBefore(deadline) &&
      !(receiverGotMessage && receiverGotNotification && senderGotEcho)) {
    await Future<void>.delayed(const Duration(milliseconds: 200));
    receiverGotMessage |= receiver.events
        .any((e) => e.$1 == 'private-channel.$convId' && e.$2.endsWith('message.sent'));
    receiverGotNotification |= receiver.events.any((e) =>
        e.$1 == 'private-user.${receiver.userId}' &&
        e.$2.endsWith('notification.received'));
    senderGotEcho |= sender.events
        .any((e) => e.$1 == 'private-channel.$convId' && e.$2.endsWith('message.sent'));
  }

  log('receiver message.sent on channel.$convId: $receiverGotMessage');
  log('receiver notification.received on user channel: $receiverGotNotification');
  log('sender echo message.sent: $senderGotEcho');
  if (!receiverGotMessage) failures++;
  if (!receiverGotNotification) failures++;
  if (!senderGotEcho) failures++;

  await sender.ws?.close();
  await receiver.ws?.close();
  print(failures == 0
      ? 'RESULT: PASS (live cross-session delivery verified)'
      : 'RESULT: FAIL ($failures delivery checks failed)');
  exit(failures == 0 ? 0 : 1);
}
