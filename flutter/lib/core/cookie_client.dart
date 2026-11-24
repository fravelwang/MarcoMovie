import 'dart:async';

import 'package:http/http.dart' as http;

/// A minimal per-host cookie jar for the netdisk passport flows.
///
/// `http.Client` does not persist cookies. Several passport endpoints bind a
/// QR-login token to the cookies handed out when the token was created —
/// notably Quark's `uop.quark.cn` (see `docs/quark-account-playback.md`), where
/// polling without the creation cookies answers `50004002 Token Not Found`
/// instead of `50004001 Query result is empty`.
///
/// Cookies are kept per host so one drive's session cookies are never sent to
/// another drive's servers.
class CookieClient extends http.BaseClient {
  CookieClient([http.Client? inner]) : _inner = inner ?? http.Client();

  final http.Client _inner;
  final Map<String, Map<String, String>> _jar = {};

  /// Drops every stored cookie. Called when a new QR attempt starts so a stale
  /// session can never be reused by a replacement QR code.
  void clear() => _jar.clear();

  Map<String, String> cookiesFor(String host) =>
      Map<String, String>.unmodifiable(_jar[host] ?? const {});

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    final jar = _jar[request.url.host];
    // Never clobber an explicit Cookie header: the Quark account exchange
    // builds its own header from the identity cookies it just received.
    final hasExplicitCookie =
        request.headers.keys.any((key) => key.toLowerCase() == 'cookie');
    if (!hasExplicitCookie && jar != null && jar.isNotEmpty) {
      request.headers['cookie'] =
          jar.entries.map((entry) => '${entry.key}=${entry.value}').join('; ');
    }
    final response = await _inner.send(request);
    final raw = response.headers['set-cookie'];
    if (raw != null && raw.isNotEmpty) {
      _ingest(request.url.host, raw);
    }
    return response;
  }

  @override
  void close() {
    _inner.close();
    super.close();
  }

  /// `package:http` folds repeated `Set-Cookie` headers into one comma-joined
  /// string, so a plain `split(',')` would also cut `Expires=Wed, 09 Jun ...`.
  /// Only split where a new `name=value` pair starts.
  void _ingest(String host, String raw) {
    final chunks = raw.split(RegExp(r',(?=\s*[A-Za-z0-9_\-]+=)'));
    for (final chunk in chunks) {
      final pair = chunk.split(';').first.trim();
      final eq = pair.indexOf('=');
      if (eq <= 0) continue;
      final name = pair.substring(0, eq).trim();
      final value = pair.substring(eq + 1).trim();
      if (name.isEmpty || _attributeNames.contains(name.toLowerCase())) continue;
      (_jar[host] ??= {})[name] = value;
    }
  }

  static const Set<String> _attributeNames = {
    'path',
    'domain',
    'expires',
    'max-age',
    'secure',
    'httponly',
    'samesite',
    'priority',
  };
}
