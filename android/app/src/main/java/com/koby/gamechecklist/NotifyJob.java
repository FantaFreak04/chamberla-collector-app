package com.koby.gamechecklist;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class NotifyJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters p) {
        new Thread(() -> {
            try {
                run();
            } catch (Exception e) { /* try again next time */ }
            jobFinished(p, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        return true;
    }

    private static String[] http(String method, String url, String key, String token, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("apikey", key);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
        }
        return new String[]{String.valueOf(code), bo.toString("UTF-8")};
    }

    private void run() throws Exception {
        SharedPreferences sp = getSharedPreferences("ns", MODE_PRIVATE);
        String raw = sp.getString("s", "");
        if (raw.isEmpty()) return;
        JSONObject s = new JSONObject(raw);
        String url = s.getString("url"), key = s.getString("key"), uid = s.getString("uid");
        long now = System.currentTimeMillis() / 1000;
        if (s.optLong("exp", 0) - 60 < now) {
            String[] r = http("POST", url + "/auth/v1/token?grant_type=refresh_token", key, null,
                    new JSONObject().put("refresh_token", s.getString("rt")).toString());
            if (!r[0].equals("200")) return;
            JSONObject j = new JSONObject(r[1]);
            s.put("at", j.getString("access_token"));
            s.put("rt", j.getString("refresh_token"));
            s.put("exp", j.has("expires_at") ? j.getLong("expires_at") : now + j.optLong("expires_in", 3600));
            sp.edit().putString("s", s.toString()).apply();
        }
        String at = s.getString("at");
        boolean nm = s.optBoolean("nm", true), na = s.optBoolean("na", true);
        boolean show = !MainActivity.fg;
        Map<String, String> names = new HashMap<>();

        // messages
        long lastM = sp.getLong("lm", -1);
        String[] r = http("GET", url + "/rest/v1/messages?select=id,sender_id,body&recipient_id=eq." + uid
                + "&read_at=is.null&order=id.asc&limit=30", key, at, null);
        if (r[0].equals("200")) {
            JSONArray a = new JSONArray(r[1]);
            long max = lastM;
            JSONArray fresh = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject m = a.getJSONObject(i);
                long id = m.getLong("id");
                if (id > max) max = id;
                if (lastM >= 0 && id > lastM) fresh.put(m);
            }
            if (lastM < 0 || max > lastM) sp.edit().putLong("lm", Math.max(max, 0)).apply();
            if (show && nm && fresh.length() > 0) {
                fill(names, fresh, "sender_id", url, key, at);
                JSONObject last = fresh.getJSONObject(fresh.length() - 1);
                Set<String> senders = new HashSet<>();
                for (int i = 0; i < fresh.length(); i++) senders.add(fresh.getJSONObject(i).getString("sender_id"));
                String title, text;
                if (senders.size() == 1) {
                    title = nm(names, last.getString("sender_id"), "New message");
                    text = fresh.length() == 1 ? last.getString("body") : fresh.length() + " new messages";
                } else {
                    title = "New messages";
                    text = fresh.length() + " messages from " + senders.size() + " friends";
                }
                notify(1, "messages", "Messages", title, text, "msg");
            }
        }

        // message requests (people who are not friends yet)
        long lastR = sp.getLong("lr", -1);
        r = http("GET", url + "/rest/v1/message_requests?select=id,sender_id,body&recipient_id=eq." + uid
                + "&status=eq.pending&order=id.asc&limit=30", key, at, null);
        if (r[0].equals("200")) {
            JSONArray a = new JSONArray(r[1]);
            long max = lastR;
            JSONArray fresh = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject m = a.getJSONObject(i);
                long id = m.getLong("id");
                if (id > max) max = id;
                if (lastR >= 0 && id > lastR) fresh.put(m);
            }
            if (lastR < 0 || max > lastR) sp.edit().putLong("lr", Math.max(max, 0)).apply();
            if (show && nm && fresh.length() > 0) {
                fill(names, fresh, "sender_id", url, key, at);
                String title = "Message request", text;
                if (fresh.length() == 1) {
                    JSONObject m = fresh.getJSONObject(0);
                    text = nm(names, m.getString("sender_id"), "Someone") + " wants to message you";
                } else {
                    text = fresh.length() + " people want to message you";
                }
                notify(3, "requests", "Message requests", title, text, "msg");
            }
        }

        // friend updates
        long lastA = sp.getLong("la", -1);
        r = http("GET", url + "/rest/v1/activity?select=id,user_id,kind,platform,title&user_id=neq." + uid
                + "&order=id.desc&limit=30", key, at, null);
        if (r[0].equals("200")) {
            JSONArray a = new JSONArray(r[1]);
            long max = lastA;
            JSONArray fresh = new JSONArray();
            for (int i = a.length() - 1; i >= 0; i--) {
                JSONObject m = a.getJSONObject(i);
                long id = m.getLong("id");
                if (id > max) max = id;
                if (lastA >= 0 && id > lastA) fresh.put(m);
            }
            if (lastA < 0 || max > lastA) sp.edit().putLong("la", Math.max(max, 0)).apply();
            if (show && na && fresh.length() > 0) {
                fill(names, fresh, "user_id", url, key, at);
                String title = "Friend update", text;
                if (fresh.length() == 1) {
                    JSONObject m = fresh.getJSONObject(0);
                    String who = nm(names, m.getString("user_id"), "A friend");
                    String kd = m.optString("kind");
                    text = "profile".equals(kd)
                            ? who + ("Bio".equals(m.getString("title")) ? " updated their bio" : " changed their profile picture")
                            : "milestone".equals(kd)
                            ? who + " reached " + m.getString("title") + " in " + m.getString("platform")
                            : who + " added " + m.getString("title") + " (" + m.getString("platform") + ")";
                } else {
                    text = fresh.length() + " new updates from your friends";
                }
                notify(2, "updates", "Friend updates", title, text, "up");
            }
        }
    }

    private static String nm(Map<String, String> m, String k, String d) {
        String v = m.get(k);
        return v == null ? d : v;
    }

    private void fill(Map<String, String> names, JSONArray rows, String field, String url, String key, String at) throws Exception {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) ids.add(rows.getJSONObject(i).getString(field));
        if (ids.isEmpty()) return;
        String[] r = http("GET", url + "/rest/v1/profiles?select=id,username&id=in.(" + android.text.TextUtils.join(",", ids) + ")", key, at, null);
        if (!r[0].equals("200")) return;
        JSONArray a = new JSONArray(r[1]);
        for (int i = 0; i < a.length(); i++) names.put(a.getJSONObject(i).getString("id"), a.getJSONObject(i).getString("username"));
    }

    private void notify(int id, String chan, String chanName, String title, String text, String open) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel(chan, chanName,
                    id == 1 ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_DEFAULT));
        Intent i = new Intent(this, MainActivity.class).putExtra("open", open)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        nm.notify(id, new NotificationCompat.Builder(this, chan)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title).setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true).setContentIntent(pi)
                .setPriority(id == 1 ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_DEFAULT)
                .build());
    }
}
