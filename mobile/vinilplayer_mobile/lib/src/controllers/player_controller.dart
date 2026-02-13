import 'dart:async';

import 'package:flutter/foundation.dart';

import '../models/player_state.dart';
import '../services/lyrics_service.dart';
import '../services/settings_store.dart';
import '../services/vinil_api_client.dart';

class PlayerController extends ChangeNotifier {
  PlayerController({
    required SettingsStore settingsStore,
    required VinilApiClient apiClient,
    required LyricsService lyricsService,
  })  : _settingsStore = settingsStore,
        _apiClient = apiClient,
        _lyricsService = lyricsService;

  final SettingsStore _settingsStore;
  final VinilApiClient _apiClient;
  final LyricsService _lyricsService;

  AppSettings? settings;
  PlayerState state = PlayerState.empty();
  List<LyricsLine> lyrics = const [];

  bool loading = true;
  bool sendingCommand = false;
  String? error;
  int activePage = 0;
  int activeLyricsLineIndex = -1;

  Timer? _pollingTimer;
  String _lyricsTrackKey = '';

  Future<void> initialize() async {
    settings = await _settingsStore.load();
    loading = false;
    notifyListeners();
    await refreshState();
    _startPolling();
  }

  Future<void> refreshState() async {
    final current = settings;
    if (current == null) {
      return;
    }

    try {
      final nextState = await _apiClient.fetchState(current.baseUrl);
      state = nextState;
      error = null;
      await _syncLyricsForTrack();
      _syncActiveLyricLine();
    } catch (e) {
      error = e.toString();
    }

    notifyListeners();
  }

  Future<void> saveSettings(AppSettings nextSettings) async {
    settings = nextSettings;
    await _settingsStore.save(nextSettings);
    await refreshState();
  }

  void setActivePage(int index) {
    activePage = index;
    notifyListeners();
  }

  Future<void> playPause() async => _sendControl('playpause');

  Future<void> next() async => _sendControl('next');

  Future<void> previous() async => _sendControl('previous');

  Future<void> focusSource() async => _sendControl('focussource');

  Future<void> seek(double seconds) async => _sendControl('seek', seekSeconds: seconds);

  Future<void> seekToLyricsLine(LyricsLine line) async {
    await seek(line.timeSeconds);
  }

  Future<void> _sendControl(String action, {double? seekSeconds}) async {
    final current = settings;
    if (current == null) {
      return;
    }

    try {
      sendingCommand = true;
      notifyListeners();

      await _apiClient.control(
        baseUrl: current.baseUrl,
        apiToken: current.apiToken,
        action: action,
        seekSeconds: seekSeconds,
      );

      error = null;
    } catch (e) {
      error = e.toString();
    } finally {
      sendingCommand = false;
      notifyListeners();
    }

    await refreshState();
  }

  Future<void> _syncLyricsForTrack() async {
    final key = '${state.artist.trim().toLowerCase()}::${state.title.trim().toLowerCase()}';
    if (key == '::') {
      lyrics = const [];
      _lyricsTrackKey = key;
      activeLyricsLineIndex = -1;
      return;
    }

    if (_lyricsTrackKey == key) {
      return;
    }

    _lyricsTrackKey = key;
    lyrics = await _lyricsService.getLyrics(
      artist: state.artist,
      title: state.title,
    );
  }

  void _syncActiveLyricLine() {
    if (lyrics.isEmpty) {
      activeLyricsLineIndex = -1;
      return;
    }

    int found = -1;
    for (int i = 0; i < lyrics.length; i++) {
      if (state.positionSeconds >= lyrics[i].timeSeconds) {
        found = i;
      } else {
        break;
      }
    }
    activeLyricsLineIndex = found;
  }

  void _startPolling() {
    _pollingTimer?.cancel();
    _pollingTimer = Timer.periodic(const Duration(milliseconds: 700), (_) {
      refreshState();
    });
  }

  @override
  void dispose() {
    _pollingTimer?.cancel();
    _apiClient.dispose();
    _lyricsService.dispose();
    super.dispose();
  }
}
