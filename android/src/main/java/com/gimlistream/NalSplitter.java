package com.gimlistream;

import java.util.Arrays;

/**
 * Cuts a raw H.264 Annex B byte stream (as it arrives over USB, in arbitrary chunks) into NAL units,
 * each starting with its 00 00 01 start code. The last, possibly unfinished, NAL is kept until more data arrives.
 * Plain Java (no Android imports) so the self-check in main() runs on any JVM.
 */
final class NalSplitter {
    interface Sink {
        void nal(byte[] buf, int off, int len);
    }

    private static final int MAX_BUFFER = 8 << 20; // no start code in 8 MB means garbage: drop it

    private byte[] buf = new byte[1 << 20];
    private int len;

    void add(byte[] data, int n, Sink sink) {
        if (len + n > buf.length) {
            if (len + n > MAX_BUFFER) {
                len = 0;
            }
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
        }
        System.arraycopy(data, 0, buf, len, n);
        len += n;

        int start = -1;
        for (int i = 0; i + 2 < len; i++) {
            if (buf[i] == 0 && buf[i + 1] == 0 && buf[i + 2] == 1) {
                if (start >= 0) {
                    sink.nal(buf, start, i - start);
                }
                start = i;
                i += 2;
            }
        }
        if (start > 0) { // keep the unfinished NAL at the front
            System.arraycopy(buf, start, buf, 0, len - start);
            len -= start;
        }
    }

    /** Self-check: javac NalSplitter.java -d out && java -ea -cp out com.gimlistream.NalSplitter */
    public static void main(String[] args) {
        StringBuilder got = new StringBuilder();
        Sink sink = (b, off, n) -> got.append(Arrays.toString(Arrays.copyOfRange(b, off, off + n))).append(';');
        NalSplitter s = new NalSplitter();
        // SPS (type 7) split across two USB reads, then PPS (8), then the start of an IDR (5)
        s.add(new byte[] {0, 0, 0, 1, 0x67, 9}, 6, sink);
        s.add(new byte[] {9, 0, 0, 1, 0x68, 7, 0, 0, 1, 0x65}, 10, sink);
        assert got.toString().equals("[0, 0, 1, 103, 9, 9];[0, 0, 1, 104, 7];") : got;
        got.setLength(0);
        s.add(new byte[] {1, 2, 0, 0, 1, 0x41}, 6, sink); // IDR completes when the next NAL starts
        assert got.toString().equals("[0, 0, 1, 101, 1, 2];") : got;
        System.out.println("NalSplitter ok");
    }
}
