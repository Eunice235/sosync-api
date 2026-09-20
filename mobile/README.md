# SOSync — Flutter app

One app, two shapes. A reporter gets the SOS button first; a verified responder gets the call
list and no SOS button at all.

## Running it

The backend must be up first (see `../README.md`).

```bash
cd mobile && flutter run
```

For a browser, which is the fastest way to check a change:

```bash
cd mobile && flutter run -d chrome
```

### The server address

The app talks to the backend at the address in **`lib/config.dart`**:

```dart
static const String serverAddress = 'https://sosync-api-8gt4.onrender.com';
```

That is the hosted API on Render (see [`../deploy/RENDER.md`](../deploy/RENDER.md)). Every phone
that installs the APK connects to it, from any network. To host your own, deploy the backend and
put its address here before building.

The web build ignores this and uses `http://localhost:8090`, since it runs on the laptop. There
is no way to change the address inside the app. To point a development build at a backend on
your laptop, with the phone on USB:

```bash
adb reverse tcp:8090 tcp:8090
flutter run --dart-define=SOSYNC_API=http://localhost:8090
```

## What is built

| Screen | State |
|---|---|
| Sign in / register | Done |
| SOS (press-and-hold, 3s) | Done, with silent mode and a pre-alert note |
| Live emergency | Done: map, location trail, timeline, who-was-told, stand-down |
| Trusted contacts | Done: add, remove, SMS-only vs in-app |
| Contact inbox | Done: emergencies raised by people who named you, polled |
| Notifications tray | Done, with acknowledgement |
| Profile | Done: safety PIN, emergency note |
| Responder | Done: shift toggle, call list, accept, forward-only status, navigate |

## Decisions worth knowing about

**Press and hold, not tap.** This button lives on the first screen of an app that sits in a
pocket and a handbag. A tap would fire by accident constantly, and a contact list that has cried
wolf three times is a list nobody answers. The hold shows continuous progress, cancels silently
when released early, and the glow grows with it so the fill is legible in peripheral vision.

**Sound and vibration are deliberately asymmetric**, and the rule is enforced in one place
(`AlarmService`) rather than left to each screen:

| | Sound | Vibration |
|---|---|---|
| Reporter raising the alert | **Never**, unless they turned silent mode off | Yes — tick on press, bump as the ring closes, three firm pulses once it is away |
| Trusted contact / responder receiving one | **Yes**, looping, routed as an alarm | Yes, long insistent pattern |

The reporter's phone staying quiet is the entire product. An alarm going off in their own
pocket would announce to the person frightening them that they have just called for help. What
they get instead is a triple pulse, fired only *after* the server confirms — so the buzz means
"it is away", not "the button worked", which is the only version worth having when you cannot
look at the screen.

The receiver's phone does the opposite, because it may be face-down on a table or in a bag with
the ringer off. On Android the alarm is declared `AndroidUsageType.alarm` so the platform routes
it like an alarm rather than a notification blip — without that, the most important sound the
app makes is the easiest to miss.

**The alarm sounds once per emergency, not once per poll.** It starts when an incident id
appears that was not in the previous four-second refresh, and a large **Silence** button is the
first thing reachable. Silencing does not dismiss the call, and does not mute the *next*
emergency — a responder who quiets a call they are already handling still needs to hear the
next one. There is a separate session mute for someone who genuinely cannot have their phone
making noise. An alarm that cannot be stopped is one people avoid by leaving the app closed,
and then it protects nobody.

**The sounds are synthesised, not sourced.** `docs/generate_sounds.py` writes both WAVs from
standard-library Python: no licence to account for, and the alarm can be retuned by editing
numbers. It is a personal-attack-alarm warble at 2.2–2.9 kHz — where small phone speakers are
loudest and human hearing is most sensitive — sweeping about eight times a second, because a
steady tone is easy to ignore and hard to locate. Deliberately not a timer chime.

**A missing GPS fix never blocks an alert.** `LocationService` resolves to a result rather than
throwing, falls back to the last known position, and the SOS path treats "no location" as a
degraded success. Reaching people late with a position beats reaching them never because the
phone was in a bag.

**A wrong safety PIN is not an error state.** The API answers a distinct `invalid_safety_pin`
code; the sheet shows it in amber, says *help is still on the way*, and leaves the emergency
running. The person typing may be the reason the alarm was raised.

**Two timers, not one.** An open incident is re-polled every 4s and reports its position every
10s. They are separate because they answer different questions at different costs — tying the
screen refresh rate to GPS would drain a phone that may need to last the evening.

**Polling, not sockets or push.** It survives a connection that drops and comes back, needs
nothing held open, and cannot silently stop delivering the way a stale websocket can.

**OpenStreetMap, not Google Maps.** No API key to provision, nothing to lapse, and the tile
source can be self-hosted or cached for a low-connectivity deployment.

**The launcher icon is the in-app mark, not a drawing of it.** `docs/generate_icon.py` renders
the Material `sos_rounded` glyph — the same one on the splash screen and the SOS button — from
the font that ships with the Flutter SDK, on the app's own `danger → dangerDeep` gradient. It
centres on the pixels the glyph actually paints rather than on the font's reported bounds, which
overstate its height and left the first attempt sitting visibly high. Android 8+ gets separate
adaptive layers so each phone can mask it to its own shape, and the foreground is sized once in
the generator with the tool's default 16% inset turned off — otherwise the SOS would render
smaller on newer phones than older ones. `docs/icon_preview.png` shows it under each mask shape.

To change it: edit the generator, then

```bash
cd mobile && python docs/generate_icon.py && dart run flutter_launcher_icons
```

**Dark theme by default, and red used for one thing only.** The app gets opened at night in a
phone held low; a white screen is visible from several seats away. Red appears on the SOS
control and an active emergency, nowhere else, so its presence always means the same thing.

**The UI reads its language from the account, even with one language configured.** `Strings` is
an abstract class with one implementation per language, and the active one is selected from
`user.preferredLanguage` rather than hard-coded. A second language therefore lights up the
whole UI by adding one class — and the compiler refuses to build until every string in it
exists. It also means a grep for `Text('` catches any screen that has quietly grown its own
literal, which is how English-only apps become untranslatable ones.

**The server localises what it delivers; the app localises what it renders.** Location comes
back as `stale: true` and `ageSeconds`, not as "last seen 4 minutes ago" — facts from the API,
words from the app. The one exception is the post-trigger summary line, which the server words
because it is shown to the reporter verbatim at the moment they are least able to read
carefully.

## Known limits

**No background location.** The app reports position only while it is in the foreground.
Continuing when the phone is locked needs an Android foreground service with a permanent visible
notification — which on a silent panic alarm is precisely the thing that must not appear.
Resolving that tension is design work, not a manifest line, so it is left undone rather than
half-done. The commented-out permission in `AndroidManifest.xml` marks the spot.

**No push notifications.** There is no FCM project wired in. Alerts arrive when the app next
polls; the tray shows what a push would have said, flagged `SIMULATED`.

**The alarm only sounds while the app is open.** It is triggered by the poll, so a phone with
the app closed hears nothing until it is opened. Waking a closed app needs push, which needs
FCM — that is the single most valuable thing to add next, because it is the difference between
"Grace hears the alarm" and "Grace hears the alarm if she happened to be looking at SOSync".

**Vibration and audio are unverified on real hardware.** Both were tested on Flutter web, where
vibration is unavailable by design and audio is subject to the browser's autoplay policy. The
alarm asset is fetched and streamed, and the banner and Silence control work, but nobody has yet
*heard* it from a phone speaker or felt the triple pulse. Worth five minutes on a real device
before the demo.

**English only.** The language pickers were removed by product decision. The plumbing stays:
`Strings` still resolves from `user.preferredLanguage`, the API still carries a language per
account and per contact, and every delivery still records the language it went out in. Adding a
language is one `Strings` subclass plus one `AlertStrings` subclass on the server — see the
project README for what that involves.

**Tokens are in SharedPreferences, not secure storage.** Readable on a rooted or compromised
device. `flutter_secure_storage` is a drop-in change at three call sites in `AuthStore`.

**Cleartext HTTP is allowed on Android.** `network_security_config.xml` permits it so a local
demo can reach a laptop at whatever address it has that day. As it stands an alert carrying a
live location travels unencrypted. That file must be deleted, and the API served over HTTPS,
before this goes anywhere real.

**No widget tests.** The backend carries the automated suite (96 tests); the app has been
verified by driving it. Given more time, the press-and-hold timing and the wrong-PIN branch are
the two behaviours most worth pinning down in a widget test.

## Layout

```
lib/
├── main.dart              App root, providers, auth-based routing
├── config.dart            API address per platform, poll intervals, hold duration
├── theme.dart             Colours and component styling
├── api/
│   ├── api_client.dart    HTTP, bearer token, typed errors
│   └── models.dart        Payload mirrors
├── i18n/strings.dart      UI wording (English)
├── state/
│   ├── auth_store.dart    Session, token persistence, language
│   ├── incident_store.dart      Trigger, poll, report position, stand down
│   ├── alarm_service.dart       Sound and vibration, reporter vs receiver
│   └── location_service.dart    GPS with graceful degradation
├── screens/               One file per screen
└── widgets/
    ├── hold_button.dart   The SOS control
    ├── alarm_banner.dart  When to sound, and the Silence control
    └── common.dart        Status chips, map, timeline, delivery rows

assets/audio/              emergency_alarm.wav, alert_sent.wav
assets/icon/               Launcher icon sources (legacy + adaptive layers)
docs/generate_sounds.py    Regenerates both sounds from scratch
docs/generate_icon.py      Regenerates the launcher icon sources
docs/icon_preview.png      The icon under each Android launcher mask
```
