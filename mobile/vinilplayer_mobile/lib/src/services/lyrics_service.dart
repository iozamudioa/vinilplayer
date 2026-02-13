import 'dart:convert';

import 'package:http/http.dart' as http;

import '../models/player_state.dart';

class LyricsService {
  LyricsService({http.Client? httpClient})
      : _httpClient = httpClient ?? http.Client();

  final http.Client _httpClient;
  final Map<String, List<LyricsLine>> _cache = {};

  Future<List<LyricsLine>> getLyrics({
    required String artist,
    required String title,
  }) async {
    final key = '${artist.trim().toLowerCase()}::${title.trim().toLowerCase()}';
    if (key == '::') {
      return const [];
    }

    final cached = _cache[key];
    if (cached != null) {
      return cached;
    }

    final uri = Uri.https('lrclib.net', '/api/get', {
      'artist_name': artist,
      'track_name': title,
    });

    try {
      final response = await _httpClient
          .get(uri, headers: const {'User-Agent': 'vinilplayer-mobile/1.1'})
          .timeout(const Duration(seconds: 6));
      if (response.statusCode != 200) {
        return const [];
      }

      final map = jsonDecode(response.body) as Map<String, dynamic>;
      final syncedLyrics = (map['syncedLyrics'] ?? '').toString();
      if (syncedLyrics.trim().isEmpty) {
        return const [];
      }

      final lines = LyricsLine.fromLrc(syncedLyrics);
      _cache[key] = lines;
      return lines;
    } catch (_) {
      return const [];
    }
  }

  void dispose() {
    _httpClient.close();
  }
}
