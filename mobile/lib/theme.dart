import 'package:flutter/material.dart';

/// Visual language.
///
/// Dark by default, and not for fashion: this app gets opened in a taxi at night, in a phone
/// held low and close. A white screen in the dark is visible from several seats away, which for
/// this product is a safety problem rather than an aesthetic one.
///
/// Red is reserved. It appears on the SOS control and on an active emergency, and nowhere else,
/// so its presence on screen always means the same thing.
class AppTheme {
  AppTheme._();

  static const Color danger = Color(0xFFE03131);
  static const Color dangerDeep = Color(0xFF8B0F16);
  static const Color safe = Color(0xFF2F9E44);
  static const Color caution = Color(0xFFE8A33D);
  static const Color surface = Color(0xFF16181C);
  static const Color surfaceRaised = Color(0xFF1F2227);
  static const Color background = Color(0xFF0E0F12);
  static const Color line = Color(0xFF2C3036);
  static const Color textPrimary = Color(0xFFF2F3F5);
  static const Color textMuted = Color(0xFF9AA1AB);

  static ThemeData build() {
    final base = ThemeData.dark(useMaterial3: true);

    return base.copyWith(
      scaffoldBackgroundColor: background,
      colorScheme: base.colorScheme.copyWith(
        primary: danger,
        secondary: safe,
        surface: surface,
        error: danger,
        onPrimary: Colors.white,
        onSurface: textPrimary,
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: background,
        foregroundColor: textPrimary,
        elevation: 0,
        centerTitle: false,
      ),
      cardTheme: CardThemeData(
        color: surfaceRaised,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(14),
          side: const BorderSide(color: line),
        ),
        margin: EdgeInsets.zero,
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: surface,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: line),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: line),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: danger, width: 1.6),
        ),
        labelStyle: const TextStyle(color: textMuted),
        hintStyle: const TextStyle(color: textMuted),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          // Large targets throughout: this is used in a hurry, possibly one-handed.
          minimumSize: const Size.fromHeight(52),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          minimumSize: const Size.fromHeight(52),
          side: const BorderSide(color: line),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        ),
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: surface,
        indicatorColor: danger.withValues(alpha: 0.18),
        labelTextStyle: const WidgetStatePropertyAll(
          TextStyle(fontSize: 11, fontWeight: FontWeight.w600),
        ),
      ),
      dividerTheme: const DividerThemeData(color: line, space: 1, thickness: 1),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: surfaceRaised,
        contentTextStyle: const TextStyle(color: textPrimary),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    );
  }

  /// Colour for an incident status. Open states read as urgent, closed ones as settled.
  static Color statusColor(String status) => switch (status) {
        'TRIGGERED' => danger,
        'ACCEPTED' || 'RESPONDING' => caution,
        'ARRIVED' => safe,
        'RESOLVED' => safe,
        'CANCELLED' => textMuted,
        _ => textMuted,
      };
}
