package com.fintwin.twin.service;

import com.fintwin.events.CoreBankingEvent;
import com.fintwin.twin.dto.FeatureHistory;
import com.fintwin.twin.dto.TwinEventView;
import com.fintwin.twin.dto.TwinView;
import com.fintwin.twin.projection.TwinFeatureCalculator;
import com.fintwin.twin.projection.TwinFeatures;
import com.fintwin.twin.projection.TwinJson;
import com.fintwin.twin.projection.TwinProjector;
import com.fintwin.twin.projection.TwinState;
import com.fintwin.twin.store.EventStoreRepository;
import com.fintwin.twin.store.TwinStateRepository;
import com.fintwin.twin.store.TwinStateRepository.Snapshot;
import com.fintwin.twin.store.TwinStateRepository.VersionedState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class TwinQueryService {

    public static final int MAX_EVENTS = 5_000;

    private final TwinStateRepository states;
    private final EventStoreRepository eventStore;

    public TwinQueryService(TwinStateRepository states, EventStoreRepository eventStore) {
        this.states = states;
        this.eventStore = eventStore;
    }

    /** The stored projection, features evaluated at the last applied event. */
    public TwinView current(long customerId) {
        VersionedState v = states.find(customerId).orElseThrow(() -> new TwinNotFoundException(customerId));
        TwinState state = v.state();
        return view(state, TwinJson.read(v.featuresJson(), TwinFeatures.class), "CURRENT");
    }

    /**
     * The twin as of the end of {@code asOf} (bank time): starts from the latest month-start snapshot not after the
     * cut-off and replays the remaining stored events that happened before it.
     */
    public TwinView asOf(long customerId, LocalDate asOf) {
        Instant cutoff = cutoff(asOf);
        TwinState state = stateAt(customerId, asOf);
        if (state.lastSequence == 0) {
            throw new TwinNotFoundException(customerId);
        }
        return view(state, TwinFeatureCalculator.compute(state, cutoff), "REPLAYED");
    }

    /** Projection reconstructed at the cut-off of {@code asOf} (empty state if no events before it). */
    public TwinState stateAt(long customerId, LocalDate asOf) {
        Instant cutoff = cutoff(asOf);
        Optional<Snapshot> snapshot = states.latestSnapshotOnOrBefore(customerId, asOf.plusDays(1));
        TwinState state = snapshot.map(s -> TwinJson.readState(s.stateJson()))
                .orElseGet(() -> TwinProjector.newState(customerId));
        for (CoreBankingEvent event : eventStore.eventsAfter(customerId, state.lastSequence, cutoff)) {
            TwinProjector.apply(state, event);
        }
        return state;
    }

    public List<TwinEventView> events(long customerId, LocalDate from, LocalDate to) {
        Instant start = from == null ? Instant.EPOCH : from.atStartOfDay(TwinProjector.BANK_ZONE).toInstant();
        Instant end = to == null ? Instant.parse("9999-01-01T00:00:00Z") : cutoff(to);
        return eventStore.findByCustomer(customerId, start, end, MAX_EVENTS).stream()
                .map(e -> new TwinEventView(e.eventId(), e.customerSequence(), e.eventType(), e.occurredAt(),
                        e.topic(), e.partition(), e.offset(), e.payload()))
                .toList();
    }

    public FeatureHistory history(long customerId, String feature) {
        if (!TwinFeatures.isSeriesFeature(feature)) {
            throw new IllegalArgumentException("Unknown feature '" + feature + "'; supported: "
                    + TwinFeatures.SERIES_FEATURES);
        }
        VersionedState current = states.find(customerId).orElseThrow(() -> new TwinNotFoundException(customerId));
        List<FeatureHistory.Point> points = new ArrayList<>();
        for (Map.Entry<LocalDate, Object> p : states.featureSeries(customerId, feature)) {
            points.add(new FeatureHistory.Point(p.getKey(), p.getValue(), "SNAPSHOT"));
        }
        Map<?, ?> features = TwinJson.read(current.featuresJson(), Map.class);
        points.add(new FeatureHistory.Point(TwinProjector.localDate(current.state().lastEventAt),
                features.get(feature), "CURRENT"));
        return new FeatureHistory(customerId, feature, points);
    }

    static Instant cutoff(LocalDate asOf) {
        return asOf.plusDays(1).atStartOfDay(TwinProjector.BANK_ZONE).toInstant();
    }

    private static TwinView view(TwinState s, TwinFeatures features, String source) {
        List<TwinView.AccountView> accounts = new ArrayList<>();
        s.accounts.forEach((id, a) -> accounts.add(new TwinView.AccountView(id, a.type, a.currency,
                BigDecimal.valueOf(a.balanceCents, 2),
                "CREDIT_CARD".equals(a.type) ? BigDecimal.valueOf(a.creditLimitCents, 2) : null, a.closed)));
        List<TwinView.LoanView> loans = new ArrayList<>();
        s.loans.forEach((id, l) -> loans.add(new TwinView.LoanView(id, l.type,
                BigDecimal.valueOf(l.installmentCents, 2), BigDecimal.valueOf(l.outstandingCents, 2),
                l.remainingInstallments, l.active)));
        return new TwinView(s.customerId, features.evaluatedAt(), source, s.lastSequence, s.lastEventAt, s.segment,
                s.city, s.age, s.customerSince, accounts, loans, features);
    }
}
