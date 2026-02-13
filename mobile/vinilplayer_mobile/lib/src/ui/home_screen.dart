import 'dart:convert';

import 'package:flutter/material.dart';

import '../controllers/player_controller.dart';
import '../models/player_state.dart';
import '../services/lyrics_service.dart';
import '../services/settings_store.dart';
import '../services/vinil_api_client.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  late final PlayerController _controller;
  late final PageController _pageController;

  @override
  void initState() {
    super.initState();
    _pageController = PageController();
    _controller = PlayerController(
      settingsStore: SettingsStore(),
      apiClient: VinilApiClient(),
      lyricsService: LyricsService(),
    )..addListener(_onControllerUpdated);
    _controller.initialize();
  }

  @override
  void dispose() {
    _controller.removeListener(_onControllerUpdated);
    _controller.dispose();
    _pageController.dispose();
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

    if (mounted) {
      setState(() {});
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = _controller.state;

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
                _buildHeader(state),
                _buildTabToggle(),
                Expanded(
                  child: PageView(
                    controller: _pageController,
                    onPageChanged: _controller.setActivePage,
                    children: [
                      _buildPlayerView(state),
                      _buildLyricsView(),
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

  Widget _buildHeader(PlayerState state) {
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Row(
        children: [
          _buildCover(state.thumbnailBase64),
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
                      color: state.isPlaying ? const Color(0xFF00FFA6) : Colors.white70,
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

  Widget _buildCover(String base64Thumbnail) {
    if (base64Thumbnail.trim().isEmpty) {
      return Container(
        width: 72,
        height: 72,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(14),
          color: const Color(0xFF1A1F2B),
        ),
        child: const Icon(Icons.album, size: 34),
      );
    }

    try {
      final bytes = base64Decode(base64Thumbnail);
      return ClipRRect(
        borderRadius: BorderRadius.circular(14),
        child: Image.memory(
          bytes,
          width: 72,
          height: 72,
          fit: BoxFit.cover,
          gaplessPlayback: true,
        ),
      );
    } catch (_) {
      return Container(
        width: 72,
        height: 72,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(14),
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

  Widget _buildPlayerView(PlayerState state) {
    final duration = state.durationSeconds;
    final position = state.positionSeconds.clamp(0, duration <= 0 ? 1 : duration);

    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        children: [
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
                iconSize: 34,
              ),
              const SizedBox(width: 12),
              IconButton.filled(
                onPressed: state.canPlayPause && !_controller.sendingCommand
                    ? _controller.playPause
                    : null,
                icon: Icon(state.isPlaying ? Icons.pause : Icons.play_arrow),
                iconSize: 38,
                style: IconButton.styleFrom(
                  padding: const EdgeInsets.all(16),
                ),
              ),
              const SizedBox(width: 12),
              IconButton.filledTonal(
                onPressed: state.canNext && !_controller.sendingCommand
                    ? _controller.next
                    : null,
                icon: const Icon(Icons.skip_next),
                iconSize: 34,
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

  Widget _buildLyricsView() {
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
      itemCount: lyrics.length,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      itemBuilder: (context, index) {
        final line = lyrics[index];
        final active = index == _controller.activeLyricsLineIndex;

        return ListTile(
          dense: true,
          title: Text(
            line.text,
            style: TextStyle(
              fontWeight: active ? FontWeight.w700 : FontWeight.w400,
              color: active ? const Color(0xFF00FFA6) : Colors.white,
            ),
          ),
          subtitle: Text(_formatTime(line.timeSeconds)),
          onTap: () => _controller.seekToLyricsLine(line),
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
}
