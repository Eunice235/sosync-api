# SOSync

**A silent panic button. One action, and the right people know you need help — and where you are.**

Challenge track: **Safety, Reporting & Protection**

---

## The problem

Think about the times you feel unsafe: walking home after dark, in a taxi that has left the usual
route, someone following you. You have a phone in your hand and it is almost useless, because
using it means talking out loud in front of the person you are afraid of — and saying where you
are, which you may not even know.

So people stay quiet and hope it passes. And when they do reach somebody, that person is left
asking: where are you, are you okay, is anyone coming?

## What SOSync does

You hold one button for three seconds.

- **Your phone stays silent.** It only vibrates, and only once the server has accepted the alert
  — so nobody beside you hears anything, and you still know it went.
- **The people you chose are told**, with a map of where you are. So are registered security
  responders nearby who are on duty. **Their** phones are loud: a looping alarm, routed as an
  alarm rather than a notification.
- **Somebody accepts**, and from then on everyone watching sees that help is coming, who it is
  and how far away. Your position keeps updating, so nobody has to ask.
- **You cannot be silenced by the person threatening you.** Ending the alert needs a PIN only you
  know. A wrong PIN leaves help on the way, and records the attempt.
- **It ends when the emergency ends.** Location sharing stops the moment the incident closes.

SOSync is not connected to any national emergency number and claims no police dispatch.
"Responders" means verified private, estate, campus or community security — people who agreed to
come.

## Why the information can be trusted

In an emergency this is not a slogan: a report nobody believes is a report nobody acts on.

- **Facts travel, phrasing does not.** The server sends a position, its accuracy and its age in
  seconds; the app decides whether to write "2 minutes ago".
- **No delivery is overstated.** This prototype has no SMS or push provider, so those deliveries
  are recorded and shown as `SIMULATED` rather than implied to have been sent.
- **Every step is on the record.** Raised, notified, accepted, arrived, resolved — each with who
  did it and when.
- **Outsiders learn nothing.** Anyone who is not the reporter, a named contact or the assigned
  responder is told the incident does not exist, rather than that they may not see it.
- **Nobody self-declares as security.** Responder verification is an administrator's decision.

## What works today

Running end to end, on a hosted API that any installed phone reaches from any network:

register and sign in · trusted contacts · press-and-hold SOS · GPS capture, map and live updates
· alert fan-out with a per-recipient delivery list · alarm and vibration on a receiving phone ·
incident timeline · responder accept, navigate, arrived, resolved · safety-PIN stand-down ·
notification tray

**The honest gaps.** A contact hears the alarm only while the app is open; waking a closed phone
needs push notifications, which is the first thing to build next. There is no real SMS or push,
no background location, and no screen for the admin and family features that exist in the API.
Numbers typed without a country code are read as Kenyan.

Everything else a reviewer might want — architecture, the design decisions, the test suite and
the two real bugs it caught, all known limits, the roadmap — is in
**[docs/ENGINEERING.md](docs/ENGINEERING.md)**.

## Try it

```bash
cd backend && docker compose up -d && ./gradlew bootRun
```

```bash
cd mobile && flutter run
```

Requires JDK 21, Docker and Flutter. The schema and the demo data create themselves on first
start, and interactive API docs are at `http://localhost:8090/docs`.

**Demo accounts** — password `Sosync#2026`, Amina's safety PIN `4821`:

| Phone | Who |
|---|---|
| `+254712000001` | Amina — raises the alert |
| `+254712000002` | Grace — her sister, a trusted contact who receives it |
| `+254712000003` | Daniel — a **verified** responder, on shift |
| `+254712000009` | Platform administrator |

Two sides are worth seeing at once: sign in as Amina on a phone and as Daniel in a browser, hold
SOS, and watch both. Or run `bash docs/demo.sh` to drive the whole journey through the API,
including the parts that are meant to fail — a double press, a second responder losing the race,
a wrong PIN, and an outsider trying to read the incident.

## Repository

```
backend/    Kotlin, Spring Boot 4, PostgreSQL — the whole server
mobile/     Flutter app: reporter and responder in one build
deploy/     Hosting the API (Render + Neon, or an Oracle Cloud VM)
docs/       Engineering notes, written summary, demo script
```

Built in five days, September 2026.
