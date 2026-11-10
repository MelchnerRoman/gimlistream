package com.gimlistream;

import static org.junit.Assert.assertFalse;

import static org.junit.Assert.assertEquals;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RecordingUtilTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void noCollisionKeepsName() throws IOException {
        assertEquals(new File(tmp.getRoot(), "a.h264"), RecordingUtil.pickUnusedFile(tmp.getRoot(), "a.h264"));
    }

    @Test
    public void collisionsGetNumberedSuffix() throws IOException {
        tmp.newFile("a.h264");
        assertEquals(new File(tmp.getRoot(), "a (1).h264"), RecordingUtil.pickUnusedFile(tmp.getRoot(), "a.h264"));
        tmp.newFile("a (1).h264");
        assertEquals(new File(tmp.getRoot(), "a (2).h264"), RecordingUtil.pickUnusedFile(tmp.getRoot(), "a.h264"));
    }

    @Test(expected = IOException.class)
    public void nullDirThrows() throws IOException {
        RecordingUtil.pickUnusedFile(null, "a.h264");
    }

    @Test(expected = IOException.class)
    public void regularFileInsteadOfDirThrows() throws IOException {
        RecordingUtil.pickUnusedFile(tmp.newFile("notadir"), "a.h264");
    }

    @Test
    public void missingDirIsCreated() throws IOException {
        File missing = new File(tmp.getRoot(), "missing");
        assertEquals(new File(missing, "a.h264"), RecordingUtil.pickUnusedFile(missing, "a.h264"));
        assertTrue(missing.isDirectory());
    }

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

    @Test
    public void closeAfterFailureNullReturnsFalse() {
        assertFalse(RecordingUtil.closeAfterFailure(null));
    }
}
