package com.gimlistream;

import java.io.OutputStream;

final class RecordingUtil {
    private RecordingUtil() {}

    /** Closes a failed recording's stream; nothing escapes. Returns the new value of the "recording" flag: always false. */
    static boolean closeAfterFailure(OutputStream rec) {
        try {
            if (rec != null) rec.close(); // release the file/MediaStore handle
        } catch (Exception ignored) { // must not escape: the caller resets its state
        }
        return false;
    }
}
