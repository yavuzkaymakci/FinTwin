#!/usr/bin/env bash
# Verifies a fully replayed twin against itself and against core banking:
#   1. rebuild VERIFY (no writes) and REPLACE, comparing projection/snapshot checksums before and after;
#   2. balances of all accounts, and a random sample of customers, against core banking;
#   3. as-of balances against balances recomputed from core banking movements before the cut-off.
# Run detached:  nohup scripts/verify-twin.sh > logs/verify-$(date +%Y%m%d-%H%M%S).log 2>&1 &
# Requires the backend running and the replay complete (scripts/measure-twin.sh).
set -euo pipefail

API="http://localhost:8080"
SAMPLE=${SAMPLE:-20}
PSQL=(docker exec fintwin-postgres psql -U fintwin -d fintwin -At -F ' | ' -c)
log() { echo "$(date '+%F %T') $*"; }

STATE_SUM="SELECT md5(string_agg(customer_id || ':' || last_seq || ':' || state::text || ':' || features::text, ','
           ORDER BY customer_id)) FROM twin.customer_twin_state"
SNAP_SUM="SELECT md5(string_agg(customer_id || ':' || as_of || ':' || last_seq || ':' || state::text || ':'
          || features::text, ',' ORDER BY customer_id, as_of)) FROM twin.customer_twin_snapshot"

log "== 1. rebuild"
before_state=$("${PSQL[@]}" "$STATE_SUM")
before_snap=$("${PSQL[@]}" "$SNAP_SUM")
log "live projection checksum: $before_state / snapshots: $before_snap"
log "VERIFY: $(curl -s -X POST "$API/api/admin/twins/rebuild?mode=VERIFY")"
log "REPLACE: $(curl -s -X POST "$API/api/admin/twins/rebuild?mode=REPLACE")"
after_state=$("${PSQL[@]}" "$STATE_SUM")
after_snap=$("${PSQL[@]}" "$SNAP_SUM")
log "rebuilt projection checksum: $after_state / snapshots: $after_snap"
if [ "$before_state" = "$after_state" ] && [ "$before_snap" = "$after_snap" ]; then
    log "REBUILD IDENTICAL"
else
    log "REBUILD DIFFERS"
fi

log "== 2. consistency with core banking"
log "all accounts: $(curl -s "$API/api/admin/replay/consistency?sample=1" \
    | sed -n 's/.*"accounts":\([0-9]*\),"matchedAccounts":\([0-9]*\),"mismatchedAccounts":\([0-9]*\).*/accounts=\1 matched=\2 mismatched=\3/p')"
log "random sample of $SAMPLE customers (core vs twin, per account):"
"${PSQL[@]}" "
WITH picked AS (SELECT id FROM customer ORDER BY random() LIMIT $SAMPLE)
SELECT c.id AS customer, c.segment, a.id AS account, a.account_type, a.currency, a.balance AS core,
       (s.state -> 'accounts' -> a.id::text ->> 'balanceCents')::numeric / 100 AS twin,
       CASE WHEN a.balance = (s.state -> 'accounts' -> a.id::text ->> 'balanceCents')::numeric / 100
            THEN 'OK' ELSE 'MISMATCH' END
FROM picked p JOIN customer c ON c.id = p.id JOIN account a ON a.customer_id = c.id
LEFT JOIN twin.customer_twin_state s ON s.customer_id = c.id
ORDER BY c.id, a.id"

log "== 3. as-of balances vs core banking movements before the cut-off"
for customer in $("${PSQL[@]}" "SELECT id FROM customer ORDER BY random() LIMIT 5"); do
    for asof in 2024-12-15 2025-06-01 2026-01-31; do
        twin=$(curl -s "$API/api/twins/$customer?asOf=$asof" | python -c "
import json, sys
d = json.load(sys.stdin)
print(' '.join(f\"{a['accountId']}={a['balance']:.2f}\" for a in d.get('accounts', [])) or 'none')" 2>/dev/null || echo "none")
        core=$("${PSQL[@]}" "
SELECT COALESCE(string_agg(a.id || '=' || to_char(a.opening_balance + CASE WHEN a.account_type = 'CREDIT_CARD' THEN -1 ELSE 1 END
       * COALESCE((SELECT SUM(CASE WHEN t.direction = 'CREDIT' THEN t.amount ELSE -t.amount END) FROM bank_transaction t
                   WHERE t.account_id = a.id
                     AND t.occurred_at < (DATE '$asof' + 1)::timestamp AT TIME ZONE 'Europe/Istanbul'), 0),
       'FM9999999990.00'), ' ' ORDER BY a.id), 'none')
FROM account a
WHERE a.customer_id = $customer
  AND GREATEST(a.opened_at, DATE '2024-10-01') <= DATE '$asof'")
        verdict=$([ "$twin" = "$core" ] && echo OK || echo MISMATCH)
        log "customer $customer asOf $asof: twin [$twin] core [$core] $verdict"
    done
done
log "VERIFICATION COMPLETE"
