package com.fintwin.twin.dto;

import java.time.LocalDate;
import java.util.List;

/** Monthly series of one feature: month-start snapshots plus the current value. */
public record FeatureHistory(long customerId, String feature, List<Point> points) {

    /**
     * @param date   bank-local date: the snapshot's month start, or the day of the last event for CURRENT
     * @param source SNAPSHOT or CURRENT
     */
    public record Point(LocalDate date, Object value, String source) {
    }
}
