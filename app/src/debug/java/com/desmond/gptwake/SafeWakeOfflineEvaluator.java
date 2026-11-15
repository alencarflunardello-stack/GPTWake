package com.desmond.gptwake;

import android.content.Context;
import java.io.File;
import java.util.Locale;
import java.util.Random;

/** Local-only verification: four user-supplied calls and deterministic negative controls.
 * No recordings are uploaded, exported, or logged.
 */
final class SafeWakeOfflineEvaluator {
    private static final int FRAME = 1280;
    private SafeWakeOfflineEvaluator() {}
    static void run(Context ctx) {
        try {
            File file = new File(ctx.getExternalFilesDir(null), "Charles.m4a");
            if (!file.isFile() || !file.canRead()) {
                L.i("SAFE_OFFLINE_FILE_UNAVAILABLE");
                return;
            }
            float[] positive = OfflineWakeEvaluator.decodeTo16k(file);
            try (VoskPtWakeEngine engine = VoskPtWakeEngine.load(ctx)) {
                verify(engine, "owner_four_calls", positive);
                verify(engine, "silence", new float[160000]);
                float[] pinkishNoise = new float[160000];
                Random random = new Random(1306L);
                float low = 0;
                for (int i = 0; i < pinkishNoise.length; i++) {
                    low = 0.95f * low + 0.05f * (float) random.nextGaussian();
                    pinkishNoise[i] = Math.max(-1f, Math.min(1f, low * 0.12f));
                }
                verify(engine, "filtered_noise", pinkishNoise);
                float[] risingTones = new float[160000];
                for (int i = 0; i < risingTones.length; i++) {
                    double t = (double) i / 16000.0;
                    risingTones[i] = (float) (0.04 * Math.sin(2 * Math.PI * (180 + 50 * t) * t));
                }
                verify(engine, "rising_tone", risingTones);
                L.i("SAFE_OFFLINE_DONE");
            }
        } catch (Throwable e) {
            L.e("SAFE_OFFLINE_FAILED", e);
        }
    }
    private static void verify(VoskPtWakeEngine engine, String label, float[] audio) throws Exception {
        engine.newStream();
        int hits = 0;
        StringBuilder times = new StringBuilder();
        for (int offset = 0; offset < audio.length; offset += FRAME) {
            float[] frame = new float[FRAME];
            System.arraycopy(audio, offset, frame, 0, Math.min(FRAME, audio.length - offset));
            String hit = engine.accept(frame, 16000);
            if (hit != null) {
                hits++;
                if (times.length() > 0) times.append(",");
                times.append(String.format(Locale.US, "%.2f", (offset + FRAME)/16000.0));
            }
        }
        for (int i = 0; i < 18; i++) {
            String hit = engine.accept(new float[FRAME], 16000);
            if (hit != null) hits++;
        }
        L.i("SAFE_OFFLINE_RESULT label=" + label + " hits=" + hits
                + " durationSec=" + String.format(Locale.US,"%.2f",audio.length / 16000.0)
                + " hitsAtSec=" + times);
        engine.releaseStream();
    }
}
