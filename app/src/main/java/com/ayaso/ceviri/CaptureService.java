package com.ayaso.ceviri;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
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
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class CaptureService extends Service {
    private static final String CH = "ceviri";

    static class Blk {
        Rect rect;
        String text;
        int lines;
        int bg = 0xFF000000;
        int fg = Color.WHITE;
    }

    static class Item {
        RectF bg;
        int bgColor;
        StaticLayout layout;
        float tx, ty;
    }

        static class OverlayView extends View {
        private List<Item> items = new ArrayList<>();
        private List<RectF> pending = new ArrayList<>();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

        OverlayView(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            line.setColor(0xFF00E676);
            line.setStrokeWidth(2.5f * c.getResources().getDisplayMetrics().density);
        }

        void setItems(List<Item> l) { items = l; invalidate(); }

        boolean hasItems() { return !items.isEmpty(); }

        void setPending(List<RectF> l) { pending = l; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            for (Item it : items) {
                paint.setColor(it.bgColor);
                c.drawRoundRect(it.bg, 8f, 8f, paint);
                c.save();
                c.translate(it.tx, it.ty);
                it.layout.draw(c);
                c.restore();
            }
            if (!pending.isEmpty()) {
                double wave = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 95.0);
                line.setAlpha((int) (70 + 185 * wave));
                for (RectF r : pending) c.drawRoundRect(r, 10f, 10f, line);
                postInvalidateOnAnimation();
            }
        }
        }

    private WindowManager wm;
    private SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;
    private OverlayView overlay;
    private View bubble;
    private View swipeBar;
    private WindowManager.LayoutParams overlayLp, bubbleLp, swipeLp;
    private boolean swipeAttached = false;
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
    private String lastSig = "";
    private int sw, sh;
    private float density;
    private long pauseA = 0, pauseB = 0, lastWarn = 0;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!auto) return;
            capture();
            main.postDelayed(this, prefs.getInt("speed", 10) * 100L);
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
        density = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("ceviri", MODE_PRIVATE);

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
        sw = dm.widthPixels;
        sh = dm.heightPixels;
        int dpi = dm.densityDpi;

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

    private void updateOverlaySize() {
        if (overlay == null) return;
        overlayLp.width = sw;
        overlayLp.height = sh;
        try { wm.updateViewLayout(overlay, overlayLp); } catch (Exception ignored) {}
    }

    private GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(4, Color.WHITE);
        return g;
    }

    private void applyBubblePrefs() {
        if (bubble == null) return;
        int size = (int) (prefs.getInt("size", 52) * density);
        bubbleLp.width = size;
        bubbleLp.height = size;
        bubble.setAlpha(prefs.getInt("alpha", 80) / 100f);
        try { wm.updateViewLayout(bubble, bubbleLp); } catch (Exception ignored) {}
    }

    private void buzz(long ms) {
        if (!prefs.getBoolean("haptic", true)) return;
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception ignored) {}
    }

    private void setupSwipeBar() {
        swipeBar = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(40 * density);
        g.setColor(0x88FFFFFF);
        g.setStroke(2, 0x88000000);
        swipeBar.setBackground(g);
        swipeLp = new WindowManager.LayoutParams((int) (150 * density), (int) (26 * density),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        swipeLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        swipeLp.y = (int) (44 * density);
        swipeBar.setOnTouchListener(new View.OnTouchListener() {
            float y0;
            boolean done;

            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    y0 = e.getRawY();
                    done = false;
                } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && !done
                        && y0 - e.getRawY() > 50 * density) {
                    done = true;
                    buzz(30);
                    main.post(() -> dismissByUser());
                }
                return true;
            }
        });
    }

    private void showSwipeBar() {
        if (!prefs.getBoolean("swipe", true) || swipeAttached) return;
        try {
            wm.addView(swipeBar, swipeLp);
            swipeAttached = true;
        } catch (Exception ignored) {}
    }

    private void hideSwipeBar() {
        if (!swipeAttached) return;
        try { wm.removeView(swipeBar); } catch (Exception ignored) {}
        swipeAttached = false;
    }

    private void dismissByUser() {
        if (auto) toggleAuto(); else clearOverlay();
    }

    private void setupViews() {
        int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

        // Ceviri katmani: tum ekrani kaplar, dokunmayi oyuna gecirir
        overlay = new OverlayView(this);
        overlay.setVisibility(View.INVISIBLE);
        overlayLp = new WindowManager.LayoutParams(sw, sh, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        overlayLp.gravity = Gravity.TOP | Gravity.START;
        overlayLp.x = 0;
        overlayLp.y = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            overlayLp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= 28) {
            overlayLp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        wm.addView(overlay, overlayLp);

        // Baloncuk
        bubble = new View(this);
        bubble.setBackground(circle(0xFF2979FF));
        int size = (int) (prefs.getInt("size", 52) * density);
        bubble.setAlpha(prefs.getInt("alpha", 80) / 100f);
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
                        bubble.animate().scaleX(0.82f).scaleY(0.82f).setDuration(80).start();
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
                    case MotionEvent.ACTION_CANCEL:
                        bubble.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                        if (e.getActionMasked() == MotionEvent.ACTION_UP && !moved) {
                            if (System.currentTimeMillis() - t0 > 600) {
                                buzz(60);
                                toggleAuto();
                            } else {
                                buzz(25);
                                if (!auto && overlay.getVisibility() == View.VISIBLE) {
                                    clearOverlay();
                                } else {
                                    capture();
                                }
                            }
                        }
                        return true;
                }
                return false;
            }
        });
        wm.addView(bubble, bubbleLp);

        setupSwipeBar();

        prefListener = (sp, key) -> applyBubblePrefs();
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
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
    }    private void clearOverlay() {
        overlay.setItems(new ArrayList<Item>());
        overlay.setPending(new ArrayList<RectF>());
        overlay.setVisibility(View.INVISIBLE);
        hideSwipeBar();
        lastSig = "";
    }

    private void toggleAuto() {
        auto = !auto;
        bubble.setBackground(circle(auto ? 0xFF00C853 : 0xFF2979FF));
        if (auto) {
            lastSig = "";
            main.post(loop);
        } else {
            main.removeCallbacks(loop);
            clearOverlay();
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
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        if (reader == null || dm.widthPixels != reader.getWidth()
                || dm.heightPixels != reader.getHeight()) {
            setupDisplay();
            updateOverlaySize();
            main.postDelayed(this::capture, 400);
            return;
        }

        busy = true;
        final boolean wasShown = overlay.getVisibility() == View.VISIBLE;
        // Kendi cevirimizi okumamak icin yakalama aninda katmani gizliyoruz
        if (wasShown) {
            overlay.setVisibility(View.INVISIBLE);
            if (swipeAttached) swipeBar.setVisibility(View.INVISIBLE);
        }
        main.postDelayed(() -> bg.post(() -> {
            final Bitmap bmp = grab();
            main.post(() -> {
                if (wasShown) {
                    overlay.setVisibility(View.VISIBLE);
                    if (swipeAttached) swipeBar.setVisibility(View.VISIBLE);
                }
                if (bmp == null) {
                    busy = false;
                    toast("Ekran goruntusu alinamadi, tekrar dene");
                    return;
                }
                ocr(bmp);
            });
        }), wasShown ? 120 : 0);
    }

    private boolean hasLetters(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) return true;
        }
        return false;
    }

    private void ocr(final Bitmap bmp) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener(res -> {
                    boolean smart = prefs.getBoolean("smart", true);
                    List<Blk> list = new ArrayList<>();
                    StringBuilder sig = new StringBuilder();
                    for (Text.TextBlock b : res.getTextBlocks()) {
                        Rect r = b.getBoundingBox();
                        String t = b.getText().replace("\n", " ").trim();
                        if (r == null || t.length() < 2 || !hasLetters(t)) continue;
                        Blk k = new Blk();
                        k.rect = new Rect(r);
                        k.text = t;
                        k.lines = Math.max(1, b.getLines().size());
                        sampleColors(bmp, k, smart);
                        list.add(k);
                        sig.append(t).append('|');
                    }
                    bmp.recycle();

                    if (list.isEmpty()) {
                        clearOverlay();
                        if (!auto) toast("Yazi bulunamadi");
                        busy = false;
                        return;
                    }
                    String s = sig.toString();
                    if (auto && s.equals(lastSig)) {
                        busy = false;
                        return;
                    }
                    translateSmart(list, s);
                })
                .addOnFailureListener(e -> {
                    bmp.recycle();
                    busy = false;
                });
    }

    private void sampleColors(Bitmap bmp, Blk k, boolean smart) {
        k.bg = 0xFF000000;
        k.fg = Color.WHITE;
        if (!smart) return;

        Rect r = k.rect;
        int l = Math.max(0, r.left), t = Math.max(0, r.top);
        int rr = Math.min(bmp.getWidth(), r.right), b = Math.min(bmp.getHeight(), r.bottom);
        if (rr - l < 2 || b - t < 2) return;

        int step = Math.max(1, (int) Math.sqrt((double) (rr - l) * (b - t) / 1500.0));
        HashMap<Integer, int[]> m = new HashMap<>();
        for (int y = t; y < b; y += step) {
            for (int x = l; x < rr; x += step) {
                int c = bmp.getPixel(x, y);
                int cr = Color.red(c), cg = Color.green(c), cb = Color.blue(c);
                int key = ((cr >> 4) << 8) | ((cg >> 4) << 4) | (cb >> 4);
                int[] a = m.get(key);
                if (a == null) { a = new int[4]; m.put(key, a); }
                a[0]++; a[1] += cr; a[2] += cg; a[3] += cb;
            }
        }
        int[] best = null;
        for (int[] a : m.values()) {
            if (best == null || a[0] > best[0]) best = a;
        }
        if (best == null) return;

        int br = best[1] / best[0], bgc = best[2] / best[0], bb = best[3] / best[0];
        int lumBg = (299 * br + 587 * bgc + 114 * bb) / 1000;

        int[] txt = null;
        for (int[] a : m.values()) {
            int dist = Math.abs(a[1] / a[0] - br) + Math.abs(a[2] / a[0] - bgc)
                    + Math.abs(a[3] / a[0] - bb);
            if (dist > 150 && (txt == null || a[0] > txt[0])) txt = a;
        }

        int fgColor = lumBg > 128 ? Color.BLACK : Color.WHITE;
        if (txt != null) {
            int fr = txt[1] / txt[0], fgc = txt[2] / txt[0], fb = txt[3] / txt[0];
            int lumFg = (299 * fr + 587 * fgc + 114 * fb) / 1000;
            if (Math.abs(lumFg - lumBg) >= 90) fgColor = Color.rgb(fr, fgc, fb);
        }
        k.bg = Color.rgb(br, bgc, bb);
        k.fg = fgColor;
    }

    private void translateSmart(List<Blk> list, String sig) {
        String[] texts = new String[list.size()];
        for (int i = 0; i < texts.length; i++) texts[i] = list.get(i).text;
        boolean bFirst = prefs.getBoolean("b_first", true);
        int[] order = bFirst ? new int[]{1, 0} : new int[]{0, 1};
        runChain(list, sig, texts, order, 0);
    }

    private void runChain(final List<Blk> list, final String sig, final String[] texts,
                          final int[] order, final int idx) {
        if (!prefs.getBoolean("ai", true) || idx >= order.length) {
            translateAll(list, sig);
            return;
        }
        final int p = order[idx];
        String key;
        String model;
        String base = "";
        if (p == 0) {
            key = prefs.getString("apikey", "").trim();
            model = prefs.getString("model", "gemini-3.5-flash-lite").trim();
            if (model.isEmpty()) model = "gemini-3.5-flash-lite";
        } else {
            key = prefs.getString("b_key", "").trim();
            model = prefs.getString("b_model", "openai/gpt-oss-120b").trim();
            if (model.isEmpty()) model = "openai/gpt-oss-120b";
            base = prefs.getString("b_url", "https://api.groq.com/openai/v1").trim();
            if (base.isEmpty()) base = "https://api.groq.com/openai/v1";
        }
        long paused = (p == 0) ? pauseA : pauseB;
        if (key.isEmpty() || System.currentTimeMillis() < paused) {
            runChain(list, sig, texts, order, idx + 1);
            return;
        }

        GeminiTranslator.Callback cb = (out, err, limited) -> {
            if (out != null) {
                finish(list, out, sig);
                return;
            }
            long t = System.currentTimeMillis();
            if (limited) {
                if (p == 0) pauseA = t + 60000; else pauseB = t + 60000;
            }
            if (t - lastWarn > 30000) {
                lastWarn = t;
                toast((p == 0 ? "Gemini" : "2. yapay zeka") + " calismadi (" + err + ")");
            }
            runChain(list, sig, texts, order, idx + 1);
        };
        if (p == 0) {
            GeminiTranslator.translate(key, model, texts, cb);
        } else {
            OpenAiTranslator.translate(base, key, model, texts, cb);
        }
    }

    // Cevrimdisi yedek ceviri
    private void translateAll(final List<Blk> list, final String sig) {
        final int n = list.size();
        final String[] out = new String[n];
        final int[] left = {n};
        for (int i = 0; i < n; i++) {
            final int idx = i;
            translator.translate(list.get(i).text)
                    .addOnSuccessListener(tr -> {
                        out[idx] = tr;
                        if (--left[0] == 0) finish(list, out, sig);
                    })
                    .addOnFailureListener(e -> {
                        if (--left[0] == 0) finish(list, out, sig);
                    });
        }
    }

    private void finish(List<Blk> list, String[] out, String sig) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (out[i] != null) items.add(buildItem(list.get(i), out[i]));
        }
        if (items.isEmpty()) {
            toast("Ceviri hatasi");
            busy = false;
            return;
        }
        lastSig = sig;
        overlay.setItems(items);
        overlay.setVisibility(View.VISIBLE);
        showSwipeBar();
        busy = false;
    }

    private Item buildItem(Blk k, String text) {
        Rect r = k.rect;
        int w = Math.max(r.width(), 140);
        int left = r.left;
        if (r.width() < 140) left = r.centerX() - w / 2;

        float avgLine = r.height() / (float) k.lines;
        float size = Math.max(18f, avgLine * 0.8f);
        float minSize = Math.max(14f, size * 0.5f);
        int availH = (int) (r.height() * 1.25f);

        Layout.Alignment al = prefs.getBoolean("center", false)
                ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL;

        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(k.fg);
        StaticLayout lay = null;
        for (int i = 0; i < 14; i++) {
            tp.setTextSize(size);
            lay = StaticLayout.Builder.obtain(text, 0, text.length(), tp, w)
                    .setAlignment(al)
                    .setIncludePad(false)
                    .build();
            if (lay.getHeight() <= availH || size <= minSize) break;
            size *= 0.92f;
        }

        float padBase = prefs.getInt("pad", 8) * density;
        float extra = avgLine * 0.15f;
        float padX = padBase * 1.5f + extra;
        float padY = padBase + extra;
        int boxH = Math.max(lay.getHeight(), r.height());
        Item it = new Item();
        it.layout = lay;
        it.bgColor = k.bg;
        it.tx = left;
        it.ty = r.top + (boxH - lay.getHeight()) / 2f;
        it.bg = new RectF(left - padX, r.top - padY, left + w + padX, r.top + boxH + padY);
        return it;
    }

    private void toast(String s) {
        Toast.makeText(getApplicationContext(), s, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (projection != null && vd != null) {
            main.postDelayed(() -> {
                setupDisplay();
                updateOverlaySize();
            }, 300);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        auto = false;
        if (main != null) main.removeCallbacksAndMessages(null);
        if (prefs != null && prefListener != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        }
        hideSwipeBar();
        try { if (bubble != null) wm.removeView(bubble); } catch (Exception ignored) {}
        try { if (overlay != null) wm.removeView(overlay); } catch (Exception ignored) {}
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
