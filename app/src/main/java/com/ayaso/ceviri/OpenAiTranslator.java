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

public class OpenAiTranslator {

    private static class HttpErr extends Exception {
        final int code;
        HttpErr(int code, String detail) {
            super("HTTP " + code + (detail.isEmpty() ? "" : ": " + detail));
            this.code = code;
        }
    }

    private static final ExecutorService EX = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void translate(final String baseUrl, final String key, final String model,
                                 final String[] texts, final GeminiTranslator.Callback cb) {
        EX.execute(() -> {
            String[] res = null;
            String err = null;
            boolean limited = false;
            try {
                res = call(baseUrl, key, model, texts);
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

    private static String[] call(String baseUrl, String key, String model, String[] texts)
            throws Exception {
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
                    if (!GeminiTranslator.glossary.isEmpty()) {
            sys += " Use these fixed translations whenever the term appears: "
                    + GeminiTranslator.glossary.replace("\n", "; ") + ".";
                                }

        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", sys));
        msgs.put(new JSONObject().put("role", "user").put("content", in.toString()));

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", msgs);
        body.put("temperature", 0.2);
        if (baseUrl.contains("groq.com") && model.contains("gpt-oss")) {
            body.put("reasoning_effort", "low");
        }

        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        URL url = new URL(base + "/chat/completions");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(4000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("User-Agent", "ceviri-baloncugu/1.0");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes("UTF-8"));
            }
            int code = c.getResponseCode();
            if (code != 200) {
                String detail = "";
                try {
                    InputStream es = c.getErrorStream();
                    if (es != null) {
                        JSONObject eo = new JSONObject(readAll(es));
                        detail = eo.getJSONObject("error").optString("message", "");
                    }
                } catch (Exception ignored) {}
                if (detail.length() > 80) detail = detail.substring(0, 80);
                throw new HttpErr(code, detail);
            }
            String resp = readAll(c.getInputStream());

            String content = new JSONObject(resp).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content");
            int a = content.indexOf('[');
            int b = content.lastIndexOf(']');
            if (a < 0 || b <= a) throw new Exception("gecersiz cevap");
            JSONArray arr = new JSONArray(content.substring(a, b + 1));
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
