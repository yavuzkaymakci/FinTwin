package com.fintwin.generator.rng;

/**
 * Derives independent random streams from the master seed.
 * <p>
 * Every customer gets its own stream keyed by (seed, customer index), so a customer's simulated history does not
 * depend on how many customers are generated, in which order, or on how many threads simulate them.
 */
public final class SeededRandoms {

    /** Stream identifiers; keep stable, changing them changes all generated data. */
    public enum Stream {
        CUSTOMER(0x1L),
        MERCHANT_CATALOG(0x2L);

        private final long salt;

        Stream(long salt) {
            this.salt = salt;
        }
    }

    private SeededRandoms() {
    }

    public static Rand forStream(long seed, Stream stream, long index) {
        return new Rand(mix64(mix64(seed ^ mix64(stream.salt)) + index));
    }

    public static Rand forCustomer(long seed, int customerIndex) {
        return forStream(seed, Stream.CUSTOMER, customerIndex);
    }

    /** SplitMix64 finaliser: a bijective, well-avalanched 64-bit mix. */
    static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
