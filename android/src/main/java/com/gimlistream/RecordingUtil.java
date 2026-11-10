package com.gimlistream;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

final class RecordingUtil {
    private RecordingUtil() {}

    /** First unused file in dir: name, then "base (1).ext", "base (2).ext"... dir may be null (unmounted storage). */
    static File pickUnusedFile(File dir, String name) throws IOException {
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) throw new IOException("storage not available");
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot), ext = dot < 0 ? "" : name.substring(dot);
        for (int i = 1; f.exists(); i++) f = new File(dir, base + " (" + i + ")" + ext);
        return f;
    }

    /** Closes a failed recording's stream; nothing escapes. Returns the new value of the "recording" flag: always false. */
    static boolean closeAfterFailure(OutputStream rec) {
        try {
            if (rec != null) rec.close(); // release the file/MediaStore handle
        } catch (Exception ignored) { // must not escape: the caller resets its state
        }
        return false;
    }
}
