import 'dart:io';

import 'package:audio_service/audio_service.dart';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

import 'src/services/mobile_media_session_handler.dart';
import 'src/ui/home_screen.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await _ensureNotificationPermission();

  final mediaSessionHandler = await AudioService.init(
    builder: () => MobileMediaSessionHandler(),
    config: AudioServiceConfig(
      androidNotificationChannelId: 'net.iozamudio.vinilplayer_mobile.playback',
      androidNotificationChannelName: 'VinilPlayer Playback',
      androidNotificationOngoing: true,
      androidStopForegroundOnPause: false,
    ),
  );

  runApp(VinilPlayerMobileApp(mediaSessionHandler: mediaSessionHandler));
}

Future<void> _ensureNotificationPermission() async {
  if (!Platform.isAndroid) {
    return;
  }

  final status = await Permission.notification.status;
  if (status.isGranted) {
    return;
  }

  await Permission.notification.request();
}

class VinilPlayerMobileApp extends StatelessWidget {
  const VinilPlayerMobileApp({super.key, required this.mediaSessionHandler});

  final AudioHandler mediaSessionHandler;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'VinilPlayer Mobile',
      themeMode: ThemeMode.dark,
      darkTheme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF00FFA6),
          brightness: Brightness.dark,
        ),
        scaffoldBackgroundColor: const Color(0xFF0B0E14),
      ),
      home: HomeScreen(mediaSessionHandler: mediaSessionHandler),
    );
  }
}
