import 'dart:convert';

import 'package:http/http.dart' as http;

import '../models/player_state.dart';

class VinilApiClient {
  VinilApiClient({http.Client? httpClient})
      : _httpClient = httpClient ?? http.Client();

  final http.Client _httpClient;

  Future<PlayerState> fetchState(String baseUrl) async {
    final uri = Uri.parse('${_normalizeBaseUrl(baseUrl)}/state');
    final response = await _httpClient.get(uri).timeout(
      const Duration(seconds: 4),
    );

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

  String _normalizeBaseUrl(String baseUrl) {
    final trimmed = baseUrl.trim();
    if (trimmed.endsWith('/')) {
      return trimmed.substring(0, trimmed.length - 1);
    }
    return trimmed;
  }

  void dispose() {
    _httpClient.close();
  }
}

class ApiException implements Exception {
  ApiException(this.message);

  final String message;

  @override
  String toString() => message;
}
