import 'dart:convert';

class PlayerState {
  PlayerState({
    required this.status,
    required this.positionSeconds,
    required this.durationSeconds,
    required this.progress,
    required this.artist,
    required this.title,
    required this.source,
    required this.thumbnailBase64,
    required this.thumbnailHdBase64,
    required this.syncedLyrics,
    required this.serverActiveLyricsIndex,
    required this.canPlayPause,
    required this.canSeek,
    required this.canNext,
    required this.canPrevious,
    required this.canFocusSource,
  });

  final String status;
  final double positionSeconds;
  final double durationSeconds;
  final double progress;
  final String artist;
  final String title;
  final String source;
  final String thumbnailBase64;
  final String thumbnailHdBase64;
  final List<LyricsLine> syncedLyrics;
  final int serverActiveLyricsIndex;
  final bool canPlayPause;
  final bool canSeek;
  final bool canNext;
  final bool canPrevious;
  final bool canFocusSource;

  bool get isPlaying => status.toUpperCase() == 'PLAYING';

  bool get hasTrack => artist.trim().isNotEmpty || title.trim().isNotEmpty;

  String get trackLabel {
    if (!hasTrack) {
      return 'Sin canción activa';
    }
    return '$title — $artist';
  }

  String get preferredThumbnailBase64 {
    final hd = thumbnailHdBase64.trim();
    if (hd.isNotEmpty) {
      return hd;
    }
    return thumbnailBase64;
  }

  static PlayerState empty() {
    return PlayerState(
      status: 'STOPPED',
      positionSeconds: 0,
      durationSeconds: 0,
      progress: 0,
      artist: '',
      title: '',
      source: 'default',
      thumbnailBase64: '',
      thumbnailHdBase64: '',
      syncedLyrics: const [],
      serverActiveLyricsIndex: -1,
      canPlayPause: true,
      canSeek: true,
      canNext: true,
      canPrevious: true,
      canFocusSource: true,
    );
  }

  factory PlayerState.fromJson(Map<String, dynamic> json) {
    final playback = (json['playback'] as Map<String, dynamic>?) ?? const {};
    final track = (json['track'] as Map<String, dynamic>?) ?? const {};
    final lyrics = (json['lyrics'] as Map<String, dynamic>?) ?? const {};
    final capabilities =
        (json['capabilities'] as Map<String, dynamic>?) ?? const {};

    return PlayerState(
      status: (playback['status'] ?? 'STOPPED').toString(),
      positionSeconds: _toDouble(playback['positionSeconds']),
      durationSeconds: _toDouble(playback['durationSeconds']),
      progress: _toDouble(playback['progress']),
      artist: (track['artist'] ?? '').toString(),
      title: (track['title'] ?? '').toString(),
      source: (track['source'] ?? 'default').toString(),
      thumbnailBase64: (track['thumbnailBase64'] ?? '').toString(),
      thumbnailHdBase64: (track['thumbnailHdBase64'] ?? '').toString(),
      syncedLyrics: _parseLyricsLines(lyrics['lines']),
      serverActiveLyricsIndex: _toInt(lyrics['activeIndex'], fallback: -1),
      canPlayPause: _toBool(capabilities['canPlayPause'], fallback: true),
      canSeek: _toBool(capabilities['canSeek'], fallback: true),
      canNext: _toBool(capabilities['canNext'], fallback: true),
      canPrevious: _toBool(capabilities['canPrevious'], fallback: true),
      canFocusSource: _toBool(capabilities['canFocusSource'], fallback: true),
    );
  }

  static double _toDouble(dynamic value) {
    if (value is num) {
      return value.toDouble();
    }
    return double.tryParse(value?.toString() ?? '') ?? 0;
  }

  static bool _toBool(dynamic value, {required bool fallback}) {
    if (value is bool) {
      return value;
    }
    if (value is String) {
      if (value.toLowerCase() == 'true') {
        return true;
      }
      if (value.toLowerCase() == 'false') {
        return false;
      }
    }
    return fallback;
  }

  static int _toInt(dynamic value, {required int fallback}) {
    if (value is num) {
      return value.toInt();
    }

    final parsed = int.tryParse(value?.toString() ?? '');
    return parsed ?? fallback;
  }

  static List<LyricsLine> _parseLyricsLines(dynamic value) {
    if (value is! List) {
      return const [];
    }

    final lines = <LyricsLine>[];
    for (final entry in value) {
      if (entry is! Map) {
        continue;
      }

      final map = Map<String, dynamic>.from(entry);

      final time = _toDouble(map['timeSeconds']);
      final text = (map['text'] ?? '').toString().trim();
      if (text.isEmpty) {
        continue;
      }

      lines.add(LyricsLine(timeSeconds: time, text: text));
    }

    return lines;
  }
}

class LyricsLine {
  LyricsLine({required this.timeSeconds, required this.text});

  final double timeSeconds;
  final String text;

  static List<LyricsLine> fromLrc(String lrcText) {
    final lines = <LyricsLine>[];
    for (final raw in const LineSplitter().convert(lrcText)) {
      final trimmed = raw.trim();
      if (!trimmed.startsWith('[')) {
        continue;
      }

      final end = trimmed.indexOf(']');
      if (end <= 1) {
        continue;
      }

      final ts = trimmed.substring(1, end);
      final text = trimmed.substring(end + 1).trim();
      if (text.isEmpty) {
        continue;
      }

      final sec = _parseTimestamp(ts);
      if (sec >= 0) {
        lines.add(LyricsLine(timeSeconds: sec, text: text));
      }
    }
    return lines;
  }

  static double _parseTimestamp(String value) {
    final parts = value.split(':');
    if (parts.length != 2) {
      return -1;
    }

    final min = int.tryParse(parts[0]);
    final sec = double.tryParse(parts[1]);
    if (min == null || sec == null) {
      return -1;
    }

    return (min * 60) + sec;
  }
}
