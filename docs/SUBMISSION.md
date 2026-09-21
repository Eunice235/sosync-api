# SOSync — written summary

**Track:** Safety, Reporting & Protection
**Repository:** https://github.com/Eunice235/sosync-api
**Built:** five days, September 2026

## What it is

SOSync is a discreet emergency reporting and response platform for the moment when calling for
help out loud is the thing you cannot do. A person who senses danger presses and holds one
button for three seconds. The system creates an incident, attaches the phone's GPS position,
alerts the trusted contacts that person chose along with verified responders on shift nearby,
and keeps the location updating until the incident is resolved or stood down with a safety PIN.

It is a working prototype, not a replacement for national emergency services. Responders are
trusted contacts and verified private, estate, campus or community security — people and
organisations who have agreed to respond.

## How it fits the track

The track asks for tools that let people report threats safely and get timely support.

- **Safe reporting.** A held button instead of a voice call. The reporter's own phone makes no
  sound, so asking for help does not announce itself to whoever is standing next to them.
- **Timely support.** Trusted contacts and nearby responders are alerted in the same fan-out,
  the moment the incident is created.
- **A visible path to action.** An incident moves through raised, notified, accepted, arrived
  and resolved, and every party sees where it has got to.
- **Actionable content.** The report carries a position, its accuracy and its age, not a
  description someone has to interpret.

## Information sources

This project does not ingest third-party data feeds, so the trust question here is about
information the system itself creates and passes on. Every piece of it comes from one of four
places:

1. **The device.** Latitude, longitude and accuracy come from the phone's own location services
   via the operating system. Nothing is inferred or estimated.
2. **The person.** Their name, phone number, trusted contacts, emergency note and safety PIN
   are entered by them and editable by them.
3. **The system's own record.** Incident status, who was notified, on which channel, who
   accepted and when each step happened are all produced by the server as things happen.
4. **An administrator.** Responder verification — the decision that an account represents real
   security — is made by a human administrator, never self-declared.

Phone numbers are normalised to international E.164 format with Google's libphonenumber before
being stored or compared, so `0712345678` and `+254712345678` are recognised as the same person.
Without that, a trusted contact who had registered could silently stop receiving in-app alerts,
and nobody would find out until an emergency. Maps use OpenStreetMap tiles.

## Approach to trust and accuracy

An emergency alert is only useful if the person receiving it can believe it. Six decisions carry
that in this build:

1. **Facts travel, phrasing does not.** The API returns a position with its age in seconds and a
   `stale` flag; the app decides whether to write "2 minutes ago". The words are the app's, the
   facts are the device's, and one cannot quietly become the other.
2. **Every delivery is on the record, including the ones that were not real.** Each notification
   stores its recipient, channel and status. This prototype has no SMS gateway or push project,
   so those deliveries are recorded and displayed as `SIMULATED` rather than implied to have
   been sent. Claiming a text message went out when none did would be the exact failure this
   hackathon is about.
3. **A timeline, not a status word.** Raised, notified, accepted, arrived and resolved are each
   stored with the actor and the timestamp, so a response can be read back afterwards rather
   than taken on trust.
4. **Access is decided in one place, and outsiders learn nothing.** A request from anyone who is
   not the reporter, a named contact or the assigned responder is answered as though the incident
   does not exist. Answering "not allowed" would confirm to a stranger that a particular person
   has an emergency open.
5. **A wrong safety PIN is treated as a signal.** Ending an alert requires a PIN set in advance.
   A wrong one leaves the emergency running, tells the person help is still on the way, and
   records the failed attempt — because the person typing may be the reason the alarm was raised.
   The audit record is written in its own transaction, so it survives the refusal.
6. **Sound is deliberately one-sided.** The reporter's phone only vibrates, and only after the
   server confirms the alert was accepted, so the buzz means "it is away" rather than "the button
   worked". Receiving phones get a looping alarm routed as an alarm rather than a notification.

Accuracy of the software itself is held up by 96 automated backend tests that run against a real
PostgreSQL database rather than mocks. Two of them earned their place: one caught a refresh token
being accepted as an access token, and one caught a failed-PIN audit record being rolled back
along with the refusal it was recording. A missing GPS fix is treated as a degraded success
rather than a failure — reaching people with no position beats not reaching them at all — and the
app always says which of the two happened.

## The operating constraints, one by one

| Constraint | How SOSync answers it |
|---|---|
| **Trust and verification** | Positions carry their accuracy and their age in seconds, so a map pin can never imply a freshness it does not have. Every delivery records its channel and whether it was real or simulated. Responders are verified by an administrator, never self-service. |
| **Low bandwidth and limited access** | Small polled requests rather than a held-open connection, so it recovers by itself when a connection drops and returns. SMS is a first-class channel that reaches a phone with no app and no data. Map tiles are OpenStreetMap, with no commercial key. USSD is on the roadmap, for a phone that cannot run an app at all. |
| **Accessibility and inclusion** | The core action is one large button held for three seconds, with no text to read and no menu to navigate. Vibration confirms it without sight or sound. The receiving alarm is routed as an alarm, so it is heard rather than seen. Colour never carries meaning alone — every status is also a word. |
| **Privacy and security** | Location is shared only during an open incident, only with named contacts and the assigned responder, and stops when it closes. One class decides who may see an incident, and an unrelated caller is told it does not exist rather than that they are barred. Cancelling needs a PIN, and failed attempts are recorded. The caller is always taken from the token, never from the request body. |
| **Multilingual access** | **The weakest point of this build: only English ships.** What exists is the mechanism, which is the awkward part to add later — language is a property of the recipient rather than the deployment, carried per account and per contact, resolved per recipient when alerts go out, and recorded on each delivery. Alert wording lives in one class per language, so adding Kiswahili, French or Portuguese is one class each, and the compiler refuses to build until every message in it exists. The real cost is review by a first-language speaker, not code. |
| **Local relevance** | Responders are data an administrator verifies — a campus guard room, an estate patrol, a community watch — so a new deployment needs no release. Phone numbers are normalised to international form, so a number saved in local format still matches the account its owner registered. |
| **Clear next steps** | Every alert carries a plain maps link that opens on any phone. A contact sees whether anyone has accepted and who. A responder gets a navigation link and a forward-only status path, so "who is dealing with this" is never ambiguous. |

## What works, and what does not

Working end to end, against a hosted API that any installed phone reaches from any network:
registration and sign-in, trusted contacts, press-and-hold SOS, GPS capture and live updates,
alert fan-out with per-recipient delivery records, alarm and vibration on receiving phones, the
responder flow through accept, arrived and resolved, safety-PIN stand-down, and an admin view.

Not working yet, and stated in the app's documentation as well as here:

- **No push notifications or real SMS.** A trusted contact hears the alarm only while SOSync is
  open. Nobody walks around with a panic app open, so this is the single most important thing to
  build next, and it is a delivery-channel gap rather than a design gap.
- **No background location.** Updates stop when the phone is locked. Continuing needs an Android
  foreground service, whose permanent visible notification is precisely what a silent panic alarm
  must not show — that tension is design work, left undone rather than done badly.
- **One default country.** Numbers without a country code are read as Kenyan; international
  numbers work when typed with a `+` prefix.
- **Tokens are kept in ordinary app storage**, readable on a compromised device.

## How AI tools were used

The idea, the problem framing, the product requirements and the specification are mine. I wrote
the project template that defines the tracks fit, the roles, the modules and their priorities,
the data model, the five-day plan, the MVP scope and the demo scenario before any code existed.

I used Claude (Claude Code) as a development assistant to build against that specification. It
wrote most of the application code — the Kotlin API, the Flutter app and the test suite — from my
instructions, and I directed the decisions along the way: that the reporter's phone must stay
silent, that the alarm must be one-sided, that a wrong PIN must not cancel an emergency, that the
language pickers should be dropped, and where the API should be hosted. Reviewing behaviour on a
real device also drove changes, including one I asked for after seeing it myself: the alarm was
re-sounding every time the receiving screen was reopened.

AI was also used for the written material, including this summary and the pitch deck, drafted
from my template and my decisions and then reviewed by me.

I did not use AI to generate the project idea, and no AI-generated content is presented as data,
evidence or a source inside the product. There are no statistics in the deck or the README that I
have not sourced myself, because a safety tool arguing for trustworthy information cannot rest on
numbers nobody can check.
