package io.github.codex_cli_voice_android.aecshim;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

final class WakeProfile {
    static final String VALIDATION_SCOPE = "fake_profile_only";
    static final String BYO_VALIDATION_SCOPE = "byo_profile_path_only";

    final String id;
    final String engine;
    final String phrase;
    final String modelType;
    final String modelPath;
    final String melspectrogramPath;
    final String embeddingPath;
    final int sampleRate;
    final int frameMs;
    final double threshold;
    final long cooldownMs;
    final boolean licenseAcknowledged;

    WakeProfile(String id, String engine, String phrase) {
        this(id, engine, phrase, "", "", "", "", 0, 0, 0.0, 0L, false);
    }

    WakeProfile(
            String id,
            String engine,
            String phrase,
            String modelType,
            String modelPath,
            String melspectrogramPath,
            String embeddingPath,
            int sampleRate,
            int frameMs,
            double threshold,
            long cooldownMs,
            boolean licenseAcknowledged
    ) {
        this.id = id;
        this.engine = engine;
        this.phrase = phrase;
        this.modelType = modelType;
        this.modelPath = modelPath;
        this.melspectrogramPath = melspectrogramPath;
        this.embeddingPath = embeddingPath;
        this.sampleRate = sampleRate;
        this.frameMs = frameMs;
        this.threshold = threshold;
        this.cooldownMs = cooldownMs;
        this.licenseAcknowledged = licenseAcknowledged;
    }

    static WakeProfile fakeManual() {
        return new WakeProfile("fake_manual", "fake", "manual wake_fake_detect");
    }

    static WakeProfile fromJson(JSONObject in) {
        JSONObject source = in.optJSONObject("profile");
        if (source == null) {
            source = in;
        }
        String modelType = source.optString("modelType", source.optString("engine", "onnx")).trim().toLowerCase();
        String phrase = source.optString("label", source.optString("phrase", ""));
        return new WakeProfile(
                source.optString("id", "byo_wake_profile"),
                source.optString("engine", modelType),
                phrase,
                modelType,
                source.optString("modelPath", ""),
                source.optString("melspectrogramPath", ""),
                source.optString("embeddingPath", ""),
                source.optInt("sampleRate", 0),
                source.optInt("frameMs", 0),
                source.optDouble("threshold", 0.0),
                source.optLong("cooldownMs", 0L),
                source.optBoolean("licenseAcknowledged", false)
        );
    }

    boolean hasModelConfig() {
        return !modelPath.isEmpty() || !melspectrogramPath.isEmpty() || !embeddingPath.isEmpty();
    }

    JSONObject json() throws JSONException {
        JSONObject out = new JSONObject();
        out.put("id", id);
        out.put("engine", engine);
        out.put("phrase", phrase);
        out.put("validationScope", hasModelConfig() ? BYO_VALIDATION_SCOPE : VALIDATION_SCOPE);
        if (hasModelConfig()) {
            out.put("modelType", modelType);
            out.put("modelPath", modelPath);
            out.put("melspectrogramPath", melspectrogramPath);
            out.put("embeddingPath", embeddingPath);
            out.put("sampleRate", sampleRate);
            out.put("frameMs", frameMs);
            out.put("threshold", threshold);
            out.put("cooldownMs", cooldownMs);
            out.put("licenseAcknowledged", licenseAcknowledged);
        }
        return out;
    }

    static boolean readableFile(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        File file = new File(path);
        return file.isFile() && file.canRead();
    }
}
