package com.gimlistream;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class NalSplitterTest {
    @Test
    public void dropsGarbageOverMaxBufferThenSplitsValidNals() {
        List<byte[]> got = new ArrayList<>();
        NalSplitter.Sink sink = (b, off, n) -> got.add(Arrays.copyOfRange(b, off, off + n));
        NalSplitter s = new NalSplitter();

        // 9 MB of 0x55 (no start code) crosses the 8 MB cap; nothing may be emitted and nothing may throw
        byte[] garbage = new byte[1 << 20];
        Arrays.fill(garbage, (byte) 0x55);
        for (int i = 0; i < 9; i++) {
            s.add(garbage, garbage.length, sink);
        }
        assertEquals(0, got.size());

        // still works afterwards: SPS, PPS, then the next start code releases PPS
        byte[] data = {0, 0, 1, 0x67, 9, 0, 0, 1, 0x68, 7, 0, 0, 1, 0x65};
        s.add(data, data.length, sink);
        assertEquals(2, got.size());
        assertEquals("[0, 0, 1, 103, 9]", Arrays.toString(got.get(0)));
        assertEquals("[0, 0, 1, 104, 7]", Arrays.toString(got.get(1)));
    }

    @Test
    public void overflowDropsBufferedBytesIncludingPartialStartCode() {
        List<byte[]> got = new ArrayList<>();
        NalSplitter.Sink sink = (b, off, n) -> got.add(Arrays.copyOfRange(b, off, off + n));
        NalSplitter s = new NalSplitter();

        // 7 MB of garbage ending in a partial start code "00 00"
        byte[] garbage = new byte[1 << 20];
        Arrays.fill(garbage, (byte) 0x55);
        for (int i = 0; i < 7; i++) {
            s.add(garbage, garbage.length, sink);
        }
        s.add(new byte[] {0, 0}, 2, sink);

        // this chunk overflows the 8 MB cap; it starts with the "01" that would complete the old tail
        garbage[0] = 1;
        s.add(garbage, garbage.length, sink);
        assertEquals(0, got.size());

        // if the tail had survived, the next start code would flush a 1 MB bogus NAL "00 00 01 55 ..."
        byte[] data = {0, 0, 1, 0x68, 7, 0, 0, 1, 0x41};
        s.add(data, data.length, sink);
        assertEquals(1, got.size());
        assertEquals("[0, 0, 1, 104, 7]", Arrays.toString(got.get(0)));
    }
}
