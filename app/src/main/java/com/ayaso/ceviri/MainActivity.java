package com.ayaso.ceviri;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 100;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);

        TextView info = new TextView(this);
        info.setTextSize(16);
        info.setText("1) Baslat'a bas, izinleri ver.\n"
                + "2) Ekran paylasimi sorulunca \"Tum ekran\" sec.\n"
                + "3) Oyuna gec. Baloncuga dokununca ekran cevrilir.\n"
                + "4) Baloncuga uzun basarsan otomatik mod acilir/kapanir.\n"
                + "5) Cevirinin ustune dokunursan kapanir.");

        Button start = new Button(this);
        start.setText("Baslat");
        start.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                Toast.makeText(this, "Izni ac, sonra geri donup tekrar Baslat'a bas",
                        Toast.LENGTH_LONG).show();
                return;
            }
            MediaProjectionManager m =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(m.createScreenCaptureIntent(), REQ_CAPTURE);
        });

        Button stop = new Button(this);
        stop.setText("Durdur");
        stop.setOnClickListener(v -> stopService(new Intent(this, CaptureService.class)));

        root.addView(info);
        root.addView(start);
        root.addView(stop);
        setContentView(root);
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
