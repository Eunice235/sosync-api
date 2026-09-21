# SOSync

**Discreet emergency reporting and rapid response.**

One action. The right people know you need help — and where to find you.

---

## In plain words

Think about the times you feel unsafe: walking home after dark, in a taxi that has left the usual
route, someone following you, a situation at home about to turn violent. In all of them you have
a phone in your hand and it is almost useless, because using it means talking out loud in front
of the person you are afraid of — and saying where you are, which you may not even know.

So people stay quiet and hope it passes. And when they do reach somebody, that person is left
asking: where are you, are you okay, is anyone coming?

SOSync is one button, held for three seconds. Your phone stays silent and only vibrates, so
nobody beside you hears anything. The people you chose beforehand get an alert with a map of
where you are, and so do registered security responders nearby who are on duty. Their phones are
loud. One of them accepts, and from then on everybody watching can see that help is coming, who
it is, and how far away. Your position keeps updating, so nobody has to ask.

And you cannot be silenced by the person threatening you: ending the alert needs a PIN only you
know, and a wrong one leaves help on the way.

## The problem

Calling for help takes time you may not have, and makes noise you may not be able to afford.

A woman in a taxi notices the driver has left the agreed route. Someone walking home senses they
are being followed. In both cases the standard advice — phone a friend, phone the police — asks
them to do the one thing that escalates the danger: hold a visible, audible conversation about
the fact that they are afraid, in front of the person frightening them.

Even when a call does get through, it starts from nothing. The caller has to explain who they
are, where they are, and what is happening, while it is happening. Nobody on the other end knows
whether someone else is already responding.

SOSync reduces that to a single discreet press-and-hold, and sends the information a response
actually needs.

## Challenge track

**Primary: Safety, Reporting & Protection.** Secondary relevance to Stability & Social Cohesion
through coordination between community responders.

| Requirement | How SOSync answers it |
|---|---|
| Safe reporting | Press-and-hold raises an alert with no call and no sound. Silent mode is the default, not an option. |
| Timely support | Contacts and nearby verified responders are alerted the moment the incident is created. |
| Clear pathway to action | Incidents move through `TRIGGERED → ACCEPTED → RESPONDING → ARRIVED → RESOLVED`, visible to everyone involved. |
| Trust and verification | Responders are verified by an administrator, never self-service. Every alert states which channel it used and whether it got through. |
| Anonymity and privacy | Location is shared only with named people, only during an active emergency, and access to it is audited. |
| Low bandwidth | SMS is a first-class channel, not a nicety: it reaches a feature phone with no app and no data. |
| Multilingual access | **Not answered in this build.** English only. Alert wording lives in one class per language rather than inline strings, so adding a language is one class plus a review by a first-language speaker. See [Known limits](#known-limits). |
| Actionable information | Every message carries a plain maps link that opens on any phone, and every position carries its own age. |

## What actually works

This is a five-day proof of concept, and the line between what is built and what is planned is
drawn deliberately. It is drawn twice: once for the app somebody can install and use, and once
for the API behind it, which is further along than the screens are.

**In the app, end to end — this is what the demo shows:**

- Register, sign in, add and remove trusted contacts
- Press-and-hold SOS with a three-second hold, silent by default, and a repeated press joining
  the emergency already running rather than starting a second one
- GPS captured with the alert, shown on a map, and re-reported every ten seconds while open
- Alert fan-out to trusted contacts and nearby verified responders, with a per-recipient
  delivery list showing the channel each one got and whether it was real or simulated
- Alarm and vibration on a receiving phone (while the app is open — see the limits), with one
  large Silence control
- Incident timeline: raised, notified, accepted, arrived, resolved
- Responder call list sorted by waiting time, accept-first-wins, forward-only status progression,
  and a navigate link
- Stand-down gated on a safety PIN, where a wrong PIN leaves the emergency running and is
  recorded
- Notification tray with acknowledgement

**In the API only, with no screen yet:** phone verification and password reset; the admin surface
(platform statistics, responder verification, account suspension, per-incident audit trail);
family groups; and simulated subscription plans. All of it is exercised by the test suite and
callable from the interactive docs at `/docs`, but a judge watching the app will not see it.

**Not built at all:** real SMS and push gateways, payments, police integration, wearables,
check-in escalation, trip monitoring, background location, and any language beyond English. See
[Known limits](#known-limits) and the app's own [limits](mobile/README.md#known-limits).

## Architecture

Kotlin, Spring Boot 4, PostgreSQL. One module, one database, one process.

```
sosync/
├── backend/                     Spring Boot service (this is the whole server)
│   ├── src/main/kotlin/com/sosync/
│   │   ├── common/              Ids, phone normalisation, distance, errors, current caller
│   │   ├── config/              Security, OpenAPI, properties, demo seeder
│   │   ├── domain/              JPA entities and enums
│   │   ├── persistence/         Spring Data repositories
│   │   ├── service/
│   │   │   ├── auth/            Accounts, JWT, safety PIN
│   │   │   ├── incident/        SOS lifecycle (command), reads (query), access policy
│   │   │   ├── contact/         Trusted contacts
│   │   │   ├── responder/       Responder profile and dispatch matching
│   │   │   ├── notification/    Fan-out, message wording, gateways
│   │   │   ├── family/          Households and simulated plans
│   │   │   └── admin/           Statistics, verification, audit
│   │   └── web/                 REST controllers and DTOs
│   ├── src/main/resources/db/migration/   Flyway schema
│   └── docker-compose.yml       PostgreSQL 17
├── mobile/                      Flutter app (reporter + responder in one build)
│   ├── lib/
│   │   ├── api/                 HTTP client and payload mirrors
│   │   ├── state/               Session, incident polling, GPS
│   │   ├── i18n/                UI wording (English)
│   │   ├── screens/             One file per screen
│   │   └── widgets/             SOS hold button, map, timeline, delivery rows
│   └── README.md                Setup, decisions, known limits
└── docs/
    └── demo.sh                  The full emergency flow, as a runnable script
```

### Why one service

A gateway, a discovery server and a message broker all have to be running before a demo can
start, and none of them is something a judge will ever see. Five days is better spent on the
emergency path, so this is one module, one database, one process: `./gradlew bootRun`.

Two choices inside that worth stating:

- **Spring MVC + JPA rather than a reactive stack.** JPA makes a ten-entity model nearly free,
  and a blocking stack trace is much faster to read at two in the morning on day four.
- **A single symmetric signing key (HS256) rather than a full OAuth2 authorization server.** One
  service issues and verifies its own tokens, so a symmetric key does the same job. The trade-off
  is real: the signing key is also the verification key, so it cannot be published, and rotating
  it invalidates every token at once.

### Design decisions worth knowing about

**A missing GPS fix never blocks an alert.** Reaching people late with a location beats reaching
them never because the phone was indoors. The incident is created, the notification says the
location is not yet available, and the trail fills in when a fix arrives.

**A repeated press joins the running emergency rather than starting a second one.** A frightened
person presses more than once. A partial unique index on `incidents` enforces at most one open
incident per user, and the service answers a second press with the incident already running.
Without this, one emergency becomes three and the responders split up.

**A wrong safety PIN leaves the emergency running.** Cancellation is the most
security-sensitive operation in the system, because whoever can cancel can call off the response.
Only the reporter can cancel, only with their PIN, and a failed attempt returns a distinct error
(`invalid_safety_pin`) so the client can stay outwardly calm — the person typing may not be the
person who raised the alarm.

**Location sharing genuinely stops.** There is no code path that stores a position outside an
open incident, and the access policy withholds location from every viewer except the reporter
once an incident closes. `GET /api/incidents/{id}/locations` returns an empty list to a contact
after resolution. This is verified in `docs/demo.sh`, step 15.

**Every position carries its age.** A map pin with no timestamp is actively misleading: it looks
like where somebody is when it may be where they were twenty minutes ago. Location responses
include `ageSeconds` and a `stale` flag, and responder positions that go stale stop being used
for matching at all.

**Delivery is never overstated.** No real SMS or push gateway is connected, so deliveries are
recorded with status `SIMULATED` and returned over the API. A safety tool that claims a text was
sent when it was not is worse than one that admits it.

**One place decides who may see an incident.** `IncidentAccessPolicy` is the only authority on
that question. Scattering these checks across controllers fails by one endpoint quietly missing
one, which nobody notices until it matters. An unrelated caller gets `404`, not `403`: confirming
an incident id exists tells a stranger that somebody raised an emergency.

**The caller comes from the token, never the request body.** No endpoint accepts an actor id, so
no caller can act as somebody else by editing a payload.

## Running it

Requires **JDK 21** and **Docker**. No Gradle install needed — the wrapper is committed.

```bash
cd backend && docker compose up -d
```

```bash
cd backend && ./gradlew bootRun
```

Then open **http://localhost:8090/docs**.

And the app, in a third terminal:

```bash
cd mobile && flutter run
```

See [mobile/README.md](mobile/README.md) — in particular the server-address table, which is the
thing that breaks first when demoing from a real phone.

> Port 8090 rather than 8080: on the development machine this was built on, Windows `http.sys`
> already holds 8080 and 8085. Override with `SERVER_PORT` if you need to.

Flyway creates the schema on first start and the demo data seeds itself. Seeding is idempotent,
so restarting never duplicates anything and a demo can be re-run without resetting the database.

### Hosting it for testers

Hosting is optional; everything above runs on one laptop. To put the API where phones can
reach it from anywhere:

- **[`deploy/RENDER.md`](deploy/RENDER.md)**: Render + Neon, free and no card. Render builds
  straight from this repository using `render.yaml` and `backend/Dockerfile`.
- [`deploy/README.md`](deploy/README.md): your own Oracle Cloud VM, via `deploy\deploy.bat`.

A hosted server should set `SOSYNC_DEMO_PASSWORD`, so the demo accounts below cannot be signed
into by everyone who reads this README.

### Demo accounts

Password `Sosync#2026` for all of them. Amina's safety PIN is `4821`.

| Role | Phone | Who they are |
|---|---|---|
| `USER` | `+254712000001` | Amina Wanjiru — raises the alert |
| `USER` | `+254712000002` | Grace — her sister, a registered trusted contact |
| `RESPONDER` | `+254712000003` | Daniel Otieno, Nairobi Community Watch — **verified**, on shift |
| `RESPONDER` | `+254712000004` | Joyce Kamau, Westlands Security — **pending**, so verification has something to demonstrate |
| `ADMIN` | `+254712000009` | Platform operator |

Amina also has a contact, `John (neighbour)` on `+254712000099`, with **no account anywhere**.
That is on purpose: he demonstrates the SMS-only path, which is the case that matters most in
practice.

### Watch the whole thing run

```bash
bash docs/demo.sh
```

Twenty-one steps from login to audit trail, including the parts that are supposed to fail: a
double press, a second responder losing the race, a wrong PIN, an outsider trying to read the
incident, and location access ending at resolution.

## Demo scenario

Amina is travelling home and the driver has left the route. She does not want to make a call the
driver would hear.

1. She holds the SOS button. An incident is created, silent, with her GPS position.
2. Grace gets an SMS, a push and an in-app alert. John, who has no account, gets the SMS. Daniel,
   1.2 km away and on shift, gets the alert in his responder list.
3. Daniel accepts. Grace immediately sees that help is on the way and who it is.
4. Amina's phone keeps reporting position; Grace and Daniel watch it move, each fix labelled with
   its age.
5. Daniel marks RESPONDING, then ARRIVED, then RESOLVED with a note.
6. Location sharing stops. Grace can see the outcome, and no longer the position.

## Tests

```bash
cd backend && ./gradlew test
```

96 tests, about four minutes, all green.

Requires Docker. The suite runs against a real PostgreSQL container, not an in-memory
substitute, because several of the rules being tested are enforced by the database: the partial
unique index that stops one emergency becoming three exists only in PostgreSQL, and an H2 run
would pass while the real constraint went untested. Flyway runs against the container too, so
the migrations are exercised on every build.

One container is started by hand and shared by the whole suite, rather than the usual
`@Testcontainers` / `@Container` pair. That spelling stops the container after each test class
while Spring keeps caching the context across classes, so every class after the first inherits
a connection pool pointing at a database that no longer exists — and fails with a Hikari
timeout nowhere near the actual mistake. `IntegrationTest` explains this at the point somebody
would otherwise "tidy" it back.

The tests are deliberately concentrated on the rules where being wrong is expensive, rather
than spread evenly for the sake of a coverage number:

| Suite | What it pins down |
|---|---|
| `IncidentLifecycleTest` | Raising an alert with and without GPS, the double-press guard, first-responder-wins, forward-only transitions, and location being refused once the incident closes |
| `SafetyPinCancellationTest` | Only the reporter can cancel; a wrong PIN leaves the emergency **running**; the password works only until a PIN exists; failed attempts are audited |
| `IncidentAccessPolicyTest` | Who may read an incident, why a stranger gets 404 rather than 403, a responder losing sight of an alert somebody else took, and location access ending at close |
| `NotificationFanOutTest` | Per-recipient delivery rules, SMS-only contacts, verified-responder-only dispatch, stale positions being ignored, and the reporter never being sent an SMS about their own silent alarm |
| `SecurityBoundaryTest` | The filter chain over real HTTP: role boundaries, refresh tokens rejected as access tokens, login not revealing who has an account, admin not being self-assignable |
| `AlertStringsTest`, `PhonesTest`, `GeoTest` | Pure unit tests: catalogue completeness per language, phone-format equivalence, distance and the (0, 0) failed-fix case |

**Writing these found two real bugs**, both in security-relevant code and neither visible from
the outside:

1. **A refresh token worked as an access token.** The resource server verified a signature but
   never checked the `typ` claim, so a 30-day credential authenticated every request exactly
   like the 24-hour one — the shorter lifetime was decoration. Fixed with a `typ` and issuer
   validator on the decoder in `SecurityConfig`.
2. **Failed cancellation attempts were never recorded.** `cancel` wrote the
   "rejected: incorrect safety PIN" audit row and then threw, so the row rolled back with the
   exception. The audit trail of somebody trying to silence an alarm — precisely the trace a
   stolen phone should leave — was being discarded at the moment it was created. Fixed with
   `AuditService.recordRefusal`, which commits in its own transaction (`REQUIRES_NEW`) so it
   survives the rollback. Ordinary audit entries still join the caller's transaction, because a
   row claiming a state change happened must not outlive that change being undone.

Both are the kind of thing a demo would never surface and a test suite finds in an afternoon.

`docs/demo.sh` complements the suite rather than duplicating it: the tests assert the rules,
the script shows the journey.

## Known limits

Stated plainly, because the difference between a prototype and a product is mostly this list.

**No admin or family screens.** Responder verification, suspension, statistics, the audit trail,
family groups and plans exist in the API and in the tests, but nobody can reach them from the
app. A five-day build spent its screen time on the emergency path.

**The alarm only sounds while the app is open.** A trusted contact with SOSync closed hears
nothing until they open it, which is the gap push notifications close. Nobody walks around with a
panic app open, so this is the first thing to build next.

**Not an emergency service.** SOSync is not connected to any national emergency number and makes
no claim of police dispatch or guaranteed rescue. "Responders" means verified private security,
campus or building security, or community responders.

**No real notification gateways.** SMS and push are recorded as `SIMULATED`. Wiring in a real
one is a single interface implementation each — `SmsGateway` and `PushGateway` — plus
credentials. Nothing at the call sites changes.

**Delivery is synchronous.** Fan-out runs inside the triggering transaction. With simulated
gateways that costs microseconds and lets the API response name everyone who was reached. A real
SMS gateway takes about a second per recipient, at which point delivery must move to a worker
draining `PENDING` rows: write the row in the transaction, send outside it.

**Tokens cannot be revoked.** Refresh tokens are stateless, so logout cannot invalidate one and a
stolen token stays valid until it expires. Production needs them persisted and rotated on use.

**Verification codes have no rate limit.** Six digits with unlimited guesses is not a control.
Needs an attempt counter and lockout.

**Payments are simulated.** No billing integration, and nothing on the emergency path consults
subscription state. That last part is deliberate: putting a panic button behind a paywall is a
decision to make openly, not one for a prototype to imply.

**Responder matching is straight-line distance.** Haversine in the application, not PostGIS and
not routing. It decides who is alerted and how the list sorts; it never promises an arrival time.

**Single region for phone parsing.** Numbers without a country code are read as Kenyan
(`Phones.DEFAULT_REGION`). Any number typed with `+` is parsed on its own terms, so international
contacts work, but a multi-country deployment should take the region from the user's profile.

**English only.** This is the most significant gap against the brief, which lists multilingual
access as a judged constraint, and it is a scope decision rather than an oversight.

What is built is the *mechanism*, which is the part that is awkward to add later: language is a
property of the recipient rather than of the deployment, carried on the account and overridable
per trusted contact (a contact with no app has no profile to read a preference from), resolved
per recipient at fan-out, and recorded on every delivery row. Alert wording lives in
`AlertStrings`, an interface with one implementation per language.

So adding Kiswahili, French or Portuguese means writing one class each and adding one enum
constant — the compiler then refuses to build until every message in that language exists.
Nothing in the fan-out, the schema or the API has to change. Wording that reaches somebody in
an emergency should be checked by a first-language speaker, which is the real cost, not the
code.

**Client-side text is not localised.** The server localises what it delivers itself — every
notification — and sends facts rather than sentences everywhere else (`stale: true` and
`ageSeconds`, not "last seen 4 minutes ago"). The app words those in the reader's language, so
that work lands in the Flutter client.

## Roadmap

Nearest first:

1. Real SMS via Africa's Talking, and FCM push
2. Kiswahili, then French and Portuguese — one `AlertStrings` implementation each, reviewed by
   a first-language speaker
3. Safety check-ins with missed-check-in escalation
4. Safe Journey monitored trip mode
5. USSD trigger, so the panic button works on a phone with no app at all
6. Persisted, rotating refresh tokens and rate-limited verification codes
7. Responder proximity matching over real road networks
8. Security-company dispatch integration

## API overview

50 endpoints, all documented interactively at `/docs`. The app uses a subset; the rest are
exercised by the tests and callable from the docs page.

| Area | Base path |
|---|---|
| Authentication and profile | `/api/auth` |
| Trusted contacts | `/api/me/contacts` |
| Emergencies | `/api/incidents` |
| Trusted contact inbox | `/api/alerts` |
| Responder | `/api/responder` |
| Notifications | `/api/notifications` |
| Family groups | `/api/families` |
| Plans | `/api/plans`, `/api/subscriptions` |
| Administration | `/api/admin` |
| Public metadata | `/api/meta` |

## Configuration

Every value has a working development default, so the service starts with nothing set. See
`backend/.env.example`.

| Variable | Purpose |
|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL |
| `SERVER_PORT` | HTTP port (default 8090) |
| `SOSYNC_JWT_SECRET` | HS256 signing key. **Must** be replaced before any deployment; holding it is enough to mint an admin token. |
| `SOSYNC_SIMULATE_NOTIFICATIONS` | `true` records deliveries as SIMULATED; `false` requires real gateways |
| `SOSYNC_SEED_DEMO` | Seeds the demo accounts on startup |
