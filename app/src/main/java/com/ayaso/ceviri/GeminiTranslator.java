package com.ayaso.ceviri;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GeminiTranslator {

    public interface Callback {
        void onResult(String[] out, String error, boolean rateLimited);
    }

    private static class HttpErr extends Exception {
        final int code;
        HttpErr(int code) { super("HTTP " + code); this.code = code; }
    }

    private static final ExecutorService EX = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void translate(final String key, final String model,
                                 final String[] texts, final Callback cb) {
        EX.execute(() -> {
            String[] res = null;
            String err = null;
            boolean limited = false;
            try {
                res = call(key, model, texts);
            } catch (HttpErr e) {
                err = e.getMessage();
                limited = e.code == 429;
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : "baglanti hatasi";
            }
            final String[] fr = res;
            final String fe = err;
            final boolean fl = limited;
            MAIN.post(() -> cb.onResult(fr, fe, fl));
        });
    }

    private static String[] call(String key, String model, String[] texts) throws Exception {
        JSONArray in = new JSONArray();
        for (String t : texts) in.put(t);

        String sys = "You are a professional video game localizer. "
                + "Translate each English string into natural, fluent Turkish, "
                + "the way a native Turkish gamer would read it in a game. "
                + "The strings are from one screen, so use them as context for each other. "
                + "The text comes from OCR and may contain small recognition errors; fix them silently. "
                + "Keep character names, numbers and symbols as they are when appropriate. "
                + "Return ONLY a JSON array of strings with exactly " + texts.length
                + " items, in the same order. No explanations.";

        JSONObject body = new JSONObject();
        body.put("systemInstruction", new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text", sys))));
        body.put("contents", new JSONArray().put(new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", in.toString())))));
        body.put("generationConfig", new JSONObject()
                .put("temperature", 0.2)
                .put("responseMimeType", "application/json"));

        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(4000);
            c.setReadTimeout(12000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("x-goog-api-key", key);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes("UTF-8"));
            }
            int code = c.getResponseCode();
            if (code != 200) throw new HttpErr(code);
            String resp = readAll(c.getInputStream());

            JSONArray parts = new JSONObject(resp).getJSONArray("candidates")
                    .getJSONObject(0).getJSONObject("content").getJSONArray("parts");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                sb.append(parts.getJSONObject(i).optString("text", ""));
            }
            String txt = sb.toString().trim();
            if (txt.startsWith("```")) {
                int a = txt.indexOf('\n');
                int b = txt.lastIndexOf("```");
                if (a >= 0 && b > a) txt = txt.substring(a + 1, b).trim();
            }
            JSONArray arr = new JSONArray(txt);
            if (arr.length() != texts.length) throw new Exception("eksik ceviri");
            String[] out = new String[texts.length];
            for (int i = 0; i < out.length; i++) out[i] = arr.getString(i);
            return out;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        return sb.toString();
    }
}
