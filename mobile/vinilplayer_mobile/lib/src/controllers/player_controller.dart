import 'dart:async';

import 'package:audio_service/audio_service.dart';
import 'package:flutter/foundation.dart';

import '../models/player_state.dart';
import '../services/lyrics_service.dart';
import '../services/mobile_media_session_handler.dart';
import '../services/settings_store.dart';
import '../services/vinil_api_client.dart';

class PlayerController extends ChangeNotifier {
  PlayerController({
    required SettingsStore settingsStore,
    required VinilApiClient apiClient,
    required LyricsService lyricsService,
    required AudioHandler mediaSessionHandler,
  })  : _settingsStore = settingsStore,
        _apiClient = apiClient,
        _lyricsService = lyricsService,
        _mediaSessionHandler = mediaSessionHandler;

  final SettingsStore _settingsStore;
  final VinilApiClient _apiClient;
  final LyricsService _lyricsService;
  final AudioHandler _mediaSessionHandler;

  AppSettings? settings;
  PlayerState state = PlayerState.empty();
  List<LyricsLine> lyrics = const [];

  bool loading = true;
  bool sendingCommand = false;
  String? error;
  int activePage = 0;
  int activeLyricsLineIndex = -1;

  Timer? _pollingTimer;
  Timer? _reconnectTimer;
  StreamSubscription<PlayerState>? _stateStreamSubscription;
  String _lyricsTrackKey = '';
  bool _wsConnected = false;
  bool _refreshInFlight = false;
  bool _connectingStream = false;
  int _reconnectAttempts = 0;

  Future<void> initialize() async {
    settings = await _settingsStore.load();

    final handler = _mediaSessionHandler;
    if (handler is MobileMediaSessionHandler) {
      handler.setCallbacks(
        onPlayPause: playPause,
        onNext: next,
        onPrevious: previous,
        onSeek: seek,
      );
    }

    loading = false;
    notifyListeners();
    await refreshState();
    _connectStateStream();
    _startPolling();
  }

  Future<void> refreshState({Duration timeout = const Duration(seconds: 4)}) async {
    final current = settings;
    if (current == null) {
      return;
    }

    if (_refreshInFlight) {
      return;
    }

    _refreshInFlight = true;

    try {
      final nextState = await _apiClient.fetchState(current.baseUrl, timeout: timeout);
      await _applyState(nextState);
      error = null;
    } catch (e) {
      error = e.toString();
    } finally {
      _refreshInFlight = false;
    }

    notifyListeners();
  }

  Future<void> saveSettings(AppSettings nextSettings) async {
    settings = nextSettings;
    await _settingsStore.save(nextSettings);
    _disconnectStateStream();
    await refreshState();
    _connectStateStream();
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
    if (state.syncedLyrics.isNotEmpty) {
      lyrics = state.syncedLyrics;
      final key = '${state.artist.trim().toLowerCase()}::${state.title.trim().toLowerCase()}';
      _lyricsTrackKey = key;
      return;
    }

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
    if (state.serverActiveLyricsIndex >= 0 && state.syncedLyrics.isNotEmpty) {
      activeLyricsLineIndex = state.serverActiveLyricsIndex;
      return;
    }

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
    _pollingTimer = Timer.periodic(const Duration(milliseconds: 850), (_) async {
      if (_wsConnected) {
        return;
      }
      await refreshState(timeout: const Duration(milliseconds: 1200));
      if (!_wsConnected) {
        _connectStateStream();
      }
    });
  }

  void _connectStateStream() {
    final current = settings;
    if (current == null) {
      return;
    }

    if (_connectingStream) {
      return;
    }

    _connectingStream = true;

    _stateStreamSubscription?.cancel();
    _reconnectTimer?.cancel();

    _stateStreamSubscription = _apiClient
        .connectStateStream(
          baseUrl: current.baseUrl,
          apiToken: current.apiToken,
        )
        .listen(
          (nextState) async {
            _connectingStream = false;
            _wsConnected = true;
            _reconnectAttempts = 0;
            await _applyState(nextState);
            error = null;
            notifyListeners();
          },
          onError: (Object streamError) {
            _connectingStream = false;
            _wsConnected = false;
            error = streamError.toString();
            notifyListeners();
            _scheduleReconnect();
          },
          onDone: () {
            _connectingStream = false;
            _wsConnected = false;
            _scheduleReconnect();
          },
          cancelOnError: true,
        );
  }

  void _scheduleReconnect() {
    _reconnectTimer?.cancel();
    final attempt = _reconnectAttempts++;
    final delayMs = switch (attempt) {
      0 => 500,
      1 => 900,
      2 => 1400,
      3 => 2000,
      _ => 2800,
    };
    _reconnectTimer = Timer(Duration(milliseconds: delayMs), _connectStateStream);
  }

  Future<void> forceReconnect() async {
    _disconnectStateStream();
    await refreshState(timeout: const Duration(milliseconds: 1200));
    _connectStateStream();
  }

  void _disconnectStateStream() {
    _reconnectTimer?.cancel();
    _stateStreamSubscription?.cancel();
    _stateStreamSubscription = null;
    _wsConnected = false;
    _connectingStream = false;
    _apiClient.disconnectStateStream();
  }

  Future<void> _applyState(PlayerState nextState) async {
    state = nextState;
    await _syncLyricsForTrack();
    _syncActiveLyricLine();

    final handler = _mediaSessionHandler;
    if (handler is MobileMediaSessionHandler) {
      await handler.updateFromState(state);
    }
  }

  @override
  void dispose() {
    final handler = _mediaSessionHandler;
    if (handler is MobileMediaSessionHandler) {
      handler.setCallbacks();
    }

    _disconnectStateStream();
    _pollingTimer?.cancel();
    _apiClient.dispose();
    _lyricsService.dispose();
    super.dispose();
  }
}
