package dev.crow.karoo;

import java.util.ArrayDeque;
import java.util.Locale;

/** Recent measured samples, not the requested GPS or connection rate. No position data. */
final class SampleRate {
    private final ArrayDeque<Long> times = new ArrayDeque<>();
    void clear() { times.clear(); }
    void record(long timeNs) {
        if (!times.isEmpty() && timeNs <= times.peekLast()) return;
        times.add(timeNs);
        expire(timeNs);
        while (times.size() > 64) times.remove();
    }
    private void expire(long nowNs) {
        while (!times.isEmpty() && nowNs - times.peek() > 5_000_000_000L) times.remove();
    }
    String display(long nowNs) {
        expire(nowNs);
        if (times.size() < 2) return "—";
        return String.format(Locale.ROOT, "%.1f", (times.size() - 1) * 1e9 / (times.peekLast() - times.peek()));
    }
}
