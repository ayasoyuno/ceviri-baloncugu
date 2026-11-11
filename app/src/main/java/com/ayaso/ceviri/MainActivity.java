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
import android.graphics.drawable.GradientDrawable;
import android.view.View;

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
                + "3) Oyuna geç. Baloncuğa dokununca ekran yerinde çevrilir.\n"
                + "4) Kapatmak için baloncuğa tekrar dokun ya da alttaki beyaz çubuğu yukarı kaydır.\n"
                + "5) Baloncuğa uzun basarsan otomatik mod açılır/kapanır (baloncuk yeşil olur).");
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
                addColors(root);
        addSeek(root, "Otomatik mod aralığı", "speed", 10, 30, 10, 10, " sn");
        addSeek(root, "Kaplama payı (orijinal yazı sızarsa artır)", "pad", 2, 20, 8, 1, " dp");

        addSwitch(root, "Akıllı mod (orijinal renk ve arka planı kopyala)", "smart", true);
        addSwitch(root, "Yazıyı ortala (kapalıysa soldan başlar)", "center", false);
        addSwitch(root, "Dokunma titreşimi", "haptic", true);
        addSwitch(root, "Alt çubukla kaydırarak kapat", "swipe", true);
        addSwitch(root, "Çevrilirken yeşil çerçeve", "pending", true);

        TextView aiHead = new TextView(this);
        aiHead.setTextSize(18);
        aiHead.setPadding(0, 48, 0, 8);
        aiHead.setText("Yapay zeka çevirisi");
        root.addView(aiHead);

        TextView aiInfo = new TextView(this);
        aiInfo.setTextSize(14);
        aiInfo.setText("Anahtarlar ücretsiz alınır. Biri hata verirse diğeri, o da olmazsa "
                + "çevrimdışı çeviri kullanılır.");
        root.addView(aiInfo);

        addSwitch(root, "Yapay zeka çevirisini kullan", "ai", true);
        addSwitch(root, "Önce 2. sağlayıcıyı (Groq) dene", "b_first", true);

        TextView g1 = new TextView(this);
        g1.setPadding(0, 32, 0, 0);
        g1.setText("1. sağlayıcı: Gemini (aistudio.google.com/apikey)");
        root.addView(g1);
        addField(root, "Gemini API anahtarı", "apikey", "", true);
        addField(root, "Gemini model adı", "model", "gemini-3.5-flash-lite", false);

        TextView g2 = new TextView(this);
        g2.setPadding(0, 32, 0, 0);
        g2.setText("2. sağlayıcı: Groq (console.groq.com) ya da OpenAI uyumlu servis");
        root.addView(g2);
        addField(root, "Adres", "b_url", "https://api.groq.com/openai/v1", false);
        addField(root, "API anahtarı", "b_key", "", true);
        addField(root, "Model adı", "b_model", "openai/gpt-oss-120b", false);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }    private void addColors(LinearLayout root) {
        TextView t = new TextView(this);
        t.setPadding(0, 24, 0, 8);
        t.setText("Baloncuk rengi");
        root.addView(t);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int[] colors = {0xFF2979FF, 0xFFE53935, 0xFFFB8C00, 0xFF8E24AA,
                0xFFEC407A, 0xFF00ACC1, 0xFF424242};
        for (int c : colors) {
            View v = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(c);
            g.setStroke(3, 0xFF888888);
            v.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(88, 88);
            lp.setMargins(0, 0, 20, 0);
            v.setLayoutParams(lp);
            v.setOnClickListener(x -> {
                prefs.edit().putInt("bcolor", c).apply();
                Toast.makeText(this, "Renk seçildi", Toast.LENGTH_SHORT).show();
            });
            row.addView(v);
        }
        root.addView(row);
    }

    private void addSwitch(LinearLayout root, String text, String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(text);
        s.setChecked(prefs.getBoolean(key, def));
        s.setPadding(0, 24, 0, 0);
        s.setOnCheckedChangeListener((v, c) -> prefs.edit().putBoolean(key, c).apply());
        root.addView(s);
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
