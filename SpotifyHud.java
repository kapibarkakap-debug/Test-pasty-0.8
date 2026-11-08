package dev.pastaclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spotify HUD: polls the Spotify Web API "currently playing" endpoint with an access token you paste
 * into the GUI (user-read-currently-playing scope). It works while Spotify plays in the background on any device.
 * Synced lyrics come from lrclib.net (free, no key); not every song has them.
 * The token only lives in memory and is only sent to api.spotify.com.
 */
public final class SpotifyHud {
    public static String token = "";
    static volatile String title = "", artist = "", status = "waiting...";
    static volatile long progress, duration, fetchedAt;
    static volatile boolean playing;
    static long nextPoll;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    // synced lyrics (LRC) for the current track
    static volatile String lyricsKey = "";
    static volatile long[] lyricTimes = new long[0];
    static volatile String[] lyricLines = new String[0];
    static final Pattern LRC = Pattern.compile("^\\[(\\d+):(\\d+(?:\\.\\d+)?)](.*)$");

    static void tick() {
        if (!PastaClient.SPOTIFY.on || token.isBlank()) return;
        long now = System.currentTimeMillis();
        if (now < nextPoll) return;
        nextPoll = now + 5000;
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1/me/player/currently-playing"))
                .header("Authorization", "Bearer " + token.trim()).GET().build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenAccept(r -> parse(r.statusCode(), r.body()))
            .exceptionally(e -> { status = "network error"; return null; });
    }

    static void parse(int code, String body) {
        if (code == 204) { title = ""; status = "nothing playing"; return; }
        if (code == 401) { title = ""; status = "token expired"; return; }
        if (code != 200) { title = ""; status = "error " + code; return; }
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (!o.has("item") || !o.get("item").isJsonObject()) { title = ""; status = "nothing playing"; return; }
            JsonObject item = o.getAsJsonObject("item");
            StringBuilder names = new StringBuilder();
            if (item.has("artists")) {
                JsonArray arr = item.getAsJsonArray("artists");
                for (int i = 0; i < arr.size(); i++) {
                    if (i > 0) names.append(", ");
                    names.append(arr.get(i).getAsJsonObject().get("name").getAsString());
                }
            }
            artist = names.toString();
            duration = item.has("duration_ms") ? item.get("duration_ms").getAsLong() : 0;
            progress = o.has("progress_ms") && !o.get("progress_ms").isJsonNull() ? o.get("progress_ms").getAsLong() : 0;
            playing = o.has("is_playing") && o.get("is_playing").getAsBoolean();
            fetchedAt = System.currentTimeMillis();
            title = item.get("name").getAsString();
            if (Visuals.LYRICS.value == 1) fetchLyrics(title, artist, duration);
        } catch (Exception e) { title = ""; status = "parse error"; }
    }

    // ---------- lyrics ----------
    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    static void fetchLyrics(String t, String a, long durMs) {
        final String key = t + "|" + a;
        if (key.equals(lyricsKey)) return;
        lyricsKey = key;
        lyricTimes = new long[0];
        lyricLines = new String[0];
        final String first = a.split(", ")[0];
        String url = "https://lrclib.net/api/get?artist_name=" + enc(first) + "&track_name=" + enc(t)
                + "&duration=" + (durMs / 1000);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "PastaClient/0.4").GET().build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenAccept(r -> {
                if (!key.equals(lyricsKey)) return;
                if (r.statusCode() == 200 && applyLyrics(r.body())) return;
                searchLyrics(key, t, first);          // exact match failed: try a looser search
            })
            .exceptionally(e -> null);
    }

    static void searchLyrics(String key, String t, String first) {
        String url = "https://lrclib.net/api/search?track_name=" + enc(t) + "&artist_name=" + enc(first);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "PastaClient/0.4").GET().build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenAccept(r -> {
                if (!key.equals(lyricsKey) || r.statusCode() != 200) return;
                try {
                    JsonArray arr = JsonParser.parseString(r.body()).getAsJsonArray();
                    for (JsonElement e : arr) {
                        if (applyLyrics(e.toString())) return;
                    }
                } catch (Exception ignored) { }
            })
            .exceptionally(e -> null);
    }

    /** Reads "syncedLyrics" from an LRCLIB JSON object. Returns true when lyrics were found. */
    static boolean applyLyrics(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (!o.has("syncedLyrics") || o.get("syncedLyrics").isJsonNull()) return false;
            String lrc = o.get("syncedLyrics").getAsString();
            List<Long> times = new ArrayList<>();
            List<String> lines = new ArrayList<>();
            for (String raw : lrc.split("\n")) {
                Matcher m = LRC.matcher(raw.trim());
                if (!m.matches()) continue;
                long ms = (long) ((Integer.parseInt(m.group(1)) * 60 + Double.parseDouble(m.group(2))) * 1000);
                times.add(ms);
                lines.add(m.group(3).trim());
            }
            if (times.isEmpty()) return false;
            long[] t = new long[times.size()];
            for (int i = 0; i < t.length; i++) t[i] = times.get(i);
            lyricLines = lines.toArray(new String[0]);
            lyricTimes = t;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Index of the line that is being sung at position posMs, or -1 before the first line. */
    static int lyricIndex(long posMs) {
        long[] t = lyricTimes;
        int idx = -1;
        for (int i = 0; i < t.length; i++) {
            if (t[i] <= posMs + 250) idx = i; else break;
        }
        return idx;
    }

    // ---------- drawing ----------
    static String clip(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

    static void draw(DrawContext ctx, MinecraftClient mc) {
        if (!PastaClient.SPOTIFY.on || mc.options.hudHidden || mc.currentScreen instanceof ThemeScreen) return;
        int sw = ctx.getScaledWindowWidth();
        int w = 150;
        int x = (int) (Visuals.spotX * (sw - w));
        int y = (int) (Visuals.spotY * (ctx.getScaledWindowHeight() - 28));
        ctx.fill(x, y, x + w, y + 28, 0x99000000);
        if (token.isBlank()) { ctx.drawTextWithShadow(mc.textRenderer, "Spotify: paste token in GUI", x + 4, y + 10, 0xFFAAAAAA); return; }
        if (title.isEmpty()) { ctx.drawTextWithShadow(mc.textRenderer, "Spotify: " + status, x + 4, y + 10, 0xFFAAAAAA); return; }
        ctx.drawTextWithShadow(mc.textRenderer, clip(title, 26), x + 4, y + 3, 0xFFFFFFFF);
        ctx.drawTextWithShadow(mc.textRenderer, clip(artist, 28), x + 4, y + 13, 0xFFAAAAAA);
        long pos = progress + (playing ? System.currentTimeMillis() - fetchedAt : 0);
        double frac = duration > 0 ? Math.min(1.0, pos / (double) duration) : 0;
        ctx.fill(x + 4, y + 24, x + w - 4, y + 26, 0x55FFFFFF);
        ctx.fill(x + 4, y + 24, x + 4 + (int) ((w - 8) * frac), y + 26, 0xFFFF4FC3);

        // current lyric line, one line under the player (optional second line = what comes next)
        if (Visuals.LYRICS.value == 1) {
            int i = lyricIndex(pos);
            drawLine(ctx, mc, i, x + w / 2, y + 31, sw, 0xFFFF4FC3);
            if (Visuals.LYRICS_NEXT.value == 1) drawLine(ctx, mc, i + 1, x + w / 2, y + 44, sw, 0xFFAAAAAA);
        }
    }

    static void drawLine(DrawContext ctx, MinecraftClient mc, int index, int centerX, int y, int sw, int color) {
        String[] lines = lyricLines;
        if (index < 0 || index >= lines.length || lines[index].isEmpty()) return;
        String line = lines[index];
        int tw = mc.textRenderer.getWidth(line);
        int bx = Math.max(2, Math.min(centerX - tw / 2 - 4, sw - tw - 10));
        ctx.fill(bx, y, bx + tw + 8, y + 12, 0x99000000);
        ctx.drawTextWithShadow(mc.textRenderer, line, bx + 4, y + 2, color);
    }
}
