package com.desmond.gptwake;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Local-only diagnostic for the owner's own "Charles" recording. Runs offline
 * on-device; no audio is committed, sent over the network or retained in logs.
 * Requires the normal microphone service to be stopped during benchmarking.
 */
public final class OfflineWakeEvaluator {
    private static final int TARGET_SAMPLE_RATE = 16000;
    private static final int FRAME_SAMPLES = 1280;
    private static final String[] PRONUNCIATIONS = {
        "CH AA1 R L Z",
        "CH AA1 R L IY0",
        "CH AA1 R L IY0 Z",
        "CH AA1 R L IH0 S",
        "CH AA1 R L IY0 S",
        "CH AA1 R L IH0 Z"
    };
    private static final float[] SCORES = {1.5f, 2.5f, 3.0f};
    private static final float[] THRESHOLDS = {0.10f, 0.15f, 0.20f};

    private OfflineWakeEvaluator() { }

    public static void run(Context ctx) {
        long t0 = System.currentTimeMillis();
        KwsEngine engine = new KwsEngine();
        float oldScore = KwsEngine.keywordsScore;
        float oldThreshold = KwsEngine.keywordsThreshold;
        String oldLine = KwsEngine.customKeywordLine;
        try {
            File file = new File(ctx.getExternalFilesDir(null), "Charles.m4a");
            if (!file.isFile() || !file.canRead()) {
                L.i("CHARLES_OFFLINE_FILE_NOT_READABLE path=" + file.getAbsolutePath());
                return;
            }
            float[] audio = decodeTo16k(file);
            L.i("CHARLES_OFFLINE_BEGIN samples=" + audio.length +
                    " sec=" + (audio.length / 16000.0) + " fileBytes=" + file.length());
            // KwsEngine.configure() selects the same native model as the production service.
            engine.configure(WakeWordStore.read(ctx));
            engine.load(ctx.getAssets());

            for (int p = 0; p < PRONUNCIATIONS.length; p++) {
                String phones = PRONUNCIATIONS[p];
                for (float score : SCORES) {
                    for (float threshold : THRESHOLDS) {
                        KwsEngine.customKeywordLine = phones + " @Test" + p;
                        KwsEngine.keywordsScore = score;
                        KwsEngine.keywordsThreshold = threshold;
                        engine.newStream();
                        int hits = 0;
                        StringBuilder positions = new StringBuilder();
                        int lastHitSample = -32000;
                        for (int offset = 0; offset < audio.length; offset += FRAME_SAMPLES) {
                            float[] frame = new float[FRAME_SAMPLES];
                            int n = Math.min(FRAME_SAMPLES, audio.length - offset);
                            System.arraycopy(audio, offset, frame, 0, n);
                            String result = engine.accept(frame, TARGET_SAMPLE_RATE);
                            if (result != null && offset - lastHitSample >= TARGET_SAMPLE_RATE) {
                                hits++;
                                if (positions.length() > 0) positions.append(",");
                                positions.append(String.format(java.util.Locale.US, "%.2f",
                                        (offset + n) / 16000.0));
                                lastHitSample = offset;
                            }
                        }
                        // Extra silence lets the decoder flush trailing phonemes.
                        for (int k = 0; k < 20; k++) {
                            String result = engine.accept(new float[FRAME_SAMPLES], TARGET_SAMPLE_RATE);
                            if (result != null) hits++;
                        }
                        L.i("CHARLES_OFFLINE_RESULT phones=[" + phones +
                                "] score=" + score + " threshold=" + threshold +
                                " hits=" + hits + " times=[" + positions + "]");
                        engine.releaseStream();
                    }
                }
            }
            L.i("CHARLES_OFFLINE_DONE elapsedMs=" + (System.currentTimeMillis() - t0));
        } catch (Throwable error) {
            L.e("CHARLES_OFFLINE_FAILED", error);
        } finally {
            engine.release();
            KwsEngine.keywordsScore = oldScore;
            KwsEngine.keywordsThreshold = oldThreshold;
            KwsEngine.customKeywordLine = oldLine;
        }
    }

    private static float[] decodeTo16k(File source) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            int selected = -1;
            MediaFormat trackFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat fmt = extractor.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    selected = i;
                    trackFormat = fmt;
                    break;
                }
            }
            if (selected < 0) throw new IllegalStateException("M4A has no audio track");
            extractor.selectTrack(selected);
            decoder = MediaCodec.createDecoderByType(trackFormat.getString(MediaFormat.KEY_MIME));
            decoder.configure(trackFormat, null, null, 0);
            decoder.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            ByteArrayOutputStream raw = new ByteArrayOutputStream(600000);
            boolean inputEos = false;
            boolean outputEos = false;
            int sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int encoding = 2; // ENCODING_PCM_16BIT

            for (int loops = 0; !outputEos && loops < 25000; loops++) {
                if (!inputEos) {
                    int index = decoder.dequeueInputBuffer(10000);
                    if (index >= 0) {
                        ByteBuffer in = decoder.getInputBuffer(index);
                        in.clear();
                        int size = extractor.readSampleData(in, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = decoder.dequeueOutputBuffer(info, 10000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat fmt = decoder.getOutputFormat();
                    sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = fmt.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                } else if (outIndex >= 0) {
                    if (info.size > 0) {
                        ByteBuffer out = decoder.getOutputBuffer(outIndex);
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        byte[] bytes = new byte[info.size];
                        out.get(bytes);
                        raw.write(bytes);
                    }
                    outputEos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(outIndex, false);
                }
            }
            if (!outputEos || encoding != 2) {
                throw new IllegalStateException("Audio decoder did not yield PCM16 (eos=" +
                        outputEos + ", encoding=" + encoding + ")");
            }
            byte[] pcm = raw.toByteArray();
            int srcFrames = pcm.length / (channels * 2);
            int dstFrames = (int) ((long) srcFrames * TARGET_SAMPLE_RATE / sampleRate);
            float[] mono = new float[srcFrames];
            ByteBuffer buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < srcFrames; i++) {
                float sum = 0;
                for (int ch = 0; ch < channels; ch++) sum += buffer.getShort() / 32768f;
                mono[i] = sum / channels;
            }
            float[] dst = new float[dstFrames];
            double ratio = (double) sampleRate / TARGET_SAMPLE_RATE;
            for (int i = 0; i < dstFrames; i++) {
                double pos = i * ratio;
                int left = (int) pos;
                int right = Math.min(srcFrames - 1, left + 1);
                double f = pos - left;
                dst[i] = (float) (mono[left] * (1 - f) + mono[right] * f);
            }
            L.i("CHARLES_OFFLINE_DECODE rate=" + sampleRate + " channels=" + channels +
                    " srcFrames=" + srcFrames + " dstFrames=" + dstFrames);
            return dst;
        } finally {
            try { if (decoder != null) decoder.stop(); } catch (Throwable ignored) { }
            if (decoder != null) decoder.release();
            extractor.release();
        }
    }
}
