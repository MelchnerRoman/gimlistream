package com.gimlistream;

import static org.junit.Assert.assertFalse;

import java.io.OutputStream;
import org.junit.Test;

public class RecordingUtilTest {
    @Test
    public void runtimeExceptionOnCloseStillClearsRecordingFlag() {
        OutputStream bad = new OutputStream() {
            @Override public void write(int b) {}
            @Override public void close() { throw new IllegalStateException("boom"); }
        };
        boolean recording = true;
        recording = RecordingUtil.closeAfterFailure(bad);
        assertFalse(recording);
    }
}
