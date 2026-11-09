package com.ayaso.ceviri;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 100;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("ceviri", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);

        TextView info = new TextView(this);
        info.setTextSize(15);
        info.setText("1) Başlat'a bas, izinleri ver.\n"
                + "2) Ekran paylaşımı sorulunca \"Tüm ekran\" seç.\n"
                + "3) Oyuna geç. Baloncuğa dokununca ekran yerinde çevrilir, tekrar dokununca kapanır.\n"
                + "4) Baloncuğa uzun basarsan otomatik mod açılır/kapanır (baloncuk yeşil olur).");
        root.addView(info);

        Button start = new Button(this);
        start.setText("Başlat");
        start.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                Toast.makeText(this, "İzni aç, sonra geri dönüp tekrar Başlat'a bas",
                        Toast.LENGTH_LONG).show();
                return;
            }
            MediaProjectionManager m =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(m.createScreenCaptureIntent(), REQ_CAPTURE);
        });
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Durdur");
        stop.setOnClickListener(v -> stopService(new Intent(this, CaptureService.class)));
        root.addView(stop);

        TextView head = new TextView(this);
        head.setTextSize(18);
        head.setPadding(0, 48, 0, 8);
        head.setText("Ayarlar (anında uygulanır)");
        root.addView(head);

        addSeek(root, "Baloncuk boyutu", "size", 30, 100, 52, 1, " dp");
        addSeek(root, "Baloncuk görünürlüğü", "alpha", 20, 100, 80, 1, " %");
        addSeek(root, "Otomatik mod aralığı", "speed", 10, 30, 10, 10, " sn");

        Switch smart = new Switch(this);
        smart.setText("Akıllı mod (orijinal renk ve arka planı kopyala)");
        smart.setChecked(prefs.getBoolean("smart", true));
        smart.setPadding(0, 32, 0, 0);
        smart.setOnCheckedChangeListener((v, c) ->
                prefs.edit().putBoolean("smart", c).apply());
        root.addView(smart);

        TextView aiHead = new TextView(this);
        aiHead.setTextSize(18);
        aiHead.setPadding(0, 48, 0, 8);
        aiHead.setText("Yapay zeka çevirisi (Gemini)");
        root.addView(aiHead);

        TextView aiInfo = new TextView(this);
        aiInfo.setTextSize(14);
        aiInfo.setText("aistudio.google.com/apikey adresinden ücretsiz anahtar al ve aşağıya yapıştır. "
                + "Anahtar yoksa ya da hata olursa çevrimdışı çeviri kullanılır.");
        root.addView(aiInfo);

        Switch ai = new Switch(this);
        ai.setText("Yapay zeka çevirisini kullan");
        ai.setChecked(prefs.getBoolean("ai", true));
        ai.setPadding(0, 24, 0, 0);
        ai.setOnCheckedChangeListener((v, c) -> prefs.edit().putBoolean("ai", c).apply());
        root.addView(ai);

        addField(root, "API anahtarı", "apikey", "", true);
        addField(root, "Model adı", "model", "gemini-2.5-flash-lite", false);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private void addField(LinearLayout root, String hint, String key, String def, boolean secret) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setSingleLine(true);
        et.setText(prefs.getString(key, def));
        if (secret) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void afterTextChanged(Editable e) {
                prefs.edit().putString(key, e.toString().trim()).apply();
            }
        });
        root.addView(et);
    }

    private void addSeek(LinearLayout root, String title, String key,
                         int min, int max, int def, int div, String unit) {
        int cur = prefs.getInt(key, def);
        TextView tv = new TextView(this);
        tv.setPadding(0, 24, 0, 0);
        tv.setText(title + ": " + fmt(cur, div) + unit);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(cur - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                int v = p + min;
                tv.setText(title + ": " + fmt(v, div) + unit);
                prefs.edit().putInt(key, v).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(tv);
        root.addView(sb);
    }

    private String fmt(int v, int div) {
        if (div == 1) return String.valueOf(v);
        return String.format(Locale.US, "%.1f", v / (double) div);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CAPTURE && res == RESULT_OK && data != null) {
            Intent s = new Intent(this, CaptureService.class);
            s.putExtra("code", res);
            s.putExtra("data", data);
            startForegroundService(s);
            moveTaskToBack(true);
        }
    }
}
