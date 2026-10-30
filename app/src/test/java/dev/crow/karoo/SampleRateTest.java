package dev.crow.karoo;

import org.junit.Test;
import static org.junit.Assert.*;

public class SampleRateTest {
    @Test public void reportsMeasuredRateAndIgnoresDuplicateAndOutOfOrderSamples() {
        SampleRate rate = new SampleRate();
        assertEquals("—", rate.display(0));
        rate.record(1_000_000_000L);
        assertEquals("—", rate.display(1_000_000_000L));
        rate.record(1_100_000_000L);
        rate.record(1_100_000_000L);
        rate.record(1_050_000_000L);
        assertEquals("10.0", rate.display(1_100_000_000L));
        rate.clear();
        rate.record(2_000_000_000L); rate.record(3_000_000_000L);
        assertEquals("1.0", rate.display(3_000_000_000L));
    }
    @Test public void stopsDisplayingAnOldRateAfterDataStopsAndAfterReset() {
        SampleRate rate = new SampleRate();
        rate.record(0); rate.record(100_000_000L);
        assertEquals("—", rate.display(6_000_000_000L));
        rate.record(6_000_000_000L);
        assertEquals("—", rate.display(6_000_000_000L));
        rate.record(6_100_000_000L);
        assertEquals("10.0", rate.display(6_100_000_000L));
        rate.clear(); assertEquals("—", rate.display(6_100_000_000L));
    }
}
