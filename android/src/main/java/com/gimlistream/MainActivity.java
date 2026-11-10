package com.gimlistream;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.window.OnBackInvokedDispatcher;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/** DJI FPV Goggles V1/V2 -> fullscreen live view, decoded by the phone's hardware H.264 decoder. */
public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final int VENDOR_ID = 0x2ca3;
    private static final int PRODUCT_ID = 0x001f;
    private static final int IFACE = 3; // bulk interface: OUT takes the start command, IN streams H.264
    private static final byte[] MAGIC = "RMVT".getBytes(StandardCharsets.US_ASCII);

    private UsbManager usb;
    private TextView status;
    private SurfaceView video;
    private volatile Surface surface;
    private volatile boolean running;
    private volatile boolean live;      // false while the start menu is showing
    private volatile boolean recording; // wanted by the user; the worker opens/closes the file to match
    private Thread worker;
    private View menu;
    private Button recButton;
    private int videoW, videoH; // UI thread only: last decoded size, 0 until known

    // Worker thread only: the open recording, and the latest SPS/PPS so a file started mid-stream is playable.
    private OutputStream rec;
    private String recName;
    private byte[] sps, pps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        usb = getSystemService(UsbManager.class);
        status = findViewById(R.id.status);
        video = findViewById(R.id.video);
        video.getHolder().addCallback(this);
        // In-place resize (split-screen/freeform) doesn't recreate us, so re-letterbox on root size change.
        findViewById(R.id.root).addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (videoW > 0 && (r - l != or - ol || b - t != ob - ot)) fitVideo(videoW, videoH);
        });
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemBars();

        menu = findViewById(R.id.menu);
        recButton = findViewById(R.id.rec);
        findViewById(R.id.menu_live).setOnClickListener(v -> startLive(false));
        findViewById(R.id.menu_record).setOnClickListener(v -> startLive(true));
        findViewById(R.id.menu_recordings).setOnClickListener(v -> openRecordings());
        findViewById(R.id.menu_quit).setOnClickListener(v -> finish());
        recButton.setOnClickListener(v -> setRecording(!recording));
        if (Build.VERSION.SDK_INT >= 33) { // targetSdk 36+ no longer calls onBackPressed
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }
        showMenu();
    }

    @Override
    @SuppressWarnings("deprecation") // pre-Android 13 fallback
    public void onBackPressed() {
        back();
    }

    /** Back: from the live view to the menu, from the menu out of the app. */
    private void back() {
        if (live) showMenu(); else finish();
    }

    private void showMenu() {
        live = false; // the worker stops streaming, which also ends a recording
        menu.setVisibility(View.VISIBLE);
        recButton.setVisibility(View.GONE);
    }

    /** Files app at Downloads; recordings are in its Gimlistream folder (Android 10+). */
    private void openRecordings() {
        try {
            startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file manager found. Recordings are in Download/Gimlistream", Toast.LENGTH_LONG).show();
        }
    }

    private void startLive(boolean record) {
        setRecording(record);
        menu.setVisibility(View.GONE);
        recButton.setVisibility(View.VISIBLE);
        live = true;
    }

    private void setRecording(boolean on) {
        recording = on;
        recButton.setText(on ? "■ STOP" : "● REC");
        recButton.setBackgroundTintList(on ? android.content.res.ColorStateList.valueOf(0xffff453a) : null);
    }

    @SuppressWarnings("deprecation") // pre-Android 11 fallback
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startWorker(); // waits (non-blocking) if onPause's join timed out; never runs two workers
    }

    /** Starts the worker unless the old one is still winding down; then retries until it has exited. */
    private final Runnable startWorker = this::startWorker;

    private void startWorker() {
        if (worker != null && worker.isAlive()) {
            video.postDelayed(startWorker, 250); // onPause cancels this
            return;
        }
        running = true;
        worker = new Thread(this::loop, "gimlistream-usb");
        worker.start();
    }

    @Override
    protected void onPause() {
        running = false;
        video.removeCallbacks(startWorker);
        try {
            if (worker != null) worker.join(1000); // reads time out after 100 ms, so this is quick
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        super.onPause();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surface = null;
    }

    /** Worker thread: find goggles, get permission, stream; retry on anything going wrong. */
    private void loop() {
        boolean permissionRequested = false;
        long lastRequestMs = 0;
        boolean menuShown = false;
        while (running) {
            try {
                if (!live) {
                    if (!menuShown) setStatus(null); // don't leave "Waiting for goggles" over the menu
                    menuShown = true;
                    sleep(100);
                    continue;
                }
                menuShown = false;
                UsbDevice dev = findGoggles();
                if (dev == null) {
                    permissionRequested = false;
                    setStatus("Waiting for goggles…\nConnect DJI FPV Goggles V1/V2 with a USB-C cable (OTG)");
                    sleep(1000);
                } else if (!usb.hasPermission(dev)) {
                    setStatus("Allow Gimlistream to access the goggles");
                    // Re-ask every 15 s so a denied dialog doesn't need a replug.
                    long now = System.currentTimeMillis();
                    if (!permissionRequested || now - lastRequestMs > 15000) {
                        permissionRequested = true;
                        lastRequestMs = now;
                        // Result is picked up by polling hasPermission(), so no receiver is needed.
                        Intent i = new Intent("com.gimlistream.USB_PERMISSION").setPackage(getPackageName());
                        usb.requestPermission(dev, PendingIntent.getBroadcast(this, 0, i, PendingIntent.FLAG_MUTABLE));
                    }
                    sleep(500);
                } else if (surface == null) {
                    sleep(100);
                } else {
                    try {
                        stream(dev);
                    } catch (Exception e) {
                        if (!running) break; // paused mid-stream (e.g. surface torn down): exit now, onPause is joining us
                        setStatus("Connection lost: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) + "\nRetrying…");
                        sleep(1000);
                    }
                }
            } catch (RuntimeException e) {
                if (!running) break;
                Log.w("gimlistream", "loop error", e);
                setStatus("Error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) + "\nRetrying…");
                sleep(1000); // e.g. SecurityException from the USB calls; keep the worker alive
            }
        }
    }

    private UsbDevice findGoggles() {
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getVendorId() == VENDOR_ID && d.getProductId() == PRODUCT_ID) {
                return d;
            }
        }
        return null;
    }

    private void stream(UsbDevice dev) throws IOException {
        UsbDeviceConnection conn = usb.openDevice(dev);
        if (conn == null) {
            throw new IOException("can't open the goggles");
        }
        sps = null;
        pps = null; // don't start a new recording with the previous connection's parameter sets
        MediaCodec codec = null;
        try {
            Surface target = surface; // the field can go null at any time (onPause); don't configure the decoder with null
            if (target == null) {
                return;
            }
            if (dev.getInterfaceCount() <= IFACE) {
                throw new IOException("goggles have no video interface");
            }
            UsbInterface intf = dev.getInterface(IFACE);
            if (!conn.claimInterface(intf, true)) {
                throw new IOException("can't claim the video interface");
            }
            UsbEndpoint in = null, out = null;
            for (int i = 0; i < intf.getEndpointCount(); i++) {
                UsbEndpoint ep = intf.getEndpoint(i);
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep; else out = ep;
            }
            if (in == null || out == null) {
                throw new IOException("video interface has no bulk endpoints");
            }

            conn.bulkTransfer(out, MAGIC, MAGIC.length, 2000);
            setStatus("Connected – waiting for video\nTurn on the drone");

            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080); // max; real size comes from the SPS
            if (Build.VERSION.SDK_INT >= 30) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }
            codec.configure(format, target, null, 0);
            codec.start();

            MediaCodec c = codec;
            boolean[] showing = {false};
            NalSplitter nals = new NalSplitter();
            byte[] chunk = new byte[131072];
            int errors = 0;
            while (running && live && surface != null) {
                long t0 = SystemClock.elapsedRealtime();
                int n = conn.bulkTransfer(in, chunk, chunk.length, 100);
                if (n <= 0) {
                    if (findGoggles() == null) {
                        throw new IOException("goggles disconnected");
                    }
                    // Android reports timeouts and errors both as -1; only a -1 that returns well before the 100 ms timeout is a real error.
                    if (n < 0 && SystemClock.elapsedRealtime() - t0 < 50 && ++errors >= 20) {
                        throw new IOException("repeated USB read errors");
                    }
                    conn.bulkTransfer(out, MAGIC, MAGIC.length, 2000); // goggles stop streaming until poked again
                    continue;
                }
                errors = 0;
                nals.add(chunk, n, (buf, off, len) -> {
                    record(buf, off, len);
                    queue(c, buf, off, len);
                });
                if (drain(c) && !showing[0]) {
                    showing[0] = true;
                    setStatus(null);
                }
            }
        } finally {
            stopRecording();
            if (recording) { // stream ended (error/disconnect/stop) mid-recording: keep the REC button in sync with the closed file
                recording = false;
                runOnUiThread(() -> setRecording(false));
            }
            try {
                if (codec != null) codec.release();
            } finally {
                conn.close(); // always release the USB connection, even if codec.release() throws
            }
        }
    }

    /** Mirrors the REC toggle into an open/closed raw .h264 file and appends each NAL to it. */
    private void record(byte[] buf, int off, int len) {
        int type = buf[off + 3] & 0x1f;
        if (type == 7) sps = Arrays.copyOfRange(buf, off, off + len);
        if (type == 8) pps = Arrays.copyOfRange(buf, off, off + len);
        try {
            if (recording && rec == null) {
                rec = openRecording();
                if (sps != null) rec.write(sps);
                if (pps != null) rec.write(pps);
                toast("Recording to " + recName);
            } else if (!recording && rec != null) {
                stopRecording();
            }
            if (rec != null) rec.write(buf, off, len);
        } catch (IOException | RuntimeException e) { // MediaStore can throw SecurityException/IllegalArgumentException
            toast("Recording failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            recording = RecordingUtil.closeAfterFailure(rec);
            rec = null;
            // recording is false now, so the next NAL doesn't retry openRecording before the UI thread runs
            runOnUiThread(() -> setRecording(false));
        }
    }

    /** Android 10+: Download/Gimlistream (visible in the Files app). Older: the app's own Movies folder, no permission needed. */
    private OutputStream openRecording() throws IOException {
        String name = "Gimlistream " + new SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(new Date()) + ".h264";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Gimlistream");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            OutputStream out = uri == null ? null : getContentResolver().openOutputStream(uri);
            if (out == null) throw new IOException("can't create " + name);
            recName = "Download/Gimlistream/" + name;
            return new BufferedOutputStream(out, 1 << 20);
        }
        File f = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), name);
        recName = f.getPath();
        return new BufferedOutputStream(new FileOutputStream(f), 1 << 20);
    }

    private void stopRecording() {
        if (rec == null) return;
        try {
            rec.close();
            toast("Saved " + recName);
        } catch (Exception e) {
            toast("Saving " + recName + " failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        } finally {
            rec = null;
        }
    }

    private void toast(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
    }

    /** One NAL per input buffer; SPS/PPS flagged as codec config. */
    private void queue(MediaCodec codec, byte[] buf, int off, int len) {
        int idx = codec.dequeueInputBuffer(50_000);
        if (idx < 0) {
            return; // decoder is behind; dropping a NAL glitches a frame or two but keeps latency low
        }
        ByteBuffer in = codec.getInputBuffer(idx);
        if (in == null || len > in.capacity()) {
            codec.queueInputBuffer(idx, 0, 0, 0, 0);
            return;
        }
        in.clear();
        in.put(buf, off, len);
        int type = buf[off + 3] & 0x1f; // after the 00 00 01 start code
        int flags = (type == 7 || type == 8) ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0;
        codec.queueInputBuffer(idx, 0, len, System.nanoTime() / 1000, flags);
    }

    /** Renders every decoded frame immediately. Returns whether any frame was shown. */
    private boolean drain(MediaCodec codec) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean rendered = false;
        while (true) {
            int idx = codec.dequeueOutputBuffer(info, 0);
            if (idx >= 0) {
                codec.releaseOutputBuffer(idx, true);
                rendered = true;
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = codec.getOutputFormat();
                int w = f.getInteger(MediaFormat.KEY_WIDTH), h = f.getInteger(MediaFormat.KEY_HEIGHT);
                if (f.containsKey("crop-right") && f.containsKey("crop-bottom")) {
                    w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
                    h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
                }
                int vw = w, vh = h;
                runOnUiThread(() -> fitVideo(vw, vh));
            } else {
                return rendered;
            }
        }
    }

    /** Letterbox: scale the SurfaceView to the video's aspect ratio instead of stretching it. */
    private void fitVideo(int w, int h) {
        videoW = w;
        videoH = h;
        View root = findViewById(R.id.root);
        float scale = Math.min(root.getWidth() / (float) w, root.getHeight() / (float) h);
        video.setLayoutParams(new FrameLayout.LayoutParams(Math.round(w * scale), Math.round(h * scale), Gravity.CENTER));
    }

    private void setStatus(String text) {
        runOnUiThread(() -> {
            status.setText(text);
            status.setVisibility(text == null ? View.GONE : View.VISIBLE);
        });
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
