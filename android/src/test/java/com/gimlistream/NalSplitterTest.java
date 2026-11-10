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
}
