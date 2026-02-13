import 'dart:convert';

class PlayerState {
  PlayerState({
    required this.status,
    required this.positionSeconds,
    required this.durationSeconds,
    required this.progress,
    required this.artist,
    required this.title,
    required this.thumbnailBase64,
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
  final String thumbnailBase64;
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

  static PlayerState empty() {
    return PlayerState(
      status: 'STOPPED',
      positionSeconds: 0,
      durationSeconds: 0,
      progress: 0,
      artist: '',
      title: '',
      thumbnailBase64: '',
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
    final capabilities =
        (json['capabilities'] as Map<String, dynamic>?) ?? const {};

    return PlayerState(
      status: (playback['status'] ?? 'STOPPED').toString(),
      positionSeconds: _toDouble(playback['positionSeconds']),
      durationSeconds: _toDouble(playback['durationSeconds']),
      progress: _toDouble(playback['progress']),
      artist: (track['artist'] ?? '').toString(),
      title: (track['title'] ?? '').toString(),
      thumbnailBase64: (track['thumbnailBase64'] ?? '').toString(),
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
