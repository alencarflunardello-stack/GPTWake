package com.desmond.gptwake;

import android.content.Context;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.json.JSONObject;
import java.io.File;
import java.util.Locale;

/** Offline-only user-owned recording diagnostic; no recording is uploaded or logged. */
final class VoskPtOfflineEvaluator {
    private static final int FRAME = 1280; // 80 ms at 16 kHz
    private static final String[] GRAMMARS = {
        "",
        "[\"charles\",\"[unk]\"]",
        "[\"charles\",\"charli\",\"charlie\",\"carlos\",\"[unk]\"]",
    };    private VoskPtOfflineEvaluator() {}
    static void run(Context ctx) {
        long start = System.currentTimeMillis();
        File recording = new File(ctx.getExternalFilesDir(null), "Charles.m4a");
        try {
            if (!recording.isFile() || !recording.canRead()) {
                L.i("VOSK_PT_RECORDING_NOT_READABLE"); return;
            }
            float[] audio = OfflineWakeEvaluator.decodeTo16k(recording);
            L.i("VOSK_PT_EVAL_START durationSec=" +
                    String.format(Locale.US, "%.2f", audio.length / 16000.0));
            try (Model model = VoskPtModel.open(ctx)) {
                for (int mode = 0; mode < GRAMMARS.length; mode++) {
                    String grammar = GRAMMARS[mode];
                    int finals = 0;
                    int partials = 0;
                    String prevPartial = "";
                    StringBuilder finalTexts = new StringBuilder();
                    StringBuilder partialTexts = new StringBuilder();
                    try (Recognizer rec = grammar.isEmpty()
                            ? new Recognizer(model, 16000f)
                            : new Recognizer(model, 16000f, grammar)) {
                        for (int offset = 0; offset < audio.length; offset += FRAME) {
                            int n = Math.min(FRAME, audio.length - offset);
                            float[] buf = new float[n];
                            // Vosk float API expects the numerical scale of PCM16
                            // (-32768..32767), not [-1..1] normalized waveform.
                            for (int j = 0; j < n; j++) buf[j] = audio[offset + j] * 32768f;
                            if (rec.acceptWaveForm(buf, n)) {
                                String text = new JSONObject(rec.getResult()).optString("text", "").trim();
                                if (!text.isEmpty()) {
                                    finals++;
                                    if (finalTexts.length() > 0) finalTexts.append(" | ");
                                    finalTexts.append(String.format(Locale.US, "%.2f:", (offset + n)/16000.0)).append(text);
                                }
                                prevPartial = "";
                            } else if ((offset/FRAME) % 3 == 0) {
                                String partial = new JSONObject(rec.getPartialResult()).optString("partial", "").trim();
                                if (!partial.isEmpty() && !partial.equals(prevPartial)) {
                                    prevPartial = partial;
                                    partials++;
                                    if (partialTexts.length() > 0) partialTexts.append(" | ");
                                    partialTexts.append(String.format(Locale.US, "%.2f:", (offset+n)/16000.0)).append(partial);
                                }
                            }
                        }
                        String tail = new JSONObject(rec.getFinalResult()).optString("text", "").trim();
                        if (!tail.isEmpty()) {
                            finals++;
                            if (finalTexts.length()>0) finalTexts.append(" | ");
                            finalTexts.append("END:").append(tail);
                        }
                    }
                    L.i("VOSK_PT_RESULT mode=" + mode + " finals=" + finals +
                            " partials=" + partials + " finalTexts=[" + finalTexts +
                            "] partialTexts=[" + partialTexts + "]");
                }
            }
            L.i("VOSK_PT_EVAL_DONE elapsedMs=" + (System.currentTimeMillis() - start));
        } catch (Throwable error) {
            L.e("VOSK_PT_EVAL_FAILED", error);
        }
    }
}
