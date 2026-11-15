package com.desmond.gptwake;

import android.content.Context;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

/** Local, Portuguese speech trigger. Only the exact word Charles wakes the assistant.
 * Microphone audio stays in the Android process and is never logged or uploaded. */
final class VoskPtWakeEngine implements AutoCloseable {
    private static final String GRAMMAR = "[\"charles\",\"[unk]\"]";
    private final Model model;
    private Recognizer recognizer;
    private String previousPartial = "";
    private int matchingFrames = 0;

    static VoskPtWakeEngine load(Context context) throws Exception {
        long t = System.currentTimeMillis();
        VoskPtWakeEngine engine = new VoskPtWakeEngine(VoskPtModel.open(context));
        L.i("VOSK_PT_LIVE_MODEL_READY elapsedMs=" + (System.currentTimeMillis() - t));
        return engine;
    }
    private VoskPtWakeEngine(Model model) {
        this.model = model;
    }
    synchronized void newStream() throws Exception {
        releaseStream();
        recognizer = new Recognizer(model, 16000f, GRAMMAR);
        L.i("VOSK_PT_LIVE_STREAM_NEW grammar=charles_or_unknown");
    }
    synchronized String accept(float[] normalized, int sampleRate) {
        if (recognizer == null || sampleRate != 16000) return null;
        float[] pcm16 = new float[normalized.length];
        for (int i = 0; i < normalized.length; i++) pcm16[i] = normalized[i] * 32768f;
        try {
            boolean endpoint = recognizer.acceptWaveForm(pcm16, pcm16.length);
            String result = new JSONObject(endpoint
                    ? recognizer.getResult() : recognizer.getPartialResult())
                    .optString(endpoint ? "text" : "partial", "").trim();
            if (endpoint) {
                matchingFrames = 0;
                previousPartial = "";
                if ("charles".equalsIgnoreCase(result)) {
                    L.i("VOSK_PT_FINAL_HIT keyword=Charles");
                    return "Charles";
                }
                return null;
            }
            if ("charles".equalsIgnoreCase(result)) {
                matchingFrames = "charles".equals(previousPartial) ? matchingFrames + 1 : 1;
                previousPartial = "charles";
                // Two successive 80 ms chunks reduce one-frame partial hallucinations.
                if (matchingFrames >= 2) {
                    L.i("VOSK_PT_PARTIAL_HIT keyword=Charles stableFrames=" + matchingFrames);
                    matchingFrames = 0;
                    return "Charles";
                }
            } else {
                matchingFrames = 0;
                previousPartial = result;
            }
            return null;
        } catch (Exception e) {
            L.e("VOSK_PT_LIVE_RECOGNIZER_FAIL", e);
            return null;
        }
    }
    synchronized void releaseStream() {
        Recognizer r = recognizer;
        recognizer = null;
        matchingFrames = 0;
        previousPartial = "";
        if (r != null) r.close();
    }
    @Override
    public synchronized void close() {
        releaseStream();
        model.close();
    }
}
