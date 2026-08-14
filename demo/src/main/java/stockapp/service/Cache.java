package stockapp.service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A tiny time-to-live cache.
 *
 * <p>Sized for one desktop user watching a few dozen symbols, so it favours
 * being obviously correct over being clever: no eviction policy beyond
 * expiry, no background threads, and a hard cap so a runaway caller cannot grow
 * it without bound.
 *
 * <p>{@link #get} does not lock around the loader, so two simultaneous misses
 * for the same key can both call it. That is a deliberate trade: a duplicate
 * upstream request is cheaper than holding a lock across a network call.
 */
final class Cache<K, V> {

    private static final int MAX_ENTRIES = 2_000;

    private record Entry<V>(V value, long expiresAtMillis) {
        boolean isFresh() {
            return System.currentTimeMillis() < expiresAtMillis;
        }
    }

    private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();

    /** Returns the cached value if fresh, otherwise loads, stores and returns. */
    V get(K key, Duration ttl, Function<K, V> loader) {
        Entry<V> existing = entries.get(key);
        if (existing != null && existing.isFresh()) {
            return existing.value();
        }
        V loaded = loader.apply(key);
        if (loaded != null) {
            put(key, loaded, ttl);
        }
        return loaded;
    }

    /** The cached value only if it is still fresh, else null. Never loads. */
    V peek(K key) {
        Entry<V> entry = entries.get(key);
        return entry != null && entry.isFresh() ? entry.value() : null;
    }

    /**
     * The cached value even if it has expired. Used as a last resort when the
     * upstream API fails: a slightly stale price beats an error page.
     */
    V peekStale(K key) {
        Entry<V> entry = entries.get(key);
        return entry == null ? null : entry.value();
    }

    void put(K key, V value, Duration ttl) {
        if (entries.size() >= MAX_ENTRIES) {
            purgeExpired();
            if (entries.size() >= MAX_ENTRIES) {
                entries.clear();
            }
        }
        entries.put(key, new Entry<>(value, System.currentTimeMillis() + ttl.toMillis()));
    }

    void invalidate(K key) {
        entries.remove(key);
    }

    private void purgeExpired() {
        entries.entrySet().removeIf(e -> !e.getValue().isFresh());
    }
}
