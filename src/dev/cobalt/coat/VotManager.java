package dev.cobalt.coat;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Minimal Yandex VOT client/player for the patched Cobalt YouTube TV application. */
public final class VotManager {
    private static final String TAG = "YTTV_VOT";
    private static final String HOST = "https://api.browser.yandex.ru";
    private static final String HMAC_KEY = "bt8xH3VOlb4mqf0nqAibnDOoiPlXsisf";
    private static final String VERSION = "24.4.0.0";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 YaBrowser/24.4.0.0 Safari/537.36";
    private static final int AUDIO_CHUNK_SIZE = 5295308;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Context sContext;
    private static volatile boolean sEnabled;
    private static volatile boolean sAutoEnabled;
    private static volatile String sManualVideoId;
    private static volatile String sVideoId;
    private static volatile double sPositionSec;
    private static volatile double sDurationSec;
    private static volatile boolean sPaused = true;
    private static volatile float sSpeed = 1.0f;
    private static volatile String sSourceAudioUrl;
    private static volatile float sTranslationVolume = 1.0f;
    private static volatile float sOriginalVolume = 0.18f;
    private static volatile boolean sLivelyVoice;
    private static volatile String sOAuthToken;
    private static volatile int sGeneration;
    private static volatile MediaPlayer sPlayer;
    private static volatile boolean sPrepared;
    private static volatile boolean sRequestInFlight;
    private static volatile int sRequestGeneration = -1;
    private static volatile long sLastRequestMs;
    private static volatile String sAudioVideoId;
    private static volatile long sLastStateLogAt;
    private static Session sSession;

    private VotManager() {}

    public static void init(Context context, boolean enabled) {
        if (context != null) sContext = context.getApplicationContext();
        setEnabled(enabled);
    }

    public static synchronized void setEnabled(boolean enabled) {
        sAutoEnabled = enabled;
        sManualVideoId = null;
        if (sEnabled == enabled) return;
        sEnabled = enabled;
        sGeneration++;
        if (!enabled) {
            sVideoId = null;
            releasePlayer(true);
            showStatus("VOT выключен", "#aaaaaa", 1800);
        } else {
            showStatus("VOT включён", "#00e676", 1800);
        }
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    public static synchronized void activateForVideo(String videoId) {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) return;
        sManualVideoId = videoId;
        if (!sEnabled) {
            sEnabled = true;
            sGeneration++;
        }
        forceRestart();
    }

    public static synchronized void forceRestart() {
        if (!sEnabled) return;
        sGeneration++;
        sVideoId = null;
        sSourceAudioUrl = null;
        sRequestGeneration = -1;
        sRequestInFlight = false;
        sLastRequestMs = 0L;
        sSession = null;
        releasePlayer(true);
        showStatus("VOT: повторно запрашиваю перевод…", "#00b0ff", 3000);
    }

    public static void configure(float translationVolume, float originalVolume,
                                 boolean livelyVoice, String oauthToken) {
        sTranslationVolume = Math.max(0f, Math.min(1f, translationVolume));
        sOriginalVolume = Math.max(0f, Math.min(1f, originalVolume));
        sLivelyVoice = livelyVoice;
        sOAuthToken = oauthToken == null ? "" : oauthToken.trim();
        MediaPlayer player = sPlayer;
        if (player != null) {
            try { player.setVolume(sTranslationVolume, sTranslationVolume); } catch (Throwable ignored) {}
        }
        if (sPrepared) setOriginalAudioActive(true);
    }

    public static void onPlayerState(String videoId, double positionSec, double durationSec,
                                     boolean paused, float speed, String sourceAudioUrl) {
        if (!sEnabled) return;
        if (sManualVideoId != null && !sManualVideoId.equals(videoId)) {
            sManualVideoId = null;
            if (!sAutoEnabled) {
                setEnabled(false);
                clearStatus();
                return;
            }
        }
        if (videoId == null || videoId.length() != 11) {
            if (sVideoId != null) {
                sGeneration++;
                sVideoId = null;
                releasePlayer(true);
            }
            clearStatus();
            return;
        }
        if (!Double.isFinite(durationSec) || durationSec >= 14400.0) {
            if (sVideoId != null) {
                sGeneration++;
                sVideoId = null;
                releasePlayer(true);
            }
            clearStatus();
            return;
        }
        sPositionSec = Math.max(0, positionSec);
        sDurationSec = Math.max(0, durationSec);
        sPaused = paused;
        sSpeed = speed > 0 ? speed : 1.0f;
        boolean changed = !videoId.equals(sVideoId);
        long now = System.currentTimeMillis();
        if (changed || now - sLastStateLogAt > 15000L) {
            sLastStateLogAt = now;
            Log.i(TAG, "player state video=" + videoId + " duration=" + durationSec +
                    " audio=" + (sourceAudioUrl == null || sourceAudioUrl.isEmpty() ? "missing" : "available"));
        }
        if (changed) {
            sVideoId = videoId;
            sSourceAudioUrl = sourceAudioUrl != null && !sourceAudioUrl.isEmpty() ? sourceAudioUrl : null;
            final String initialAudioUrl = sSourceAudioUrl;
            final int generation = ++sGeneration;
            releasePlayer(true);
            if (durationSec > 0) {
                startTranslationRequest(generation, videoId, durationSec, initialAudioUrl);
            }
        } else {
            if (sourceAudioUrl != null && !sourceAudioUrl.isEmpty()) sSourceAudioUrl = sourceAudioUrl;
            if (sPrepared && videoId.equals(sAudioVideoId)) syncPlayer(false);
            else if (durationSec > 0 && (!sRequestInFlight || sRequestGeneration != sGeneration) &&
                    (sRequestGeneration != sGeneration ||
                    (sSourceAudioUrl != null && !sSourceAudioUrl.isEmpty() &&
                    System.currentTimeMillis() - sLastRequestMs > 5000L))) {
                startTranslationRequest(sGeneration, videoId, durationSec, sSourceAudioUrl);
            }
        }
    }

    private static synchronized void startTranslationRequest(final int generation, final String videoId,
                                                             final double durationSec, final String audioUrl) {
        if ((sRequestInFlight && sRequestGeneration == generation) || !isCurrent(generation, videoId)) return;
        sRequestInFlight = true;
        sRequestGeneration = generation;
        sLastRequestMs = System.currentTimeMillis();
        Log.i(TAG, "starting request video=" + videoId + " generation=" + generation +
                " audio=" + (audioUrl == null || audioUrl.isEmpty() ? "missing" : "available"));
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { requestTranslation(generation, videoId, durationSec, audioUrl); }
                finally { if (sRequestGeneration == generation) sRequestInFlight = false; }
            }
        }, "YTTV-VOT-" + videoId);
        t.setDaemon(true);
        t.start();
    }

    public static String getStatusJson() {
        String state = !sEnabled ? "disabled" : (sPrepared ? "playing" : "waiting");
        return "{\"enabled\":" + sEnabled + ",\"state\":\"" + state + "\",\"video\":\"" +
                (sVideoId == null ? "" : sVideoId) + "\"}";
    }

    private static void requestTranslation(int generation, String videoId, double durationSec,
                                           String initialAudioUrl) {
        showStatus("VOT: запрашиваю перевод…", "#00b0ff", 2500);
        try {
            boolean audioUploaded = false;
            boolean shortUrlFallback = false;
            String videoUrl = "https://www.youtube.com/watch?v=" + videoId;
            int postUploadAudioRequests = 0;
            for (int attempt = 0; attempt < 120 && isCurrent(generation, videoId); attempt++) {
                TranslationResponse response = translate(videoUrl, durationSec);
                Log.i(TAG, "response status=" + response.status + " eta=" + response.remainingTimeSec +
                        " url=" + (response.url == null || response.url.isEmpty() ? "empty" : "present") +
                        " translationId=" + (response.translationId == null || response.translationId.isEmpty() ? "empty" : "present"));
                if (!isCurrent(generation, videoId)) return;
                if ((response.status == 1 || response.status == 5) && response.url != null && !response.url.isEmpty()) {
                    preparePlayer(generation, videoId, response.url);
                    return;
                }
                if (response.status == 0 && !shortUrlFallback) {
                    shortUrlFallback = true;
                    videoUrl = "https://youtu.be/" + videoId;
                    showStatus("VOT: повторяю запрос с альтернативным адресом…", "#ffab40", 4000);
                    continue;
                }
                if (response.status == 7) {
                    synchronized (VotManager.class) { sSession = null; }
                    continue;
                }
                if (response.status == 6) {
                    if (audioUploaded) {
                        if (++postUploadAudioRequests > 20) {
                            showStatus("VOT: сервер не обработал загруженное аудио", "#ff5252", 5000);
                            return;
                        }
                        showStatus("VOT: сервер обрабатывает аудио…", "#ffeb3b", 3500);
                        sleepInterruptibly(generation, videoId, 5000L);
                        continue;
                    }
                    showStatus("VOT: ожидаю аудио YouTube…", "#ffab40", 4000);
                    String sourceUrl = waitForSourceAudioUrl(generation, videoId, initialAudioUrl);
                    if (!isCurrent(generation, videoId)) return;
                    if (sourceUrl == null || sourceUrl.isEmpty()) {
                        if (!shortUrlFallback) {
                            // The browser userscript uses the short URL for its no-audio fallback.
                            shortUrlFallback = true;
                            videoUrl = "https://youtu.be/" + videoId;
                            showStatus("VOT: пробую резервный способ без аудиодорожки…", "#ffab40", 4000);
                            continue;
                        }
                        if (response.translationId == null || response.translationId.isEmpty()) {
                            showStatus("VOT: сервер не выдал ID перевода", "#ff5252", 5000);
                            return;
                        }
                        reportMissingSourceAudio(videoUrl);
                        AudioResponse fallbackResponse = uploadAudioBody(ensureSession(),
                                encodeAudioSingle(videoUrl, response.translationId,
                                        "fallback-empty-audio:video-translation:" + videoId, new byte[0]));
                        validateFinalAudioResponse(fallbackResponse);
                        audioUploaded = true;
                        showStatus("VOT: резервный запрос отправлен…", "#00b0ff", 4000);
                        continue;
                    }
                    Log.i(TAG, "source audio URL available; beginning upload");
                    if (response.translationId == null || response.translationId.isEmpty()) {
                        showStatus("VOT: сервер не выдал ID перевода", "#ff5252", 5000);
                        return;
                    }
                    showStatus("VOT: отправляю аудио для генерации…", "#ffab40", 4000);
                    uploadSourceAudio(generation, videoId, videoUrl,
                            response.translationId, sourceUrl);
                    if (!isCurrent(generation, videoId)) return;
                    audioUploaded = true;
                    showStatus("VOT: аудио загружено, запускаю перевод…", "#00b0ff", 4000);
                    continue;
                }
                if (response.status == 2 || response.status == 3) {
                    int wait = response.remainingTimeSec > 0 ? Math.min(20, Math.max(5, response.remainingTimeSec)) : 15;
                    showStatus("VOT: перевод готовится, ~" + response.remainingTimeSec + " сек", "#ffeb3b", 3500);
                    sleepInterruptibly(generation, videoId, wait * 1000L);
                    continue;
                }
                if (response.status == 5) {
                    showStatus("VOT: собираю готовый перевод…", "#ffeb3b", 3500);
                    sleepInterruptibly(generation, videoId, 5000L);
                    continue;
                }
                String msg = response.message == null || response.message.isEmpty() ? "перевод недоступен" : response.message;
                showStatus("VOT: " + msg, "#ff5252", 5000);
                return;
            }
            if (isCurrent(generation, videoId)) {
                showStatus("VOT: время ожидания перевода истекло", "#ff5252", 5000);
            }
        } catch (Throwable t) {
            Log.e(TAG, "VOT request failed", t);
            if (isCurrent(generation, videoId)) {
                String detail = t.getMessage();
                if (detail != null && (detail.startsWith("YouTube audio HTTP ") ||
                        detail.startsWith("VOT HTTP ") || detail.startsWith("Unexpected YouTube audio content type "))) {
                    showStatus("VOT: " + detail, "#ff5252", 5000);
                } else {
                    showStatus("VOT: ошибка соединения", "#ff5252", 4500);
                }
            }
        }
    }

    private static boolean isCurrent(int generation, String videoId) {
        return sEnabled && generation == sGeneration && videoId.equals(sVideoId);
    }

    private static String waitForSourceAudioUrl(int generation, String videoId,
                                                String initialAudioUrl) throws InterruptedException {
        String url = initialAudioUrl;
        long deadline = System.currentTimeMillis() + 20000L;
        while ((url == null || url.isEmpty()) && System.currentTimeMillis() < deadline
                && isCurrent(generation, videoId)) {
            Thread.sleep(500L);
            url = sSourceAudioUrl;
        }
        return url;
    }

    private static void sleepInterruptibly(int generation, String videoId, long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end && isCurrent(generation, videoId)) {
            Thread.sleep(Math.max(1L, Math.min(500L, end - System.currentTimeMillis())));
        }
    }

    private static TranslationResponse translate(String youtubeUrl, double durationSec) throws Exception {
        Session session = ensureSession();
        WireWriter w = new WireWriter();
        w.writeString(3, youtubeUrl);
        w.writeBool(5, true);
        w.writeDouble(6, durationSec);
        w.writeInt32(7, 1);
        w.writeString(8, "en");
        w.writeString(14, "ru");
        w.writeInt32(15, 1);
        w.writeInt32(16, 2);
        w.writeBool(18, sLivelyVoice && sOAuthToken != null && !sOAuthToken.isEmpty());
        byte[] body = w.toByteArray();

        String path = "/video-translation/translate";
        String token = session.uuid + ":" + path + ":" + VERSION;
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Vtrans-Signature", sign(body));
        headers.put("Sec-Vtrans-Sk", session.secretKey);
        headers.put("Sec-Vtrans-Token", sign(token.getBytes(StandardCharsets.UTF_8)) + ":" + token);
        if (sLivelyVoice && sOAuthToken != null && !sOAuthToken.isEmpty()) {
            headers.put("Authorization", "OAuth " + sOAuthToken);
        }
        return parseTranslation(post(path, body, headers));
    }

    private static synchronized Session ensureSession() throws Exception {
        long now = System.currentTimeMillis();
        if (sSession != null && now - sSession.createdAtMs < sSession.expiresSec * 1000L) return sSession;
        String uuid = randomToken();
        WireWriter w = new WireWriter();
        w.writeString(1, uuid);
        w.writeString(2, "video-translation");
        byte[] body = w.toByteArray();
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Vtrans-Signature", sign(body));
        headers.put("Sec-Vtrans-Token", randomToken());
        byte[] raw = post("/session/create", body, headers);
        Map<Integer, Object> fields = parseFields(raw);
        Session session = new Session();
        session.uuid = uuid;
        session.secretKey = asString(fields.get(1));
        session.expiresSec = asInt(fields.get(2), 3600);
        session.createdAtMs = now;
        if (session.secretKey == null || session.secretKey.isEmpty()) throw new IOException("Empty VOT session key");
        sSession = session;
        return session;
    }

    private static byte[] post(String path, byte[] body, Map<String, String> extraHeaders) throws IOException {
        return request("POST", path, body, extraHeaders);
    }

    private static byte[] put(String path, byte[] body, Map<String, String> extraHeaders) throws IOException {
        return request("PUT", path, body, extraHeaders);
    }

    private static void reportMissingSourceAudio(String videoUrl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(HOST + "/video-translation/fail-audio-js")
                .openConnection(Proxy.NO_PROXY);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(25000);
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        String json = "{\"video_url\":\"" + videoUrl + "\"}";
        try {
            OutputStream out = conn.getOutputStream();
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.close();
            int code = conn.getResponseCode();
            byte[] raw = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            if (code < 200 || code >= 300 ||
                    new org.json.JSONObject(new String(raw, StandardCharsets.UTF_8)).optInt("status", -1) != 1) {
                throw new IOException("VOT fail-audio HTTP " + code);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] request(String method, String path, byte[] body,
                                  Map<String, String> extraHeaders) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(HOST + path).openConnection(Proxy.NO_PROXY);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(25000);
        conn.setRequestMethod(method);
        conn.setDoOutput(true);
        conn.setRequestProperty("Accept", "application/x-protobuf");
        conn.setRequestProperty("Accept-Language", "en");
        conn.setRequestProperty("Content-Type", "application/x-protobuf");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Pragma", "no-cache");
        conn.setRequestProperty("Cache-Control", "no-cache");
        for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
            conn.setRequestProperty(entry.getKey(), entry.getValue());
        }
        OutputStream out = conn.getOutputStream();
        out.write(body);
        out.flush();
        out.close();
        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        byte[] data = readAll(in);
        conn.disconnect();
        if (code < 200 || code >= 300) throw new IOException("VOT HTTP " + code);
        return data;
    }

    private static void uploadSourceAudio(int generation, String videoId, String youtubeUrl,
                                          String translationId, String sourceUrl) throws Exception {
        Session session = ensureSession();
        String fileId = "random-web_abr-" + UUID.randomUUID().toString();
        showStatus("VOT: загрузка аудио 0%", "#ffab40", 4000);
        RangeData first = downloadAudioRange(sourceUrl, 0, AUDIO_CHUNK_SIZE - 1L);
        if (first.data.length <= 0) throw new IOException("Empty YouTube audio stream");
        long totalLength = first.totalLength;
        if ((totalLength > 0 && totalLength <= first.data.length) ||
                (totalLength <= 0 && first.data.length < AUDIO_CHUNK_SIZE)) {
            AudioResponse response = uploadAudioBody(session,
                    encodeAudioSingle(youtubeUrl, translationId, fileId, first.data));
            validateFinalAudioResponse(response);
            showStatus("VOT: загрузка аудио 100%", "#ffab40", 4000);
            return;
        }
        int totalParts = totalLength > 0
                ? (int) ((totalLength + AUDIO_CHUNK_SIZE - 1L) / AUDIO_CHUNK_SIZE) : 0;
        int chunkIndex = 0;
        long offset = 0;
        RangeData current = first;
        while (isCurrent(generation, videoId)) {
            boolean terminal = totalParts > 0
                    ? chunkIndex == totalParts - 1
                    : current.data.length < AUDIO_CHUNK_SIZE;
            AudioResponse response = uploadAudioBody(session,
                    encodeAudioChunk(youtubeUrl, translationId, fileId, chunkIndex,
                            terminal ? chunkIndex + 1 : 0, current.data));
            offset += current.data.length;
            if (terminal) {
                validateFinalAudioResponse(response);
                showStatus("VOT: загрузка аудио 100%", "#ffab40", 4000);
                return;
            }
            validateIntermediateAudioResponse(response);
            chunkIndex++;
            if (totalLength > 0) {
                int percent = (int) Math.min(99L, offset * 100L / totalLength);
                showStatus("VOT: загрузка аудио " + percent + "%", "#ffab40", 4000);
            } else {
                showStatus("VOT: загрузка аудио, часть " + (chunkIndex + 1), "#ffab40", 4000);
            }
            current = downloadAudioRange(sourceUrl, offset, offset + AUDIO_CHUNK_SIZE - 1L);
            if (totalLength <= 0 && current.totalLength > 0) {
                totalLength = current.totalLength;
                totalParts = (int) ((totalLength + AUDIO_CHUNK_SIZE - 1L) / AUDIO_CHUNK_SIZE);
            }
            if (current.data.length <= 0) throw new IOException("Premature YouTube audio EOF at " + offset);
        }
        throw new IOException("Audio upload cancelled");
    }

    private static RangeData downloadAudioRange(String sourceUrl, long start, long end) throws Exception {
        HttpURLConnection source = (HttpURLConnection) new URL(sourceUrl).openConnection(Proxy.NO_PROXY);
        source.setConnectTimeout(20000);
        source.setReadTimeout(60000);
        source.setRequestProperty("User-Agent", USER_AGENT);
        source.setRequestProperty("Accept", "*/*");
        source.setRequestProperty("Connection", "keep-alive");
        source.setRequestProperty("Range", "bytes=" + start + "-" + end);
        int code = source.getResponseCode();
        if (code != 200 && code != 206) {
            source.disconnect();
            throw new IOException("YouTube audio HTTP " + code);
        }
        if (start > 0 && code == 200) {
            source.disconnect();
            throw new IOException("YouTube audio server ignored range request");
        }
        String type = source.getContentType();
        if (type != null && (type.contains("text/html") || type.contains("text/plain"))) {
            source.disconnect();
            throw new IOException("Unexpected YouTube audio content type " + type);
        }
        long totalLength = -1;
        String range = source.getHeaderField("Content-Range");
        if (range != null) {
            int slash = range.lastIndexOf('/');
            if (slash >= 0) try { totalLength = Long.parseLong(range.substring(slash + 1).trim()); }
            catch (NumberFormatException ignored) {}
        }
        if (totalLength <= 0 && code == 200) totalLength = source.getContentLengthLong();
        InputStream in = source.getInputStream();
        byte[] buffer = new byte[AUDIO_CHUNK_SIZE];
        try {
            int count = readBlock(in, buffer, 0, buffer.length);
            Log.i(TAG, "YouTube audio range " + start + "-" + end + " HTTP " + code +
                    " bytes=" + count + " total=" + totalLength);
            return new RangeData(copyOf(buffer, count), totalLength);
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
            source.disconnect();
        }
    }

    private static AudioResponse uploadAudioBody(Session session, byte[] body) throws Exception {
        String path = "/video-translation/audio";
        String token = session.uuid + ":" + path + ":" + VERSION;
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Vtrans-Signature", sign(body));
        headers.put("Sec-Vtrans-Sk", session.secretKey);
        headers.put("Sec-Vtrans-Token", sign(token.getBytes(StandardCharsets.UTF_8)) + ":" + token);
        if (sLivelyVoice && sOAuthToken != null && !sOAuthToken.isEmpty()) {
            headers.put("Authorization", "OAuth " + sOAuthToken);
        }
        return parseAudioResponse(put(path, body, headers));
    }

    private static byte[] encodeAudioSingle(String url, String translationId,
                                            String fileId, byte[] audio) {
        WireWriter info = new WireWriter();
        info.writeString(1, fileId);
        info.writeBytes(2, audio);
        WireWriter root = new WireWriter();
        root.writeString(1, translationId);
        root.writeString(2, url);
        root.writeEmbedded(6, info.toByteArray());
        return root.toByteArray();
    }

    private static byte[] encodeAudioChunk(String url, String translationId, String fileId,
                                           int chunkId, int totalParts, byte[] audio) {
        WireWriter audioBuffer = new WireWriter();
        audioBuffer.writeInt32IncludeZero(1, chunkId);
        audioBuffer.writeBytes(2, audio);
        WireWriter partial = new WireWriter();
        partial.writeEmbedded(1, audioBuffer.toByteArray());
        if (totalParts > 0) partial.writeInt32(2, totalParts);
        partial.writeString(3, fileId);
        partial.writeInt32(4, 1);
        WireWriter root = new WireWriter();
        root.writeString(1, translationId);
        root.writeString(2, url);
        root.writeEmbedded(4, partial.toByteArray());
        return root.toByteArray();
    }

    private static int readBlock(InputStream in, byte[] buffer, int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int read = in.read(buffer, offset + total, length - total);
            if (read < 0) break;
            if (read == 0) continue;
            total += read;
        }
        return total;
    }

    private static byte[] copyOf(byte[] source, int length) {
        byte[] result = new byte[length];
        System.arraycopy(source, 0, result, 0, length);
        return result;
    }

    private static AudioResponse parseAudioResponse(byte[] data) {
        AudioResponse response = new AudioResponse();
        if (data == null) return response;
        int pos = 0;
        while (pos < data.length) {
            long[] tag = readVarint(data, pos);
            if (tag[0] <= 0 || tag[1] <= pos) break;
            pos = (int) tag[1];
            int field = (int) tag[0] >>> 3;
            int wire = (int) tag[0] & 7;
            if (wire == 0) {
                long[] value = readVarint(data, pos);
                if (value[0] < 0) break;
                if (field == 1) response.status = (int) value[0];
                pos = (int) value[1];
            } else if (wire == 1) pos += 8;
            else if (wire == 2) {
                long[] length = readVarint(data, pos);
                if (length[0] < 0) break;
                pos = (int) length[1] + (int) length[0];
                if (field == 2) response.remainingChunks++;
            } else if (wire == 5) pos += 4;
            else break;
        }
        return response;
    }

    private static void validateIntermediateAudioResponse(AudioResponse response) throws IOException {
        if (response.status != 1 && response.status != 2) {
            throw new IOException("Unexpected audio upload status " + response.status);
        }
    }

    private static void validateFinalAudioResponse(AudioResponse response) throws IOException {
        if (response.status != 2 || response.remainingChunks != 0) {
            throw new IOException("Incomplete audio upload status=" + response.status +
                    " remaining=" + response.remainingChunks);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        in.close();
        return out.toByteArray();
    }

    private static void preparePlayer(final int generation, final String videoId, final String audioUrl) {
        MAIN.post(new Runnable() {
            @Override public void run() {
                if (!isCurrent(generation, videoId) || sContext == null) return;
                releasePlayer(false);
                try {
                    final MediaPlayer player = new MediaPlayer();
                    sPlayer = player;
                    sAudioVideoId = videoId;
                    sPrepared = false;
                    player.setAudioStreamType(AudioManager.STREAM_MUSIC);
                    player.setDataSource(audioUrl);
                    player.setVolume(sTranslationVolume, sTranslationVolume);
                    player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        @Override public void onPrepared(MediaPlayer mp) {
                            if (!isCurrent(generation, videoId)) { releasePlayer(true); return; }
                            sPrepared = true;
                            setOriginalAudioActive(true);
                            syncPlayer(true);
                            showStatus("VOT: перевод включён", "#00e676", 3000);
                        }
                    });
                    player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                        @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                            Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                            showStatus("VOT: ошибка аудиодорожки", "#ff5252", 4000);
                            releasePlayer(true);
                            return true;
                        }
                    });
                    player.prepareAsync();
                } catch (Throwable t) {
                    Log.e(TAG, "Unable to prepare translated audio", t);
                    showStatus("VOT: не удалось открыть аудиодорожку", "#ff5252", 4500);
                    releasePlayer(true);
                }
            }
        });
    }

    private static void syncPlayer(final boolean forceSeek) {
        MAIN.post(new Runnable() {
            @Override public void run() {
                MediaPlayer player = sPlayer;
                if (!sPrepared || player == null) return;
                try {
                    long targetMs = (long) (sPositionSec * 1000.0);
                    long drift = Math.abs(player.getCurrentPosition() - targetMs);
                    if (forceSeek || drift > 1200) player.seekTo((int) Math.min(Integer.MAX_VALUE, targetMs));
                    try { player.setPlaybackParams(new PlaybackParams().setSpeed(sSpeed)); } catch (Throwable ignored) {}
                    if (sPaused) {
                        if (player.isPlaying()) player.pause();
                    } else if (!player.isPlaying()) {
                        player.start();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "sync failed: " + t.getClass().getSimpleName());
                }
            }
        });
    }

    private static void releasePlayer(final boolean restoreOriginal) {
        final MediaPlayer player = sPlayer;
        sPlayer = null;
        sPrepared = false;
        sAudioVideoId = null;
        Runnable release = new Runnable() {
            @Override public void run() {
                if (player != null) {
                    try { player.stop(); } catch (Throwable ignored) {}
                    try { player.release(); } catch (Throwable ignored) {}
                }
                if (restoreOriginal && !sPrepared) setOriginalAudioActive(false);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) release.run();
        else MAIN.post(release);
    }

    private static void setOriginalAudioActive(boolean active) {
        ProxyHelper.evaluateJs("if(window.__yttv_vot_set_active)window.__yttv_vot_set_active(" +
                active + "," + Math.round(sOriginalVolume * 100f) + ");");
    }

    private static void showStatus(String message, String color, int durationMs) {
        String safe = message.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
        ProxyHelper.evaluateJs("if(window.__yttv_vot_status)window.__yttv_vot_status('" + safe + "','" + color + "'," + durationMs + ");");
        Log.i(TAG, message);
    }

    private static void clearStatus() {
        ProxyHelper.evaluateJs("if(window.__yttv_vot_clear_status)window.__yttv_vot_clear_status();");
    }

    private static TranslationResponse parseTranslation(byte[] data) {
        Map<Integer, Object> fields = parseFields(data);
        TranslationResponse response = new TranslationResponse();
        response.url = asString(fields.get(1));
        response.status = asInt(fields.get(4), 0);
        response.remainingTimeSec = asInt(fields.get(5), 0);
        response.translationId = asString(fields.get(7));
        response.message = asString(fields.get(9));
        return response;
    }

    private static Map<Integer, Object> parseFields(byte[] data) {
        Map<Integer, Object> result = new HashMap<Integer, Object>();
        if (data == null) return result;
        int pos = 0;
        while (pos < data.length) {
            long[] tag = readVarint(data, pos);
            if (tag[0] <= 0 || tag[1] <= pos) break;
            pos = (int) tag[1];
            int field = (int) tag[0] >>> 3;
            int wire = (int) tag[0] & 7;
            if (wire == 0) {
                long[] value = readVarint(data, pos);
                if (value[0] < 0) break;
                result.put(field, (int) value[0]);
                pos = (int) value[1];
            } else if (wire == 1) {
                pos += 8;
            } else if (wire == 2) {
                long[] length = readVarint(data, pos);
                if (length[0] < 0) break;
                pos = (int) length[1];
                int len = (int) length[0];
                if (len < 0 || pos + len > data.length) break;
                byte[] chunk = new byte[len];
                System.arraycopy(data, pos, chunk, 0, len);
                if (looksLikeText(chunk)) result.put(field, new String(chunk, StandardCharsets.UTF_8));
                pos += len;
            } else if (wire == 5) {
                pos += 4;
            } else {
                break;
            }
        }
        return result;
    }

    private static boolean looksLikeText(byte[] value) {
        for (byte b : value) if ((b & 0xff) < 0x09) return false;
        return true;
    }

    private static long[] readVarint(byte[] data, int start) {
        long value = 0;
        int shift = 0;
        int pos = start;
        while (pos < data.length && shift < 64) {
            int b = data[pos++] & 0xff;
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return new long[]{value, pos};
            shift += 7;
        }
        return new long[]{-1, pos};
    }

    private static String asString(Object value) { return value instanceof String ? (String) value : null; }
    private static int asInt(Object value, int fallback) { return value instanceof Integer ? (Integer) value : fallback; }

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HMAC_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(body);
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    private static String randomToken() {
        String chars = "0123456789ABCDEF";
        StringBuilder out = new StringBuilder(32);
        for (int i = 0; i < 32; i++) out.append(chars.charAt((int) (Math.random() * chars.length())));
        return out.toString();
    }

    private static final class Session {
        String uuid;
        String secretKey;
        int expiresSec;
        long createdAtMs;
    }

    private static final class TranslationResponse {
        String url;
        int status;
        int remainingTimeSec;
        String translationId;
        String message;
    }

    private static final class AudioResponse {
        int status;
        int remainingChunks;
    }

    private static final class RangeData {
        final byte[] data;
        final long totalLength;
        RangeData(byte[] data, long totalLength) {
            this.data = data;
            this.totalLength = totalLength;
        }
    }

    private static final class WireWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] toByteArray() { return out.toByteArray(); }
        void writeString(int field, String value) {
            if (value == null || value.isEmpty()) return;
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            writeTag(field, 2); writeVarint(bytes.length); out.write(bytes, 0, bytes.length);
        }
        void writeInt32(int field, int value) { if (value != 0) { writeTag(field, 0); writeVarint(value); } }
        void writeInt32IncludeZero(int field, int value) { writeTag(field, 0); writeVarint(value); }
        void writeBool(int field, boolean value) { if (value) { writeTag(field, 0); writeVarint(1); } }
        void writeBytes(int field, byte[] value) {
            if (value == null) return;
            writeTag(field, 2); writeVarint(value.length); out.write(value, 0, value.length);
        }
        void writeEmbedded(int field, byte[] value) { writeBytes(field, value); }
        void writeDouble(int field, double value) {
            if (value == 0) return;
            writeTag(field, 1);
            long bits = Double.doubleToLongBits(value);
            for (int i = 0; i < 8; i++) out.write((int) (bits >> (i * 8)) & 0xff);
        }
        void writeTag(int field, int wire) { writeVarint((field << 3) | wire); }
        void writeVarint(int value) {
            while ((value & ~0x7f) != 0) { out.write((value & 0x7f) | 0x80); value >>>= 7; }
            out.write(value);
        }
    }
}
