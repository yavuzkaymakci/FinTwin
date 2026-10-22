#!/usr/bin/env bash
# Clean, end-to-end measurement of the twin pipeline on the generated data set:
#   reset twin tables, replay cursor, consumer group and Kafka topics -> start backend -> bulk replay ->
#   wait until every event is stored and applied -> print timings.
# Run detached so it survives a lost terminal:
#   nohup scripts/measure-twin.sh > logs/measure-$(date +%Y%m%d-%H%M%S).log 2>&1 &
# Requires: infra up (make up), backend NOT running, core banking data generated, outbox prepared.
# Leaves the backend running (its log: logs/backend-<timestamp>.log).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKEND_LOG="$ROOT/logs/backend-$STAMP.log"
API="http://localhost:8080"
mkdir -p "$ROOT/logs"

PSQL=(docker exec fintwin-postgres psql -U fintwin -d fintwin -At -c)
KAFKA_TOPICS=(docker exec fintwin-kafka kafka-topics --bootstrap-server localhost:9092)

log() { echo "$(date '+%F %T') $*"; }

if curl -s -o /dev/null "$API/actuator/health"; then
    log "ERROR: a backend is already running on :8080; stop it first"
    exit 1
fi

total=$("${PSQL[@]}" "SELECT total_events FROM feed.replay_state WHERE id = 1")
if [ "${total:-0}" -eq 0 ]; then
    log "ERROR: outbox not prepared (POST /api/admin/replay/prepare)"
    exit 1
fi
log "outbox events: $total"

log "reset: twin tables, replay cursor"
"${PSQL[@]}" "TRUNCATE twin.customer_twin_state, twin.customer_twin_snapshot, twin.event_store" >/dev/null
"${PSQL[@]}" "UPDATE feed.replay_state SET cursor_position = 0, cursor_occurred_at = NULL, published_total = 0
              WHERE id = 1" >/dev/null
"${PSQL[@]}" "VACUUM FULL twin.customer_twin_state" >/dev/null

log "reset: consumer group and Kafka topics"
docker exec fintwin-kafka kafka-consumer-groups --bootstrap-server localhost:9092 --delete --group fintwin-twin \
    >/dev/null 2>&1 || true
"${KAFKA_TOPICS[@]}" --delete --topic 'fintwin\..*' >/dev/null 2>&1 || true
for _ in $(seq 1 60); do
    [ -z "$("${KAFKA_TOPICS[@]}" --list | grep '^fintwin\.' || true)" ] && break
    sleep 2
done
log "remaining fintwin topics: $("${KAFKA_TOPICS[@]}" --list | grep -c '^fintwin\.' || true)"

log "starting backend (log: $BACKEND_LOG)"
(cd "$ROOT/backend" && nohup ./mvnw -q spring-boot:run > "$BACKEND_LOG" 2>&1 &)
for _ in $(seq 1 120); do
    curl -s "$API/actuator/health" | grep -q '"UP"' && break
    sleep 2
done
curl -s "$API/actuator/health" | grep -q '"UP"' || { log "ERROR: backend did not start"; exit 1; }
log "backend up; topics: $("${KAFKA_TOPICS[@]}" --list | grep -c '^fintwin\.')"
sleep 10 # let the consumer group form

log "bulk replay started"
t0=$(date +%s)
curl -s -X POST "$API/api/admin/replay/bulk" >/dev/null
published_at=""
while true; do
    sleep 5
    now=$(date +%s)
    status=$(curl -s "$API/api/admin/replay/status")
    mode=$(echo "$status" | sed -n 's/.*"mode":"\([A-Z]*\)".*/\1/p')
    position=$(echo "$status" | sed -n 's/.*"position":\([0-9]*\).*/\1/p')
    stored=$("${PSQL[@]}" "SELECT COUNT(*) FROM twin.event_store")
    unapplied=$("${PSQL[@]}" "SELECT COUNT(*) FROM twin.event_store e
                               LEFT JOIN twin.customer_twin_state s USING (customer_id)
                               WHERE e.customer_seq > COALESCE(s.last_seq, 0)")
    log "t=$((now - t0))s mode=$mode published=$position stored=$stored unapplied=$unapplied"
    if [ -z "$published_at" ] && [ "$mode" = "IDLE" ]; then
        published_at=$now
        log "PUBLISH DONE in $((now - t0)) s: $position events, $((position / (now - t0 > 0 ? now - t0 : 1))) events/s"
    fi
    if [ "$stored" -eq "$total" ] && [ "$unapplied" -eq 0 ] && [ -n "$published_at" ]; then
        log "TWIN DONE in $((now - t0)) s: $stored events stored and applied," \
            "$((stored / (now - t0))) events/s end to end"
        break
    fi
    if [ $((now - t0)) -gt 3600 ]; then
        log "ERROR: timeout"
        exit 1
    fi
done
log "twins: $("${PSQL[@]}" "SELECT COUNT(*) FROM twin.customer_twin_state")," \
    "snapshots: $("${PSQL[@]}" "SELECT COUNT(*) FROM twin.customer_twin_snapshot")"
log "DLT messages: $(docker exec fintwin-kafka kafka-get-offsets --bootstrap-server localhost:9092 --time -1 2>/dev/null \
    | grep '\.DLT:' | awk -F: '{s += $3} END {print s + 0}')"
log "MEASUREMENT COMPLETE"
