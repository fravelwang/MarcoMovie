import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:marco_movie/core/cookie_client.dart';

/// The Quark QR login binds the token to the cookies returned when the token
/// was created: polling without them answers `50004002 Token Not Found` instead
/// of `50004001 Query result is empty`. These tests pin the client behaviour
/// that makes the second request carry the first request's cookies.
void main() {
  late HttpServer server;
  late String base;

  Future<void> start(
    Future<void> Function(HttpRequest request) handler,
  ) async {
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    base = 'http://127.0.0.1:${server.port}';
    server.listen((request) async {
      await handler(request);
      await request.response.close();
    });
  }

  tearDown(() async {
    await server.close(force: true);
  });

  test('sends cookies set by an earlier response to the same host', () async {
    final seen = <String>[];
    await start((request) async {
      if (request.uri.path == '/create') {
        request.response.headers.add(
          'set-cookie',
          '_UP_28A_52_=532; Path=/; HttpOnly',
        );
        request.response.write('created');
      } else {
        seen.add(request.headers.value('cookie') ?? '');
        request.response.write('polled');
      }
    });

    final client = CookieClient();
    addTearDown(client.close);

    final create = await client.get(Uri.parse('$base/create'));
    final poll = await client.get(Uri.parse('$base/poll'));

    expect(create.statusCode, 200);
    expect(poll.statusCode, 200);
    expect(seen.single, contains('_UP_28A_52_=532'));
  });

  test('keeps cookies per host', () async {
    final seen = <String>[];
    await start((request) async {
      if (request.uri.path == '/create') {
        request.response.headers.add(
          'set-cookie',
          '_UP_28A_52_=532; Path=/',
        );
      } else {
        seen.add(request.headers.value('cookie') ?? '');
      }
      request.response.write('ok');
    });

    final client = CookieClient();
    addTearDown(client.close);
    await client.get(Uri.parse('$base/create'));
    // 127.0.0.1 与 localhost 在 jar 里是两个键，不能互相带上。
    await client.get(Uri.parse('http://localhost:${server.port}/poll'));

    expect(seen.single, isNot(contains('_UP_28A_52_')));
  });

  test('clear() drops the previous attempt cookies', () async {
    final seen = <String>[];
    await start((request) async {
      if (request.uri.path == '/create') {
        request.response.headers.add(
          'set-cookie',
          '_UP_28A_52_=532; Path=/',
        );
      } else {
        seen.add(request.headers.value('cookie') ?? '');
      }
      request.response.write('ok');
    });

    final client = CookieClient();
    addTearDown(client.close);
    await client.get(Uri.parse('$base/create'));
    client.clear();
    await client.get(Uri.parse('$base/poll'));

    expect(seen.single, isEmpty);
  });

  test('parses folded Set-Cookie headers without splitting Expires', () async {
    final seen = <String>[];
    await start((request) async {
      if (request.uri.path == '/create') {
        request.response.headers.add(
          'set-cookie',
          'a=1; Expires=Wed, 09 Jun 2027 10:18:14 GMT; Path=/',
        );
        request.response.headers.add('set-cookie', 'b=2; Path=/');
      } else {
        seen.add(request.headers.value('cookie') ?? '');
      }
      request.response.write('ok');
    });

    final client = CookieClient();
    addTearDown(client.close);
    await client.get(Uri.parse('$base/create'));
    await client.get(Uri.parse('$base/poll'));

    final cookie = seen.single;
    expect(cookie, contains('a=1'));
    expect(cookie, contains('b=2'));
    expect(cookie, isNot(contains('09 Jun')));
  });
}
