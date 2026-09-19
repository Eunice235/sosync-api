#!/usr/bin/env bash
# SOSync end-to-end walkthrough.
#
# Drives the whole emergency flow against a running service, including the steps that are
# supposed to fail: a double press, a second responder losing the race, a wrong safety PIN, an
# outsider trying to read the incident, and location access ending when the incident closes.
#
# Usage:  bash docs/demo.sh          (service must be running; see README)
#
# Safe to run repeatedly. Each run raises new incidents rather than resetting anything.
API="${SOSYNC_API:-http://localhost:8090}"
PASS="Sosync#2026"

j() { python -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception as e:
    print('PARSE-FAIL', e); sys.exit(0)
path = sys.argv[1:]
cur = d
for p in path:
    if isinstance(cur, list):
        cur = cur[int(p)] if len(cur) > int(p) else None
    elif isinstance(cur, dict):
        cur = cur.get(p)
    else:
        cur = None
    if cur is None: break
# Absent values print as nothing, not 'None', so callers can test with [ -z ... ].
if cur is None: sys.exit(0)
print(json.dumps(cur) if isinstance(cur,(dict,list)) else cur)
" "$@"; }

login() {
  curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"identifier\":\"$1\",\"password\":\"$PASS\"}"
}

step() { echo; echo "═══ $* ═══"; }

step "1. Login as Amina (reporter)"
AMINA_RAW=$(login "+254712000001")
AMINA=$(echo "$AMINA_RAW" | j accessToken)
echo "role=$(echo "$AMINA_RAW" | j user role) pinSet=$(echo "$AMINA_RAW" | j user safetyPinSet)"
[ -z "$AMINA" ] && { echo "LOGIN FAILED: $AMINA_RAW"; exit 1; }

step "2. Her trusted contacts"
curl -s "$API/api/me/contacts" -H "Authorization: Bearer $AMINA" \
  | python -c "
import sys,json
for c in json.load(sys.stdin):
    print(f\"  {c['name']:20} {c['phone']:16} priority={c['priority']} hasAccount={c['hasAccount']}\")"

step "3. Login as Daniel (verified responder) and confirm he is on shift"
DANIEL=$(login "+254712000003" | j accessToken)
curl -s -X PUT "$API/api/responder/location" -H "Authorization: Bearer $DANIEL" \
  -H 'Content-Type: application/json' -d '{"latitude":-1.2833,"longitude":36.8172}' \
  | j organization
curl -s -X PUT "$API/api/responder/availability" -H "Authorization: Bearer $DANIEL" \
  -H 'Content-Type: application/json' -d '{"availability":"AVAILABLE"}' \
  | python -c "import sys,json;d=json.load(sys.stdin);print('  ',d['organization'],d['verificationStatus'],d['availabilityStatus'])"

step "4. Amina holds the SOS button"
TRIGGER=$(curl -s -X POST "$API/api/incidents" -H "Authorization: Bearer $AMINA" \
  -H 'Content-Type: application/json' \
  -d '{"latitude":-1.2921,"longitude":36.8219,"accuracy":15.0,"silent":true,"note":"Taxi changed route, driver will not stop"}')
INCIDENT=$(echo "$TRIGGER" | j incident id)
echo "  incident=$INCIDENT"
echo "  summary: $(echo "$TRIGGER" | j summary)"
echo "  alreadyActive=$(echo "$TRIGGER" | j alreadyActive) status=$(echo "$TRIGGER" | j incident status)"
echo "  who was reached:"
echo "$TRIGGER" | python -c "
import sys,json
d=json.load(sys.stdin)
for n in d['notifications']:
    print(f\"    {n['channel']:7} {n['deliveryStatus']:10} -> {n['recipientLabel']}\")
print()
print('  what an SMS-only contact actually receives:')
for n in d['notifications']:
    if n['channel']=='SMS':
        print(f\"    -> {n['recipientLabel']}\")
        print(f\"       {n['body']}\")"

step "5. Double-press: does it create a second incident?"
SECOND=$(curl -s -X POST "$API/api/incidents" -H "Authorization: Bearer $AMINA" \
  -H 'Content-Type: application/json' -d '{"latitude":-1.2925,"longitude":36.8222}')
echo "  alreadyActive=$(echo "$SECOND" | j alreadyActive) sameIncident=$([ "$(echo "$SECOND" | j incident id)" = "$INCIDENT" ] && echo yes || echo NO)"

step "6. Grace (sister, trusted contact) checks her inbox"
GRACE=$(login "+254712000002" | j accessToken)
curl -s "$API/api/alerts" -H "Authorization: Bearer $GRACE" | python -c "
import sys,json
for i in json.load(sys.stdin):
    loc=i.get('location')
    print(f\"  {i['reporter']['fullName']} status={i['status']} as={i['viewerRelationship']} canSeeLocation={i['canViewLocation']}\")
    if loc: print(f\"    at {loc['latitude']},{loc['longitude']} age={loc['ageSeconds']}s  {loc['mapsLink']}\")"

step "7. Daniel sees the alert in his list"
curl -s "$API/api/responder/alerts" -H "Authorization: Bearer $DANIEL" | python -c "
import sys,json
rows=json.load(sys.stdin)
if not rows: print('  (empty)')
for a in rows:
    print(f\"  {a['reporter']['fullName']} status={a['status']} distance={a['distanceKm']}km waiting={a['waitingSeconds']}s mine={a['isMine']}\")
    print(f\"    note: {a.get('note')}\")"

step "8. Daniel accepts"
curl -s -X POST "$API/api/responder/incidents/$INCIDENT/accept" -H "Authorization: Bearer $DANIEL" \
  | python -c "
import sys,json;d=json.load(sys.stdin)
print('  status=',d['status'],'responder=',d['assignedResponder']['organization'],'distance=',d['assignedResponder']['distanceKm'],'km')"

step "9. A second responder tries to accept the same incident"
JOYCE=$(login "+254712000004" | j accessToken)
curl -s -X POST "$API/api/responder/incidents/$INCIDENT/accept" -H "Authorization: Bearer $JOYCE" \
  | python -c "import sys,json;d=json.load(sys.stdin);print('  ->',d.get('error'),':',d.get('message'))"

step "10. Amina's phone keeps reporting position"
for pair in "-1.2930 36.8228" "-1.2939 36.8236"; do
  set -- $pair
  curl -s -X POST "$API/api/incidents/$INCIDENT/locations" -H "Authorization: Bearer $AMINA" \
    -H 'Content-Type: application/json' -d "{\"latitude\":$1,\"longitude\":$2,\"accuracy\":12.0}" \
    | python -c "import sys,json;d=json.load(sys.stdin);print('  points=',d['locationPointCount'],'last=',d['location']['latitude'],d['location']['longitude'])"
done

step "11. Daniel is en route, then arrives"
for s in RESPONDING ARRIVED; do
  curl -s -X POST "$API/api/responder/incidents/$INCIDENT/status" -H "Authorization: Bearer $DANIEL" \
    -H 'Content-Type: application/json' -d "{\"status\":\"$s\"}" \
    | python -c "import sys,json;d=json.load(sys.stdin);print('  ->',d['status'])"
done

step "12. Someone tries to cancel with the WRONG PIN"
HTTP=$(curl -s -o "${TMPDIR:-/tmp}/sosync_wrong.json" -w "%{http_code}" -X POST "$API/api/incidents/$INCIDENT/cancel" \
  -H "Authorization: Bearer $AMINA" -H 'Content-Type: application/json' \
  -d '{"pin":"9999","reason":"nope"}')
echo "  HTTP $HTTP  $(cat "${TMPDIR:-/tmp}/sosync_wrong.json")"
echo "  incident still open? status=$(curl -s "$API/api/incidents/$INCIDENT" -H "Authorization: Bearer $AMINA" | j status)"

step "13. An unrelated user tries to view the incident"
# A fresh number each run, so this can be run repeatedly without colliding with the account it
# registered last time.
STRANGER_PHONE="+2547331$(printf '%05d' $((RANDOM % 100000)))"
OUTSIDER=$(curl -s -X POST "$API/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"fullName\":\"Random Stranger\",\"phone\":\"$STRANGER_PHONE\",\"password\":\"Stranger#2026\"}" \
  | j accessToken)
HTTP=$(curl -s -o "${TMPDIR:-/tmp}/sosync_out.json" -w "%{http_code}" "$API/api/incidents/$INCIDENT" \
  -H "Authorization: Bearer $OUTSIDER")
echo "  HTTP $HTTP  $(cat "${TMPDIR:-/tmp}/sosync_out.json")"

step "14. Daniel resolves it"
curl -s -X POST "$API/api/responder/incidents/$INCIDENT/status" -H "Authorization: Bearer $DANIEL" \
  -H 'Content-Type: application/json' \
  -d '{"status":"RESOLVED","note":"Reached her at the stage, walked her home"}' \
  | python -c "import sys,json;d=json.load(sys.stdin);print('  status=',d['status'],'elapsed=',d['elapsedSeconds'],'s')"

step "15. Does location sharing stop for Grace now?"
curl -s "$API/api/alerts?includeClosed=true" -H "Authorization: Bearer $GRACE" | python -c "
import sys,json
for i in json.load(sys.stdin):
    print(f\"  status={i['status']} canViewLocation={i['canViewLocation']} location={'present' if i.get('location') else 'WITHHELD'}\")"
echo "  trail via Grace: $(curl -s "$API/api/incidents/$INCIDENT/locations" -H "Authorization: Bearer $GRACE" | python -c 'import sys,json;print(len(json.load(sys.stdin)),"points")')"
echo "  trail via Amina: $(curl -s "$API/api/incidents/$INCIDENT/locations" -H "Authorization: Bearer $AMINA" | python -c 'import sys,json;print(len(json.load(sys.stdin)),"points")')"

step "16. The incident timeline"
curl -s "$API/api/incidents/$INCIDENT" -H "Authorization: Bearer $AMINA" | python -c "
import sys,json
d=json.load(sys.stdin)
for e in d.get('timeline') or []:
    print(f\"  {e['occurredAt'][11:19]}  {e['eventType']:16} {e.get('actorLabel') or '':28} {e.get('notes') or ''}\")"

step "17. Cancel flow on a fresh incident, with the CORRECT pin"
NEW=$(curl -s -X POST "$API/api/incidents" -H "Authorization: Bearer $AMINA" \
  -H 'Content-Type: application/json' -d '{"latitude":-1.2950,"longitude":36.8250}' | j incident id)
echo "  new incident=$NEW"
curl -s -X POST "$API/api/incidents/$NEW/cancel" -H "Authorization: Bearer $AMINA" \
  -H 'Content-Type: application/json' -d '{"pin":"4821","reason":"False alarm, I am safe"}' \
  | python -c "import sys,json;d=json.load(sys.stdin);print('  status=',d['status'],'reason=',d['cancelReason'])"

step "18. Admin dashboard"
ADMIN=$(login "+254712000009" | j accessToken)
curl -s "$API/api/admin/stats" -H "Authorization: Bearer $ADMIN" | python -c "
import sys,json
for k,v in json.load(sys.stdin).items(): print(f'  {k:24} {v}')"

step "19. Admin verifies the pending responder"
PENDING=$(curl -s "$API/api/admin/responders?status=PENDING" -H "Authorization: Bearer $ADMIN" | j items 0 id)
if [ -z "$PENDING" ]; then
  # Already verified by an earlier run of this script; verification only goes one way here.
  echo "  (nobody pending - Joyce was verified on a previous run)"
  curl -s "$API/api/admin/responders" -H "Authorization: Bearer $ADMIN" | python -c "
import sys,json
for r in json.load(sys.stdin)['items']:
    print(f\"  {r['organization']:30} {r['verificationStatus']:9} {r['availabilityStatus']}\")"
else
  echo "  pending responder id=$PENDING"
  curl -s -X PUT "$API/api/admin/responders/$PENDING/verification" -H "Authorization: Bearer $ADMIN" \
    -H 'Content-Type: application/json' -d '{"status":"VERIFIED"}' \
    | python -c "import sys,json;d=json.load(sys.stdin);print('  ',d['organization'],'->',d['verificationStatus'])"
fi

step "20. Role boundaries"
echo -n "  Amina (USER) -> /api/admin/stats: "
curl -s -o /dev/null -w "%{http_code}\n" "$API/api/admin/stats" -H "Authorization: Bearer $AMINA"
echo -n "  Amina (USER) -> /api/responder/alerts: "
curl -s -o /dev/null -w "%{http_code}\n" "$API/api/responder/alerts" -H "Authorization: Bearer $AMINA"
echo -n "  no token     -> /api/incidents/active: "
curl -s -o /dev/null -w "%{http_code}\n" "$API/api/incidents/active"
echo -n "  Daniel (RESPONDER) -> /api/admin/stats: "
curl -s -o /dev/null -w "%{http_code}\n" "$API/api/admin/stats" -H "Authorization: Bearer $DANIEL"

step "21. Audit trail for the incident (admin)"
curl -s "$API/api/admin/incidents/$INCIDENT/audit" -H "Authorization: Bearer $ADMIN" | python -c "
import sys,json
for a in json.load(sys.stdin):
    print(f\"  {a['occurredAt'][11:19]}  {a['action']:28} {a.get('detail') or ''}\")"

echo
echo "═══ done ═══"
