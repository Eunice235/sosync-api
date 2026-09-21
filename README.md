# SOSync

**A trusted emergency reporting and response network. One action turns a request for help into an
incident people can act on.**

Challenge track: **Safety, Reporting & Protection**

---

## The problem

When someone needs help, the process usually starts with a phone call: dial, wait for someone to
answer, explain what is happening, explain where you are, and then wait without always knowing
whether anyone is responding.

In fast-moving situations such as being followed, threatened, attacked, experiencing abuse, or
noticing that a taxi has unexpectedly changed route, those extra steps can matter. Sometimes
speaking openly can also increase the risk.

SOSync reduces the gap between recognizing danger and getting someone to respond.

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

## Information you can trust

For SOSync, trusted information means that during an emergency, the people involved can verify
the facts needed to act: who raised the alert, where they are, when the incident started, who
accepted the response, and whether help has arrived.

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

## Scalability

SOSync is designed around a response model rather than one specific institution.

The same flow can support:

- individuals and families
- residential estates
- campuses
- private security companies
- community response networks
- future integrations with public emergency and protection services

The core workflow remains the same across locations:

**report → locate → notify → accept → respond → resolve**

Responder networks, languages and local emergency structures can be configured for each
geography without changing the core incident model. Responders are records an administrator
verifies rather than anything in code, and every phone number is stored in international form,
so a contact saved in local format still matches the account its owner registered.

## AI-assisted development

AI software development tools were used throughout the hackathon to accelerate:

- coding and implementation
- debugging and troubleshooting
- test development and review
- architecture refinement
- documentation
- rapid iteration

The SOSync concept, problem definition, requirements, safety decisions and product direction
remained developer-led. AI was used as a software development assistant rather than to generate
the core capstone idea.

## Real-world deployment considerations

- **Trust & verification:** responders are administrator-verified and incident actions are
  auditable.
- **Privacy & security:** access is role-based and location is shared only as part of an active
  incident.
- **Low connectivity:** polled requests recover on their own when a connection drops; real SMS
  fallback and more resilient delivery are part of the production roadmap.
- **Accessibility:** the primary emergency action is a simple three-second hold, with no text to
  read and vibration rather than sound as confirmation.
- **Multilingual access:** only English ships today. The mechanism is already in the code —
  language is a property of each recipient, and alert wording lives in one class per language —
  so a language is one class plus review by a first-language speaker.
- **Local relevance:** response networks can be configured around the security and emergency
  structures available in each location.
- **Clear next steps:** incidents move from raised → accepted → arrived → resolved rather than
  ending after submission, and every alert carries a maps link that opens on any phone.

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
