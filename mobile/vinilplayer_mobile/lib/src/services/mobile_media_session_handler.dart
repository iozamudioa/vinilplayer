import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:audio_service/audio_service.dart';

import '../models/player_state.dart';

class MobileMediaSessionHandler extends BaseAudioHandler with SeekHandler {
  Future<void> Function()? _onPlayPause;
  Future<void> Function()? _onNext;
  Future<void> Function()? _onPrevious;
  Future<void> Function(double seconds)? _onSeek;
  String _lastArtworkKey = '';
  Uri? _lastArtworkUri;

  void setCallbacks({
    Future<void> Function()? onPlayPause,
    Future<void> Function()? onNext,
    Future<void> Function()? onPrevious,
    Future<void> Function(double seconds)? onSeek,
  }) {
    _onPlayPause = onPlayPause;
    _onNext = onNext;
    _onPrevious = onPrevious;
    _onSeek = onSeek;
  }

  Future<void> updateFromState(PlayerState state) async {
    final duration = state.durationSeconds > 0
        ? Duration(milliseconds: (state.durationSeconds * 1000).round())
        : null;
    final artworkUri = await _resolveArtworkUri(state.preferredThumbnailBase64);

    mediaItem.add(
      MediaItem(
        id: '${state.artist}::${state.title}',
        title: state.title.isEmpty ? 'Sin canción activa' : state.title,
        artist: state.artist.isEmpty ? 'VinilPlayer' : state.artist,
        duration: duration,
        artUri: artworkUri,
      ),
    );

    final controls = <MediaControl>[];
    if (state.canPrevious) {
      controls.add(MediaControl.skipToPrevious);
    }
    controls.add(state.isPlaying ? MediaControl.pause : MediaControl.play);
    if (state.canNext) {
      controls.add(MediaControl.skipToNext);
    }

    playbackState.add(
      PlaybackState(
        controls: controls,
        systemActions: {
          if (state.canSeek) MediaAction.seek,
          MediaAction.play,
          MediaAction.pause,
          if (state.canNext) MediaAction.skipToNext,
          if (state.canPrevious) MediaAction.skipToPrevious,
        },
        androidCompactActionIndices: _compactIndices(controls.length),
        processingState: AudioProcessingState.ready,
        playing: state.isPlaying,
        updatePosition:
            Duration(milliseconds: (state.positionSeconds * 1000).round()),
        speed: 1.0,
      ),
    );
  }

  @override
  Future<void> play() async {
    await _onPlayPause?.call();
  }

  @override
  Future<void> pause() async {
    await _onPlayPause?.call();
  }

  @override
  Future<void> skipToNext() async {
    await _onNext?.call();
  }

  @override
  Future<void> skipToPrevious() async {
    await _onPrevious?.call();
  }

  @override
  Future<void> seek(Duration position) async {
    await _onSeek?.call(position.inMilliseconds / 1000.0);
  }

  List<int> _compactIndices(int controlsCount) {
    if (controlsCount <= 0) {
      return const [];
    }
    if (controlsCount == 1) {
      return const [0];
    }
    if (controlsCount == 2) {
      return const [0, 1];
    }
    return const [0, 1, 2];
  }

  Future<Uri?> _resolveArtworkUri(String thumbnailBase64) async {
    final normalized = thumbnailBase64.trim();
    if (normalized.isEmpty) {
      _lastArtworkKey = '';
      _lastArtworkUri = null;
      return null;
    }

    if (_lastArtworkKey == normalized && _lastArtworkUri != null) {
      return _lastArtworkUri;
    }

    try {
      final rawBytes = base64Decode(normalized);
      final bytes = await _prepareArtworkBytes(rawBytes);
      final cacheDir = Directory.systemTemp;
      final file = File('${cacheDir.path}/vinilplayer-artwork-${normalized.hashCode}.png');
      await file.writeAsBytes(bytes, flush: true);
      _lastArtworkKey = normalized;
      _lastArtworkUri = Uri.file(file.path);
      return _lastArtworkUri;
    } catch (_) {
      _lastArtworkKey = '';
      _lastArtworkUri = null;
      return null;
    }
  }

  Future<Uint8List> _prepareArtworkBytes(Uint8List rawBytes) async {
    try {
      final probeCodec = await ui.instantiateImageCodec(rawBytes);
      final probeFrame = await probeCodec.getNextFrame();
      final image = probeFrame.image;
      final width = image.width;
      final height = image.height;
      final minDimension = width < height ? width : height;

      if (minDimension >= 512) {
        return rawBytes;
      }

      final scale = 512.0 / minDimension;
      final targetWidth = (width * scale).round();
      final targetHeight = (height * scale).round();

      final upscaleCodec = await ui.instantiateImageCodec(
        rawBytes,
        targetWidth: targetWidth,
        targetHeight: targetHeight,
      );
      final upscaleFrame = await upscaleCodec.getNextFrame();
      final byteData = await upscaleFrame.image.toByteData(
        format: ui.ImageByteFormat.png,
      );

      if (byteData == null) {
        return rawBytes;
      }

      return byteData.buffer.asUint8List();
    } catch (_) {
      return rawBytes;
    }
  }
}
