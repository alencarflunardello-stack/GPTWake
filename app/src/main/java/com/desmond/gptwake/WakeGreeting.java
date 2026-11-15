package com.desmond.gptwake;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.media.AudioAttributes;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/** A short, local, varied spoken acknowledgment after wake detection.
 * The microphone is already released by WakeController when speak() is called.
 * This does NOT use or impersonate the selected ChatGPT voice.
 */
final class WakeGreeting {
    private static final String[] REPLIES = {
            "Oi, tô aqui. O que precisa?",
            "Pode falar, estou ouvindo.",
            "Chamou? Me conta.",
            "Estou aqui. Como posso ajudar?",
            "Pronto, pode falar.",
            "Oi! Diga, o que você precisa?",
            "Tô ouvindo. Pode mandar."
    };
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private volatile boolean available;
    private volatile boolean closed;

    WakeGreeting(Context context) {
        this.context = context.getApplicationContext();
        main.post(this::initialize);
    }

    private void initialize() {
        if (closed) return;
        try {
            tts = new TextToSpeech(context, result -> {
                if (result != TextToSpeech.SUCCESS || tts == null || closed) {
                    available = false;
                    L.i("CHARLES_GREETING_TTS_INIT_FAIL code=" + result);
                    return;
                }
                int language = tts.setLanguage(new Locale("pt", "BR"));
                available = language != TextToSpeech.LANG_MISSING_DATA
                        && language != TextToSpeech.LANG_NOT_SUPPORTED;
                tts.setSpeechRate(1.05f);
                tts.setPitch(0.96f);
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                L.i("CHARLES_GREETING_TTS_READY available=" + available +
                        " languageResult=" + language);
            });
        } catch (Throwable t) {
            available = false;
            L.e("CHARLES_GREETING_TTS_INIT_EXCEPTION", t);
        }
    }

    private String chooseReply() {
        android.content.SharedPreferences p = context.getSharedPreferences(
                "charles_greetings", Context.MODE_PRIVATE);
        int previous = p.getInt("last", -1);
        int index = ThreadLocalRandom.current().nextInt(REPLIES.length - 1);
        if (previous >= 0 && index >= previous) index++;
        p.edit().putInt("last", index).apply();
        return REPLIES[index];
    }

    void speak(Runnable onFinished) {
        main.post(() -> speakWhenReady(onFinished, 0));
    }

    private void speakWhenReady(Runnable onFinished, int tries) {
        if (closed) { onFinished.run(); return; }
        if (!available || tts == null) {
            if (tries < 4) {
                main.postDelayed(() -> speakWhenReady(onFinished, tries + 1), 250);
                return;
            }
            L.i("CHARLES_GREETING_SKIPPED reason=tts_unavailable");
            onFinished.run();
            return;
        }

        String phrase = chooseReply();
        String utteranceId = "charles-greeting-" + System.nanoTime();
        AtomicBoolean finished = new AtomicBoolean();
        Runnable done = () -> {
            if (finished.compareAndSet(false, true)) {
                tts.setOnUtteranceProgressListener(null);
                L.i("CHARLES_GREETING_DONE id=" + utteranceId);
                onFinished.run();
            }
        };
        Runnable timeout = () -> {
            if (!finished.get()) {
                L.i("CHARLES_GREETING_TIMEOUT id=" + utteranceId);
                try { tts.stop(); } catch (Throwable ignored) {}
                done.run();
            }
        };
        try {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {
                    if (utteranceId.equals(id)) L.i("CHARLES_GREETING_START id=" + id);
                }
                @Override public void onDone(String id) {
                    if (utteranceId.equals(id)) main.post(() -> {
                        main.removeCallbacks(timeout); done.run();
                    });
                }
                @Override public void onError(String id) {
                    if (utteranceId.equals(id)) main.post(() -> {
                        L.i("CHARLES_GREETING_TTS_ERROR id=" + id);
                        main.removeCallbacks(timeout); done.run();
                    });
                }
            });
            int response = tts.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
            if (response != TextToSpeech.SUCCESS) {
                L.i("CHARLES_GREETING_SPEAK_FAIL status=" + response);
                done.run();
            } else {
                L.i("CHARLES_GREETING_QUEUED index=" +
                        context.getSharedPreferences("charles_greetings",
                            Context.MODE_PRIVATE).getInt("last", -1));
                main.postDelayed(timeout, 5000);
            }
        } catch (Throwable t) {
            L.e("CHARLES_GREETING_EXCEPTION", t);
            done.run();
        }
    }

    void close() {
        closed = true;
        main.post(() -> {
            available = false;
            TextToSpeech old = tts;
            tts = null;
            if (old != null) {
                try { old.stop(); old.shutdown(); } catch (Throwable ignored) {}
            }
        });
    }
}
