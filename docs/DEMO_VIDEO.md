# Demo video — how to record it

Target: **2 to 3 minutes**, narrated while the app is used. Judges want to see the emergency
journey happen, not hear a description of it.

## What the viewer must see

Two sides at once, because the whole point is that one phone stays quiet while another one
screams:

- **Left: the person in danger** — the SOSync app on the phone.
- **Right: the responder** — the same app in Chrome on the laptop, signed in as Daniel.

## Recording setup

**Easiest option (one recording, no editing).** Mirror the phone onto the laptop with **Phone
Link** (built into Windows 11; on the Samsung open *Link to Windows*), put that window beside a
Chrome window, then record the whole laptop screen:

- Press `Windows + Alt + R` to start and stop recording (Xbox Game Bar).
- Before recording, press `Windows + G` → the audio widget → turn the **microphone on**, so your
  narration is captured.

**If mirroring will not cooperate.** Record the two sides separately and place them side by side
in **Clipchamp** (also built into Windows 11):

1. Phone: Samsung's own **Screen recorder** (swipe down twice → Screen recorder), sound set to
   *Media and mic* so both the alarm and your voice are captured.
2. Laptop: `Windows + Alt + R` on the Chrome window.
3. Clipchamp: both clips on the timeline, aligned on the moment you release the SOS button.

**Before you hit record**
- Open the deck of things you will need: Chrome signed in as Daniel, **on shift**.
- On the phone, open the **Watching** tab first so the alarm can sound, then go back to SOS.
- Turn the phone's ringer and volume **up**, so the alarm is audible in the recording.
- Silence WhatsApp and other notifications on both devices.
- Do a full dry run once. The whole thing takes 90 seconds, and the second take is always better.

## Script

Timings are a guide, not a target. Speak slowly; the actions are quick.

**0:00 — the moment (spoken over the phone's home screen)**
> "A woman is in a taxi and the driver has changed route. She does not know exactly where she
> is, and the person she would be reporting is sitting right next to her. Calling anyone means
> speaking out loud. This is the moment SOSync is built for."

**0:20 — who is on screen**
> "On the left is her phone. On the right is a verified security responder on shift, in the same
> app. Notice that silent mode is on: her phone will make no sound at all."

**0:30 — raise the alert**
Hold the SOS button for the full three seconds. Let the ring close and the glow build.
> "One button, held for three seconds, so a pocket cannot raise a false alarm."

When it completes:
> "Her phone vibrates three times — and that is all it does. No sound. That buzz only happens
> after the server confirms the alert was accepted, so it means *it is away*, not *I pressed the
> button*."

**0:50 — the other side (point at the responder window)**
> "Within four seconds, the responder's screen has the call, and the alarm is sounding — routed
> as an alarm, not a notification, so it plays even from a pocket."

Let the alarm be heard for two or three seconds, then press **Silence**.
> "Silencing it does not dismiss the call, and it does not mute the next emergency."

**1:10 — what the responder can see**
Open the incident.
> "Her name, her live position on the map, how far away he is, how long she has been waiting, and
> her emergency note. The list is ordered by waiting time, not distance — the nearest call is not
> the most urgent one; the one nobody has taken for four minutes is."

**1:30 — the response**
Tap **Accept**, then **On the way**, then **Arrived**, pausing on the phone after each.
> "He accepts. On her phone, 'waiting for a responder' becomes 'responding now', with who is
> coming and how far away. Then on the way, then arrived. Each step is stored with who did it and
> when, so afterwards the whole response can be read back."

**1:55 — the trust part: a wrong PIN**
On the phone, tap stand-down and type a **wrong** PIN.
> "Ending an emergency needs a safety PIN set in advance. A wrong one does not cancel anything:
> the alert stays open, the screen says help is still on the way, and the failed attempt is
> recorded. The person typing may be the reason the alarm was raised."

Then type the correct PIN.
> "With the right PIN, it stands down, and live location sharing stops with it."

**2:15 — honesty and close**
> "Two things to be straight about. The text messages and push notifications in this prototype
> are simulated, and every one of them is labelled as simulated rather than implied to have been
> sent. And today a contact hears the alarm only while the app is open — waking a closed phone
> needs push notifications, which is the first thing I would build next.
>
> Everything else works end to end, on a hosted API, from any phone on any network. That is
> SOSync: one held button, and the right people already know."

## Things that will go wrong, and what to do

| Problem | Fix |
|---|---|
| The app says the server did not answer | The free hosting sleeps. Open the API health address in a browser, wait a minute, try again. Keep the uptime monitor running. |
| The responder sees no call | Daniel must be **on shift**, and the phone needs a location fix. Going on shift sends his position, which is what the matching uses. |
| No alarm sounds | The receiving side must be on the **Watching** tab (contacts) or the responder list, with volume up. |
| The map is blank | OpenStreetMap tiles need a connection; wait a moment or pan the map. |
| An emergency is already open | One person can only have one open emergency. Stand the old one down with the PIN first. |

## After recording

- Watch it once with the sound on. The alarm must be audible and your voice clear.
- Trim dead air at both ends. Keep it under three minutes.
- Export as **mp4**, under 250 MB. Clipchamp's 1080p preset is fine.
