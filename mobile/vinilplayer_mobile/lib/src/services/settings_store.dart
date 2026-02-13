import 'package:shared_preferences/shared_preferences.dart';

class AppSettings {
  AppSettings({required this.baseUrl, required this.apiToken});

  final String baseUrl;
  final String apiToken;

  AppSettings copyWith({String? baseUrl, String? apiToken}) {
    return AppSettings(
      baseUrl: baseUrl ?? this.baseUrl,
      apiToken: apiToken ?? this.apiToken,
    );
  }
}

class SettingsStore {
  static const _baseUrlKey = 'vinil_base_url';
  static const _apiTokenKey = 'vinil_api_token';

  Future<AppSettings> load() async {
    final prefs = await SharedPreferences.getInstance();
    final baseUrl =
      prefs.getString(_baseUrlKey) ?? 'http://192.168.100.27:8750/api/v1';
    final apiToken = prefs.getString(_apiTokenKey) ?? 'token-prueba';
    return AppSettings(baseUrl: baseUrl, apiToken: apiToken);
  }

  Future<void> save(AppSettings settings) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_baseUrlKey, settings.baseUrl.trim());
    await prefs.setString(_apiTokenKey, settings.apiToken.trim());
  }
}
