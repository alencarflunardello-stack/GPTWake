package com.desmond.gptwake;

import android.content.Context;
import android.content.res.AssetManager;
import org.vosk.Model;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Installs the pinned, Apache-2.0, pt-BR Vosk model from local APK assets.
 * No microphone audio and no personal recording is uploaded or embedded in this model. */
final class VoskPtModel {
    private VoskPtModel() {}
    static synchronized Model open(Context ctx) throws Exception {
        File dir = new File(ctx.getFilesDir(), "vosk-small-pt-0.3");
        File done = new File(dir, ".complete");
        if (!done.isFile()) {
            L.i("VOSK_PT_UNPACK_BEGIN dir=" + dir.getAbsolutePath());
            copyAssetTree(ctx.getAssets(), "vosk-pt", dir);
            if (!new File(dir, "am/final.mdl").isFile() && !new File(dir, "final.mdl").isFile()) {
                throw new IllegalStateException("Vosk pt-BR model missing final.mdl");
            }
            if (!done.createNewFile()) throw new IllegalStateException("Vosk model marker creation failed");
            L.i("VOSK_PT_UNPACK_OK");
        }
        return new Model(dir.getAbsolutePath());
    }
    private static void copyAssetTree(AssetManager assets, String path, File dest) throws Exception {
        String[] children = assets.list(path);
        if (children == null) throw new IllegalStateException("Asset not found " + path);
        if (children.length > 0) {
            if (!dest.isDirectory() && !dest.mkdirs()) throw new IllegalStateException("Cannot create " + dest);
            for (String child : children) {
                copyAssetTree(assets, path + "/" + child, new File(dest, child));
            }
        } else {
            File parent = dest.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("Cannot create " + parent);
            try (InputStream input = assets.open(path); FileOutputStream output = new FileOutputStream(dest)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = input.read(buf)) != -1) output.write(buf, 0, n);
            }
        }
    }
}
