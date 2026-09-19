import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../config.dart';
import '../theme.dart';

/// The SOS control: press and hold for three seconds.
///
/// A hold rather than a tap, because this button lives on the first screen of an app that is
/// opened in a pocket and a handbag. A single tap would fire constantly by accident, and a
/// trusted-contact list that has cried wolf three times is a list nobody answers.
///
/// Four things make the hold usable under stress:
///
/// - **The progress is visible and continuous**, so the length of the hold is never a guess.
///   The glow grows with it, which is legible in peripheral vision - you can feel the button
///   filling without looking straight at it.
/// - **The ring closing is marked.** At the moment the circle completes there is a flash and a
///   pulse outward, so "it has gone" is unmistakable even at a glance.
/// - **Releasing early cancels, silently**, with no scolding dialogue. Someone who let go by
///   accident needs to try again, not read a message.
/// - **Haptics carry the whole story** - a tick on press, a bump as the ring closes, and a
///   firm triple pulse once the alert is away. This is the only feedback channel a silent
///   alarm can use, so it does the work that sound would normally do.
class HoldButton extends StatefulWidget {
  const HoldButton({
    super.key,
    required this.onFired,
    required this.idleLabel,
    required this.holdingLabel,
    required this.busyLabel,
    this.onHoldStarted,
    this.onRingClosed,
    this.enabled = true,
    this.busy = false,
    this.holdDuration = AppConfig.sosHoldDuration,
  });

  /// Called once, when the hold completes.
  final VoidCallback onFired;
  final String idleLabel;
  final String holdingLabel;
  final String busyLabel;

  /// Fired as the press registers, for a light confirming tick.
  final VoidCallback? onHoldStarted;

  /// Fired the instant the ring closes, a beat before [onFired] does the network call.
  final VoidCallback? onRingClosed;

  final bool enabled;

  /// While true the button shows the busy label and ignores input.
  final bool busy;
  final Duration holdDuration;

  @override
  State<HoldButton> createState() => _HoldButtonState();
}

class _HoldButtonState extends State<HoldButton> with TickerProviderStateMixin {
  late final AnimationController _hold = AnimationController(
    vsync: this,
    duration: widget.holdDuration,
  )..addStatusListener(_onHoldStatus);

  /// The flash and outward pulse when the ring closes. Separate from [_hold] because it has
  /// to keep playing after the hold has been reset.
  late final AnimationController _flash = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 620),
  );

  /// A slow breath on the idle button, so it reads as live rather than as a static graphic.
  late final AnimationController _breathe = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 2400),
  )..repeat(reverse: true);

  bool _holding = false;

  void _onHoldStatus(AnimationStatus status) {
    if (status != AnimationStatus.completed) return;

    widget.onRingClosed?.call();
    _flash.forward(from: 0);
    setState(() => _holding = false);
    _hold.reset();
    widget.onFired();
  }

  void _start() {
    if (!widget.enabled || widget.busy) return;
    widget.onHoldStarted?.call();
    setState(() => _holding = true);
    _hold.forward(from: 0);
  }

  void _abort() {
    if (!_holding) return;
    setState(() => _holding = false);
    // Rewind rather than jump to zero, so an accidental brush reads as "not yet" instead of
    // a glitch.
    _hold.reverse();
  }

  @override
  void dispose() {
    _hold.dispose();
    _flash.dispose();
    _breathe.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final label = widget.busy
        ? widget.busyLabel
        : (_holding ? widget.holdingLabel : widget.idleLabel);

    return Semantics(
      button: true,
      label: widget.idleLabel,
      hint: 'Press and hold for ${widget.holdDuration.inSeconds} seconds',
      child: GestureDetector(
        onTapDown: (_) => _start(),
        onTapUp: (_) => _abort(),
        onTapCancel: _abort,
        // A drag off the button counts as letting go.
        onPanEnd: (_) => _abort(),
        child: AnimatedBuilder(
          animation: Listenable.merge([_hold, _flash, _breathe]),
          builder: (context, _) {
            final progress = _hold.value;
            final flash = _flash.value;
            final breath = _breathe.value;

            // Idle: a gentle breath. Holding: the glow tracks the hold and overtakes the
            // breath entirely, so the two never fight for attention.
            final idleGlow = 0.16 + (0.10 * breath);
            final glow = _holding || widget.busy
                ? 0.22 + (0.55 * progress)
                : idleGlow;

            // The flash decays from 1 to 0 and drives both the white rim and the ring that
            // expands past the button's edge.
            final flashDecay = flash == 0 ? 0.0 : math.pow(1.0 - flash, 2.2).toDouble();

            return SizedBox(
              width: 300,
              height: 300,
              child: Stack(
                alignment: Alignment.center,
                children: [
                  // Expanding shockwave on completion: reads as the alert leaving the phone.
                  if (flashDecay > 0.01)
                    Container(
                      width: 200 + (100 * flash),
                      height: 200 + (100 * flash),
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        border: Border.all(
                          color: Colors.white.withValues(alpha: 0.75 * flashDecay),
                          width: 3,
                        ),
                      ),
                    ),

                  // Halo that grows with the hold.
                  Container(
                    width: 224 + (44 * progress),
                    height: 224 + (44 * progress),
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: AppTheme.danger.withValues(alpha: glow * 0.55),
                    ),
                  ),

                  // The ring. Turns white as it closes, so "connected" is a colour change and
                  // not just a full circle somebody has to measure by eye.
                  SizedBox(
                    width: 240,
                    height: 240,
                    child: CircularProgressIndicator(
                      value: progress,
                      strokeWidth: 9,
                      strokeCap: StrokeCap.round,
                      backgroundColor: AppTheme.line,
                      valueColor: AlwaysStoppedAnimation(
                        Color.lerp(
                          AppTheme.danger,
                          Colors.white,
                          math.max(flashDecay, math.max(0.0, progress - 0.82) / 0.18 * 0.85),
                        )!,
                      ),
                    ),
                  ),

                  Container(
                    width: 200,
                    height: 200,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      gradient: LinearGradient(
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                        colors: widget.enabled
                            ? [
                                Color.lerp(
                                  AppTheme.danger,
                                  Colors.white,
                                  flashDecay * 0.55,
                                )!,
                                Color.lerp(
                                  AppTheme.dangerDeep,
                                  AppTheme.danger,
                                  progress * 0.5,
                                )!,
                              ]
                            : const [AppTheme.line, AppTheme.surface],
                      ),
                      boxShadow: [
                        if (widget.enabled)
                          BoxShadow(
                            color: AppTheme.danger.withValues(alpha: glow),
                            blurRadius: 24 + (34 * progress) + (30 * flashDecay),
                            spreadRadius: 1 + (7 * progress) + (6 * flashDecay),
                          ),
                      ],
                    ),
                    child: Center(
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 18),
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Icon(
                              widget.busy ? Icons.podcasts : Icons.sos_rounded,
                              size: 46,
                              color: Colors.white,
                            ),
                            const SizedBox(height: 10),
                            Text(
                              label,
                              textAlign: TextAlign.center,
                              style: const TextStyle(
                                fontSize: 15,
                                fontWeight: FontWeight.w800,
                                letterSpacing: 0.8,
                                color: Colors.white,
                              ),
                            ),
                            if (_holding) ...[
                              const SizedBox(height: 6),
                              Text(
                                '${((1 - progress) * widget.holdDuration.inSeconds).ceil()}',
                                style: const TextStyle(
                                  fontSize: 24,
                                  fontWeight: FontWeight.w800,
                                  color: Colors.white,
                                ),
                              ),
                            ],
                          ],
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            );
          },
        ),
      ),
    );
  }
}
