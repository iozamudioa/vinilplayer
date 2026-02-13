import 'dart:convert';
import 'dart:math' as math;

import 'package:audio_service/audio_service.dart';
import 'package:flutter/material.dart';

import '../controllers/player_controller.dart';
import '../models/player_state.dart';
import '../services/lyrics_service.dart';
import '../services/settings_store.dart';
import '../services/vinil_api_client.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.mediaSessionHandler});

  final AudioHandler mediaSessionHandler;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen>
  with SingleTickerProviderStateMixin, WidgetsBindingObserver {
  late final PlayerController _controller;
  late final PageController _pageController;
  late final ScrollController _lyricsScrollController;
  late final AnimationController _vinylRotationController;
  int _lastAutoScrolledLyricsIndex = -1;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _pageController = PageController();
    _lyricsScrollController = ScrollController();
    _vinylRotationController = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 6),
    );
    _controller = PlayerController(
      settingsStore: SettingsStore(),
      apiClient: VinilApiClient(),
      lyricsService: LyricsService(),
      mediaSessionHandler: widget.mediaSessionHandler,
    )..addListener(_onControllerUpdated);
    _controller.initialize();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _controller.removeListener(_onControllerUpdated);
    _controller.dispose();
    _pageController.dispose();
    _lyricsScrollController.dispose();
    _vinylRotationController.dispose();
    super.dispose();
  }

  void _onControllerUpdated() {
    if (_pageController.hasClients &&
        _pageController.page?.round() != _controller.activePage) {
      _pageController.animateToPage(
        _controller.activePage,
        duration: const Duration(milliseconds: 240),
        curve: Curves.easeOutCubic,
      );
    }

    if (_controller.state.isPlaying) {
      if (!_vinylRotationController.isAnimating) {
        _vinylRotationController.repeat();
      }
    } else {
      _vinylRotationController.stop();
    }

    if (mounted) {
      setState(() {});
      _scheduleLyricsAutoScroll();
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _controller.forceReconnect();
    }
  }

  void _scheduleLyricsAutoScroll() {
    final lyrics = _controller.lyrics;
    final activeIndex = _controller.activeLyricsLineIndex;
    if (lyrics.isEmpty || activeIndex < 0 || activeIndex >= lyrics.length) {
      return;
    }
    if (activeIndex == _lastAutoScrolledLyricsIndex) {
      return;
    }
    _lastAutoScrolledLyricsIndex = activeIndex;

    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_lyricsScrollController.hasClients) {
        return;
      }

      const itemExtent = 78.0;
      final viewport = _lyricsScrollController.position.viewportDimension;
      final targetOffset = (activeIndex * itemExtent) - ((viewport - itemExtent) / 2);
      final clampedOffset = targetOffset.clamp(
        0.0,
        _lyricsScrollController.position.maxScrollExtent,
      );

      _lyricsScrollController.animateTo(
        clampedOffset,
        duration: const Duration(milliseconds: 320),
        curve: Curves.easeOutCubic,
      );
    });
  }

  @override
  Widget build(BuildContext context) {
    final state = _controller.state;
    final palette = _paletteForSource(state.source);

    return Scaffold(
      appBar: AppBar(
        title: const Text('VinilPlayer Mobile'),
        actions: [
          IconButton(
            onPressed: () => _openSettings(context),
            icon: const Icon(Icons.settings),
          ),
        ],
      ),
      body: _controller.loading
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                if (_controller.error != null) _buildErrorBanner(_controller.error!),
                _buildHeader(state, palette),
                _buildTabToggle(),
                Expanded(
                  child: PageView(
                    controller: _pageController,
                    onPageChanged: _controller.setActivePage,
                    children: [
                      _buildPlayerView(state, palette),
                      _buildLyricsView(palette),
                    ],
                  ),
                ),
              ],
            ),
    );
  }

  Widget _buildErrorBanner(String errorText) {
    return Material(
      color: Colors.red.withValues(alpha: 0.18),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        child: Row(
          children: [
            const Icon(Icons.warning_amber_rounded, color: Colors.redAccent),
            const SizedBox(width: 8),
            Expanded(child: Text(errorText, maxLines: 2, overflow: TextOverflow.ellipsis)),
            IconButton(
              icon: const Icon(Icons.refresh),
              onPressed: _controller.refreshState,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildHeader(PlayerState state, _SourcePalette palette) {
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Row(
        children: [
          _buildCover(state.preferredThumbnailBase64),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  state.title.isEmpty ? 'Sin canción activa' : state.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 18),
                ),
                const SizedBox(height: 4),
                Text(
                  state.artist.isEmpty ? 'Esperando sesión multimedia' : state.artist,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(color: Colors.white.withValues(alpha: 0.74)),
                ),
                const SizedBox(height: 10),
                Row(
                  children: [
                    Icon(
                      state.isPlaying ? Icons.play_circle_fill : Icons.pause_circle_filled,
                      color: state.isPlaying ? palette.accent : Colors.white70,
                    ),
                    const SizedBox(width: 6),
                    Text(state.status),
                  ],
                ),
              ],
            ),
          )
        ],
      ),
    );
  }

  Widget _buildCover(String base64Thumbnail, {double size = 72, double radius = 14}) {
    if (base64Thumbnail.trim().isEmpty) {
      return Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(radius),
          color: const Color(0xFF1A1F2B),
        ),
        child: const Icon(Icons.album, size: 34),
      );
    }

    try {
      final bytes = base64Decode(base64Thumbnail);
      return ClipRRect(
        borderRadius: BorderRadius.circular(radius),
        child: Image.memory(
          bytes,
          width: size,
          height: size,
          fit: BoxFit.cover,
          gaplessPlayback: true,
        ),
      );
    } catch (_) {
      return Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(radius),
          color: const Color(0xFF1A1F2B),
        ),
        child: const Icon(Icons.broken_image_outlined, size: 32),
      );
    }
  }

  Widget _buildTabToggle() {
    final selected = _controller.activePage;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: SegmentedButton<int>(
        segments: const [
          ButtonSegment<int>(value: 0, icon: Icon(Icons.graphic_eq), label: Text('Reproductor')),
          ButtonSegment<int>(value: 1, icon: Icon(Icons.lyrics), label: Text('Lyrics')),
        ],
        selected: {selected},
        onSelectionChanged: (values) {
          _controller.setActivePage(values.first);
        },
      ),
    );
  }

  Widget _buildPlayerView(PlayerState state, _SourcePalette palette) {
    final duration = state.durationSeconds;
    final position = state.positionSeconds.clamp(0, duration <= 0 ? 1 : duration);

    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        children: [
          const SizedBox(height: 4),
          _buildVinylDisc(state, palette),
          const SizedBox(height: 14),
          Slider(
            value: position.toDouble(),
            max: duration <= 0 ? 1 : duration,
            onChanged: state.canSeek
                ? (value) => _controller.seek(value)
                : null,
          ),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(_formatTime(state.positionSeconds)),
              Text(_formatTime(state.durationSeconds)),
            ],
          ),
          const SizedBox(height: 20),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              IconButton.filledTonal(
                onPressed: state.canPrevious && !_controller.sendingCommand
                    ? _controller.previous
                    : null,
                icon: const Icon(Icons.skip_previous),
                iconSize: 40,
                style: IconButton.styleFrom(padding: const EdgeInsets.all(16)),
              ),
              const SizedBox(width: 12),
              IconButton.filled(
                onPressed: state.canPlayPause && !_controller.sendingCommand
                    ? _controller.playPause
                    : null,
                icon: Icon(state.isPlaying ? Icons.pause : Icons.play_arrow),
                iconSize: 46,
                style: IconButton.styleFrom(
                  backgroundColor: palette.accent,
                  foregroundColor: Colors.black,
                  padding: const EdgeInsets.all(18),
                ),
              ),
              const SizedBox(width: 12),
              IconButton.filledTonal(
                onPressed: state.canNext && !_controller.sendingCommand
                    ? _controller.next
                    : null,
                icon: const Icon(Icons.skip_next),
                iconSize: 40,
                style: IconButton.styleFrom(padding: const EdgeInsets.all(16)),
              ),
            ],
          ),
          const SizedBox(height: 18),
          FilledButton.tonalIcon(
            onPressed: state.canFocusSource && !_controller.sendingCommand
                ? _controller.focusSource
                : null,
            icon: const Icon(Icons.open_in_new),
            label: const Text('Abrir fuente actual'),
          ),
          const SizedBox(height: 14),
          OutlinedButton.icon(
            onPressed: _controller.refreshState,
            icon: const Icon(Icons.refresh),
            label: const Text('Refrescar estado'),
          ),
        ],
      ),
    );
  }

  Widget _buildVinylDisc(PlayerState state, _SourcePalette palette) {
    return AnimatedBuilder(
      animation: _vinylRotationController,
      builder: (context, child) {
        return Transform.rotate(
          angle: _vinylRotationController.value * 2 * math.pi,
          child: child,
        );
      },
      child: Container(
        width: 220,
        height: 220,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          gradient: RadialGradient(
            colors: [palette.discOuter, palette.discInner],
            stops: [0.05, 1],
          ),
        ),
        child: Center(
          child: Container(
            width: 150,
            height: 150,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: palette.label,
            ),
            child: Center(
              child: ClipOval(
                child: SizedBox(
                  width: 132,
                  height: 132,
                  child: _buildCover(state.preferredThumbnailBase64, size: 132, radius: 66),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildLyricsView(_SourcePalette palette) {
    final lyrics = _controller.lyrics;
    if (lyrics.isEmpty) {
      return const Center(
        child: Text(
          'Sin lyrics sincronizadas para la canción actual',
          textAlign: TextAlign.center,
        ),
      );
    }

    return ListView.builder(
      controller: _lyricsScrollController,
      itemExtent: 78,
      itemCount: lyrics.length,
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
      itemBuilder: (context, index) {
        final line = lyrics[index];
        final active = index == _controller.activeLyricsLineIndex;

        return InkWell(
          onTap: () => _controller.seekToLyricsLine(line),
          child: Center(
            child: AnimatedDefaultTextStyle(
              duration: const Duration(milliseconds: 180),
              style: TextStyle(
                fontSize: active ? 32 : 27,
                fontWeight: active ? FontWeight.w700 : FontWeight.w500,
                color: active ? palette.accent : Colors.white,
                height: 1.15,
              ),
              child: Text(
                line.text,
                textAlign: TextAlign.center,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ),
        );
      },
    );
  }

  Future<void> _openSettings(BuildContext context) async {
    final current = _controller.settings;
    if (current == null) {
      return;
    }

    final baseCtrl = TextEditingController(text: current.baseUrl);
    final tokenCtrl = TextEditingController(text: current.apiToken);

    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      builder: (context) {
        return Padding(
          padding: EdgeInsets.only(
            left: 16,
            right: 16,
            top: 18,
            bottom: MediaQuery.of(context).viewInsets.bottom + 18,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('Conexión VinilPlayer API', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
              const SizedBox(height: 12),
              TextField(
                controller: baseCtrl,
                decoration: const InputDecoration(
                  labelText: 'Base URL',
                  hintText: 'http://192.168.1.100:8750/api/v1',
                ),
              ),
              const SizedBox(height: 10),
              TextField(
                controller: tokenCtrl,
                decoration: const InputDecoration(
                  labelText: 'API Token (X-Api-Token)',
                ),
              ),
              const SizedBox(height: 14),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: () => Navigator.of(context).pop(true),
                  child: const Text('Guardar'),
                ),
              ),
            ],
          ),
        );
      },
    );

    if (saved == true) {
      await _controller.saveSettings(
        AppSettings(baseUrl: baseCtrl.text, apiToken: tokenCtrl.text),
      );
    }
  }

  String _formatTime(double seconds) {
    final safe = seconds < 0 ? 0 : seconds.floor();
    final minutes = safe ~/ 60;
    final rem = safe % 60;
    return '$minutes:${rem.toString().padLeft(2, '0')}';
  }

  _SourcePalette _paletteForSource(String source) {
    switch (source.trim().toLowerCase()) {
      case 'youtube_music':
        return const _SourcePalette(
          accent: Color(0xFFFF0000),
          discOuter: Color(0xFF2A1111),
          discInner: Color(0xFF120909),
          label: Color(0xFF2B1515),
        );
      case 'spotify':
        return const _SourcePalette(
          accent: Color(0xFF1DB954),
          discOuter: Color(0xFF102117),
          discInner: Color(0xFF0A120D),
          label: Color(0xFF16271C),
        );
      case 'apple_music':
        return const _SourcePalette(
          accent: Color(0xFFFA4378),
          discOuter: Color(0xFF28131C),
          discInner: Color(0xFF120A0E),
          label: Color(0xFF2B1620),
        );
      case 'amazon_music':
        return const _SourcePalette(
          accent: Color(0xFF00C2FF),
          discOuter: Color(0xFF0D1C24),
          discInner: Color(0xFF081018),
          label: Color(0xFF13212B),
        );
      default:
        return const _SourcePalette(
          accent: Color(0xFF00FFA6),
          discOuter: Color(0xFF1A1F2B),
          discInner: Color(0xFF090B10),
          label: Color(0xFF111622),
        );
    }
  }
}

class _SourcePalette {
  const _SourcePalette({
    required this.accent,
    required this.discOuter,
    required this.discInner,
    required this.label,
  });

  final Color accent;
  final Color discOuter;
  final Color discInner;
  final Color label;
}
