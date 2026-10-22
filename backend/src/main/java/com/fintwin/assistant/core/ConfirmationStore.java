package com.fintwin.assistant.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Human-in-the-loop gate for starting simulations. The first {@code startSimulation} call only registers a pending
 * run and returns a token; the run starts when the tool is called again with that token in a <em>later</em> turn of
 * the same conversation whose user message is an explicit confirmation. The model therefore cannot confirm on the
 * user's behalf within the same turn, and a "no" never starts anything.
 */
public final class ConfirmationStore {

    public record Pending(String token, String conversationId, long scenarioId, Long baselineScenarioId,
                          LocalDate startDate, int issuedTurn, Instant issuedAt) {
    }

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern YES = Pattern.compile(
            "\\b(evet|onaylıyorum|onaylarım|onayla|onay|tamam|başlat|çalıştır|olur|uygundur|yes|ok|okay|confirm)\\b",
            FLAGS);
    private static final Pattern NO = Pattern.compile(
            "\\b(hayır|iptal|vazgeç|vazgeçtim|dur|bekle|istemiyorum|no|cancel)\\b", FLAGS);

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Clock clock;

    public ConfirmationStore(Clock clock) {
        this.clock = clock;
    }

    public Pending issue(TurnContext turn, long scenarioId, Long baselineScenarioId, LocalDate startDate) {
        purge();
        String token = UUID.randomUUID().toString().substring(0, 8);
        Pending p = new Pending(token, turn.conversationId(), scenarioId, baselineScenarioId, startDate, turn.turn(),
                clock.instant());
        pending.put(token, p);
        return p;
    }

    /** The pending run, if the token belongs to this conversation, was issued earlier and the user said yes. */
    public Optional<Pending> confirm(String token, TurnContext turn) {
        purge();
        Pending p = token == null ? null : pending.get(token.trim());
        if (p == null || !p.conversationId().equals(turn.conversationId()) || p.issuedTurn() >= turn.turn()
                || !affirmative(turn.userMessage())) {
            return Optional.empty();
        }
        pending.remove(p.token());
        return Optional.of(p);
    }

    /**
     * Confirmation without a token: the pending run of this conversation for {@code scenarioId}, issued in an earlier
     * turn, if the current user message is an explicit yes. The model only has to remember the scenario id (it is in
     * the confirmation question), not the token (tool results of earlier turns are not in the chat memory).
     */
    public Optional<Pending> confirmPending(long scenarioId, TurnContext turn) {
        purge();
        if (!affirmative(turn.userMessage())) {
            return Optional.empty();
        }
        Optional<Pending> found = pending.values().stream()
                .filter(p -> p.conversationId().equals(turn.conversationId()) && p.scenarioId() == scenarioId
                        && p.issuedTurn() < turn.turn())
                .max(java.util.Comparator.comparing(Pending::issuedAt));
        found.ifPresent(p -> pending.values().removeIf(q -> q.conversationId().equals(p.conversationId())
                && q.scenarioId() == scenarioId));
        return found;
    }

    public static boolean affirmative(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.forLanguageTag("tr"));
        return YES.matcher(text).find() && !NO.matcher(text).find();
    }

    private void purge() {
        Instant cutoff = clock.instant().minus(TTL);
        pending.values().removeIf(p -> p.issuedAt().isBefore(cutoff));
    }
}
