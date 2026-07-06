package madacode.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WilsonIntervalTest {

    @Test
    void computesWilson95IntervalForSmallSamples() {
        WilsonInterval interval = WilsonInterval.of(2, 3);

        assertEquals(21, interval.lowerPercentRounded());
        assertEquals(94, interval.upperPercentRounded());
    }

    @Test
    void emptySamplesUseZeroWidthZeroInterval() {
        WilsonInterval interval = WilsonInterval.of(0, 0);

        assertEquals(0, interval.lowerPercentRounded());
        assertEquals(0, interval.upperPercentRounded());
    }

    @Test
    void handlesAllFailuresAndAllPasses() {
        WilsonInterval allFailures = WilsonInterval.of(0, 3);
        WilsonInterval allPasses = WilsonInterval.of(3, 3);

        assertEquals(0, allFailures.lowerPercentRounded());
        assertEquals(56, allFailures.upperPercentRounded());
        assertEquals(44, allPasses.lowerPercentRounded());
        assertEquals(100, allPasses.upperPercentRounded());
    }

    @Test
    void rejectsInvalidCounts() {
        assertThrows(IllegalArgumentException.class, () -> WilsonInterval.of(2, 1));
    }
}
