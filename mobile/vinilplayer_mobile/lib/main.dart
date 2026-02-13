import 'package:flutter/material.dart';

import 'src/ui/home_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const VinilPlayerMobileApp());
}

class VinilPlayerMobileApp extends StatelessWidget {
  const VinilPlayerMobileApp({super.key});

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
      home: const HomeScreen(),
    );
  }
}
