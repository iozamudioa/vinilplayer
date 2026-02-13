import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:web_socket_channel/web_socket_channel.dart';

import '../models/player_state.dart';

class VinilApiClient {
  VinilApiClient({http.Client? httpClient})
      : _httpClient = httpClient ?? http.Client();

  final http.Client _httpClient;
  WebSocketChannel? _stateChannel;

  Future<PlayerState> fetchState(String baseUrl, {Duration timeout = const Duration(seconds: 4)}) async {
    final uri = Uri.parse('${_normalizeBaseUrl(baseUrl)}/state');
    final response = await _httpClient.get(uri).timeout(timeout);

    if (response.statusCode != 200) {
      throw ApiException('No se pudo obtener estado (${response.statusCode})');
    }

    final jsonMap = jsonDecode(response.body) as Map<String, dynamic>;
    return PlayerState.fromJson(jsonMap);
  }

  Future<void> control({
    required String baseUrl,
    required String apiToken,
    required String action,
    double? seekSeconds,
  }) async {
    final uri = Uri.parse('${_normalizeBaseUrl(baseUrl)}/control');
    final payload = <String, dynamic>{'action': action};
    if (seekSeconds != null) {
      payload['seekSeconds'] = seekSeconds;
    }

    final response = await _httpClient
        .post(
          uri,
          headers: {
            'Content-Type': 'application/json',
            if (apiToken.trim().isNotEmpty) 'X-Api-Token': apiToken.trim(),
          },
          body: jsonEncode(payload),
        )
        .timeout(const Duration(seconds: 4));

    if (response.statusCode >= 400) {
      String message = 'Control falló (${response.statusCode})';
      try {
        final err = jsonDecode(response.body) as Map<String, dynamic>;
        message = (err['message'] ?? message).toString();
      } catch (_) {
        // ignore parse failures
      }
      throw ApiException(message);
    }
  }

  Stream<PlayerState> connectStateStream({
    required String baseUrl,
    required String apiToken,
  }) {
    disconnectStateStream();

    final uri = _buildWsUri(baseUrl, apiToken);
    final channel = WebSocketChannel.connect(uri);
    _stateChannel = channel;

    return channel.stream.map((dynamic raw) {
      final decoded = jsonDecode(raw.toString()) as Map<String, dynamic>;
      final payload = decoded['state'];
      if (payload is! Map<String, dynamic>) {
        throw ApiException('Payload WS inválido');
      }
      return PlayerState.fromJson(payload);
    });
  }

  void disconnectStateStream() {
    _stateChannel?.sink.close();
    _stateChannel = null;
  }

  String _normalizeBaseUrl(String baseUrl) {
    final trimmed = baseUrl.trim();
    if (trimmed.endsWith('/')) {
      return trimmed.substring(0, trimmed.length - 1);
    }
    return trimmed;
  }

  Uri _buildWsUri(String baseUrl, String apiToken) {
    final normalized = _normalizeBaseUrl(baseUrl);
    final httpUri = Uri.parse(normalized);

    final wsScheme = httpUri.scheme == 'https' ? 'wss' : 'ws';
    final wsPort = (httpUri.hasPort ? httpUri.port : 80) + 1;
    final path = '${httpUri.path}/ws'.replaceAll('//', '/');

    final query = <String, String>{};
    if (apiToken.trim().isNotEmpty) {
      query['token'] = apiToken.trim();
    }

    return Uri(
      scheme: wsScheme,
      host: httpUri.host,
      port: wsPort,
      path: path,
      queryParameters: query.isEmpty ? null : query,
    );
  }

  void dispose() {
    disconnectStateStream();
    _httpClient.close();
  }
}

class ApiException implements Exception {
  ApiException(this.message);

  final String message;

  @override
  String toString() => message;
}
