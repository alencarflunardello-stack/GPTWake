package com.desmond.gptwake;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.util.Locale;

/**
 * Conservative offline Portuguese "Charles" trigger.
 *
 * The previous constrained grammar treated unrelated speech as "charles",
 * and opened ChatGPT after just two partial frames (~160 ms).
 * This revision intentionally sacrifices some recall: uses the FULL PT-BR
 * recognizer, requires a COMPLETE speech segment, exact one-word result,
 * and a minimum independent word confidence. Never acts on partial text.
 * No microphone audio or unrelated transcriptions are stored/logged.
 */
final class VoskPtWakeEngine implements AutoCloseable {
    private static final double MIN_WORD_CONFIDENCE = 0.68;
    private final Model model;
    private Recognizer recognizer;

    static VoskPtWakeEngine load(Context context) throws Exception {
        long t = System.currentTimeMillis();
        VoskPtWakeEngine engine = new VoskPtWakeEngine(VoskPtModel.open(context));
        L.i("VOSK_PT_SAFE_MODEL_READY elapsedMs=" + (System.currentTimeMillis() - t));
        return engine;
    }

    private VoskPtWakeEngine(Model model) {
        this.model = model;
    }

    synchronized void newStream() throws Exception {
        releaseStream();
        // Do NOT use ["charles","[unk]"]; that grammar forced a false
        // "Charles" decision for many unrelated sounds.
        recognizer = new Recognizer(model, 16000f);
        recognizer.setWords(true);
        L.i("VOSK_PT_SAFE_STREAM_NEW grammar=unrestricted decision=final_only threshold=0.68");
    }

    synchronized String accept(float[] normalized, int sampleRate) {
        if (recognizer == null || sampleRate != 16000) return null;
        float[] pcm16 = new float[normalized.length];
        for (int i = 0; i < normalized.length; i++) pcm16[i] = normalized[i] * 32768f;
        try {
            if (!recognizer.acceptWaveForm(pcm16, pcm16.length)) {
                // Crucial: NEVER launch from a partial transcript.
                return null;
            }
            JSONObject finalResult = new JSONObject(recognizer.getResult());
            String words = finalResult.optString("text", "").trim().toLowerCase(Locale.ROOT);
            if (!"charles".equals(words)) return null;

            JSONArray details = finalResult.optJSONArray("result");
            if (details == null || details.length() != 1) {
                L.i("VOSK_PT_SAFE_REJECT reason=no_single_word_confidence");
                return null;
            }
            JSONObject word = details.optJSONObject(0);
            double confidence = word == null ? 0 : word.optDouble("conf", 0);
            if (!Double.isFinite(confidence) || confidence < MIN_WORD_CONFIDENCE) {
                L.i("VOSK_PT_SAFE_REJECT reason=low_confidence conf=" +
                        String.format(Locale.US, "%.2f", confidence));
                return null;
            }
            L.i("VOSK_PT_SAFE_HIT keyword=Charles conf=" +
                    String.format(Locale.US, "%.2f", confidence));
            return "Charles";
        } catch (Exception e) {
            L.e("VOSK_PT_SAFE_RECOGNIZER_FAIL", e);
            return null;
        }
    }

    synchronized void releaseStream() {
        Recognizer r = recognizer;
        recognizer = null;
        if (r != null) r.close();
    }

    @Override
    public synchronized void close() {
        releaseStream();
        model.close();
    }
}
