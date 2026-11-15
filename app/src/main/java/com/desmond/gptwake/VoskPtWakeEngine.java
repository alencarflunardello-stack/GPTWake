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
    static volatile double minWordConfidence = 0.55;
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
        // Calibrated Portuguese grammar. Competing common lookalike words
        // prevent forcing unrelated speech into "charles" (unlike the unsafe
        // two-token [charles,unk] grammar). Only FIRST word in a FINAL segment
        // with sufficient word confidence can trigger the assistant.
        recognizer = new Recognizer(model, 16000f, "[\"charles\",\"eles\",\"chaves\",\"alice\",\"tchau\",\"[unk]\"]");
        recognizer.setWords(true);
        L.i("VOSK_PT_SAFE_STREAM_NEW grammar=calibrated_competitors decision=final_only threshold=0.55 first_word");
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
            // Wake phrase may be followed by an immediate request, e.g.
            // "Charles, envia oi para o Higor". Only the FIRST word counts.
            // Do not trigger if "Charles" appears later in someone else's speech.
            if (words.isEmpty()) return null;
            String first = words.split("\\s+", 2)[0];
            if (!"charles".equals(first)) return null;

            JSONArray details = finalResult.optJSONArray("result");
            if (details == null || details.length() < 1) {
                L.i("VOSK_PT_SAFE_REJECT reason=no_first_word_confidence");
                return null;
            }
            JSONObject word = details.optJSONObject(0);
            if (word == null || !"charles".equalsIgnoreCase(word.optString("word", ""))) {
                L.i("VOSK_PT_SAFE_REJECT reason=first_word_mismatch");
                return null;
            }
            double confidence = word.optDouble("conf", 0);
            if (!Double.isFinite(confidence) || confidence < minWordConfidence) {
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
