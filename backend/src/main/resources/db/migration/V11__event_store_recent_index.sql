-- Newest-first reads of the event store for the live feed view (GET /api/live/twin).
CREATE INDEX idx_event_store_recorded_at ON twin.event_store (recorded_at DESC);
