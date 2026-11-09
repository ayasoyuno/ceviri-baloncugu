package com.ayaso.ceviri;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

public class CaptureService extends Service {
    private static final String CH = "ceviri";

    private WindowManager wm;
    private View bubble;
    private TextView panel;
    private WindowManager.LayoutParams bubbleLp, panelLp;
    private MediaProjection projection;
    private VirtualDisplay vd;
    private ImageReader reader;
    private Image held;
    private final Object lock = new Object();
    private HandlerThread ht;
    private Handler bg, main;
    private TextRecognizer recognizer;
    private Translator translator;
    private boolean modelReady = false;
    private boolean auto = false;
    private boolean busy = false;
    private String lastText = "";

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!auto) return;
            capture();
            main.postDelayed(this, 1500);
        }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (projection != null || intent == null) return START_NOT_STICKY;

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(
                new NotificationChannel(CH, "Ceviri", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CH)
                .setContentTitle("Ceviri baloncugu acik")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }

        main = new Handler(Looper.getMainLooper());
        ht = new HandlerThread("cap");
        ht.start();
        bg = new Handler(ht.getLooper());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        try {
            int code = intent.getIntExtra("code", 0);
            Intent data = intent.getParcelableExtra("data");
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopSelf(); }
            }, main);
            setupDisplay();
        } catch (Exception e) {
            toast("Ekran yakalama baslatilamadi: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }

        setupViews();
        setupMl();
        return START_NOT_STICKY;
    }

    private void setupDisplay() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int sw = dm.widthPixels, sh = dm.heightPixels, dpi = dm.densityDpi;

        synchronized (lock) {
            if (held != null) { held.close(); held = null; }
        }
        ImageReader old = reader;
        reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(r -> {
            Image img = null;
            try { img = r.acquireLatestImage(); } catch (Exception ignored) {}
            if (img == null) return;
            synchronized (lock) {
                if (held != null) held.close();
                held = img;
            }
        }, bg);

        if (vd == null) {
            vd = projection.createVirtualDisplay("ceviri", sw, sh, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, null);
        } else {
            vd.resize(sw, sh, dpi);
            vd.setSurface(reader.getSurface());
        }
        if (old != null) old.close();
    }

    private GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(4, Color.WHITE);
        return g;
    }

    private void setupViews() {
        float d = getResources().getDisplayMetrics().density;
        int size = (int) (52 * d);
        int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

        panel = new TextView(this);
        panel.setTextColor(Color.WHITE);
        panel.setBackgroundColor(0xDD000000);
        panel.setTextSize(18);
        panel.setGravity(Gravity.CENTER);
        panel.setMaxLines(4);
        panel.setPadding(32, 24, 32, 24);
        panel.setVisibility(View.GONE);
        panel.setOnClickListener(v -> panel.setVisibility(View.GONE));
        panelLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        wm.addView(panel, panelLp);

        bubble = new View(this);
        bubble.setBackground(circle(0xFF2979FF));
        bubble.setAlpha(0.8f);
        bubbleLp = new WindowManager.LayoutParams(size, size, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = 20;
        bubbleLp.y = 300;
        bubble.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy;
            float tx, ty;
            long t0;
            boolean moved;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = bubbleLp.x; sy = bubbleLp.y;
                        tx = e.getRawX(); ty = e.getRawY();
                        t0 = System.currentTimeMillis();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - tx, dy = e.getRawY() - ty;
                        if (Math.abs(dx) > 15 || Math.abs(dy) > 15) moved = true;
                        if (moved) {
                            bubbleLp.x = sx + (int) dx;
                            bubbleLp.y = sy + (int) dy;
                            wm.updateViewLayout(bubble, bubbleLp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            if (System.currentTimeMillis() - t0 > 600) toggleAuto();
                            else capture();
                        }
                        return true;
                }
                return false;
            }
        });
        wm.addView(bubble, bubbleLp);
    }

    private void setupMl() {
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        translator = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.TURKISH)
                .build());
        toast("Ceviri modeli indiriliyor, internet acik olsun...");
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(v -> { modelReady = true; toast("Ceviri hazir!"); })
                .addOnFailureListener(e -> toast("Model indirilemedi, interneti kontrol et"));
    }

    private void toggleAuto() {
        auto = !auto;
        bubble.setBackground(circle(auto ? 0xFF00C853 : 0xFF2979FF));
        if (auto) {
            lastText = "";
            main.post(loop);
        } else {
            main.removeCallbacks(loop);
            panel.setVisibility(View.GONE);
        }
    }

    private Bitmap grab() {
        synchronized (lock) {
            if (held == null) return null;
            Image.Plane p = held.getPlanes()[0];
            int ps = p.getPixelStride(), rs = p.getRowStride();
            int w = held.getWidth(), h = held.getHeight();
            Bitmap full = Bitmap.createBitmap(rs / ps, h, Bitmap.Config.ARGB_8888);
            p.getBuffer().rewind();
            full.copyPixelsFromBuffer(p.getBuffer());
            Bitmap out = Bitmap.createBitmap(full, 0, 0, w, h);
            full.recycle();
            return out;
        }
    }

    private void capture() {
        if (busy) return;
        if (!modelReady) {
            toast("Ceviri modeli henuz inmedi, biraz bekle");
            return;
        }
        busy = true;
        final boolean a = auto;
        if (!a) panel.setVisibility(View.INVISIBLE);
        bg.postDelayed(() -> {
            Bitmap bmp = grab();
            if (bmp == null) {
                main.post(() -> { busy = false; toast("Ekran goruntusu alinamadi, tekrar dene"); });
                return;
            }
            Bitmap use = bmp;
            if (a) {
                int top = bmp.getHeight() / 2;
                use = Bitmap.createBitmap(bmp, 0, top, bmp.getWidth(), bmp.getHeight() - top);
            }
            final Bitmap f = use;
            main.post(() -> ocr(f, a));
        }, a ? 0 : 250);
    }

    private void ocr(Bitmap bmp, boolean a) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener(res -> {
                    String t = res.getText().replace("\n", " ").trim();
                    if (t.isEmpty()) {
                        if (a) panel.setVisibility(View.GONE);
                        else showPanel("Yazi bulunamadi");
                        busy = false;
                        return;
                    }
                    if (a && t.equals(lastText)) { busy = false; return; }
                    lastText = t;
                    translator.translate(t)
                            .addOnSuccessListener(tr -> { showPanel(tr); busy = false; })
                            .addOnFailureListener(e -> { showPanel("Ceviri hatasi"); busy = false; });
                })
                .addOnFailureListener(e -> busy = false);
    }

    private void showPanel(String s) {
        panel.setText(s);
        panel.setVisibility(View.VISIBLE);
    }

    private void toast(String s) {
        Toast.makeText(getApplicationContext(), s, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (projection != null && vd != null) {
            main.postDelayed(this::setupDisplay, 300);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        auto = false;
        if (main != null) main.removeCallbacksAndMessages(null);
        try { if (bubble != null) wm.removeView(bubble); } catch (Exception ignored) {}
        try { if (panel != null) wm.removeView(panel); } catch (Exception ignored) {}
        synchronized (lock) {
            if (held != null) { held.close(); held = null; }
        }
        if (vd != null) vd.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        if (ht != null) ht.quit();
        if (recognizer != null) recognizer.close();
        if (translator != null) translator.close();
    }
              }
