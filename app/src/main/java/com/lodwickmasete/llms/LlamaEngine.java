package com.lodwickmasete.llms;

public class LlamaEngine {

    static {
        System.loadLibrary("llama");
        System.loadLibrary("llama_jni");
    }

    public interface StreamCallback {
        void onToken(String token);
    }

    // ── Chat format ───────────────────────────────────────────────────────────
    public static final String DEFAULT_SYSTEM_PROMPT  = "You are a helpful assistant.";
    public static final String DEFAULT_SYSTEM_TAG     = "<|im_start|>system\n";
    public static final String DEFAULT_USER_TAG       = "<|im_start|>user\n";
    public static final String DEFAULT_ASSISTANT_TAG  = "<|im_start|>assistant\n";
    public static final String DEFAULT_END_TAG        = "<|im_end|>\n";

    // Stop strings passed to C++ — never hardcoded there
public static final String[] DEFAULT_STOP_STRINGS = {
    "<|im_end|>",
    "<|im_start|>user",
    "<|im_start|>assistant",
    "<|im_start|>system",
    "<|endoftext|>",
    "<|eot_id|>",
    "</s>",
    "<|endof-response|>",
    "<|startof-response|>",
    "<|begin_of_solution|>",
    
    //falcon
    "User:",
    "Assistant:",
    "System:",
    
};


    // ── Sampler / generation settings ─────────────────────────────────────────
    public int   maxTokens       = 512;
    public float temperature     = 0.7f;
    public float topP            = 0.9f;
    public int   topK            = 40;
    public float repeatPenalty   = 1.1f;
    public float freqPenalty     = 0.0f;
    public float presencePenalty = 0.0f;
    public int   penaltyLastN    = 64;

    // ── Stop string / token configuration ────────────────────────────────────
    public String[] stopStrings      = DEFAULT_STOP_STRINGS;
    public int[]    extraStopTokenIds = new int[0];

    // ── Internal ──────────────────────────────────────────────────────────────
    private long ctx = 0;

    // ── Native declarations — order must match JNI signature exactly ──────────
    private native long initModel(String path);
    private native void runPromptStreaming(
            String         prompt,
            int            maxTokens,
            float          temperature,
            float          topP,
            int            topK,
            float          repeatPenalty,
            float          freqPenalty,
            float          presencePenalty,
            int            penaltyLastN,
            String[]       stopStrings,
            int[]          extraStopTokenIds,
            StreamCallback callback);
    private native void stopGeneration();
    private native void freeModel();

    // ── Public API ────────────────────────────────────────────────────────────

    public boolean load(String modelPath) {
        try {
            ctx = initModel(modelPath);
        } catch (Throwable t) {
            ctx = 0;
        }
        return ctx != 0;
    }

    /**
     * Full control overload — pass your own chat format tags.
     */
    public void generate(
            String         userMessage,
            String         systemPrompt,
            String         systemTag,
            String         userTag,
            String         assistantTag,
            String         endTag,
            StreamCallback callback) {

        if (ctx == 0) {
            callback.onToken("[Model not loaded]");
            return;
        }

        if (systemPrompt == null) systemPrompt = DEFAULT_SYSTEM_PROMPT;

        String fullPrompt =
                systemTag    + systemPrompt + endTag
              + userTag      + userMessage  + endTag
              + assistantTag;

        runPromptStreaming(
                fullPrompt,
                maxTokens,
                temperature,
                topP,
                topK,
                repeatPenalty,
                freqPenalty,
                presencePenalty,
                penaltyLastN,
                stopStrings,
                extraStopTokenIds,
                callback);
    }

    /**
     * Convenience overload — uses DEFAULT_* chat format constants.
     */
    public void generate(String userMessage, StreamCallback callback) {
        generate(
                userMessage,
                DEFAULT_SYSTEM_PROMPT,
                DEFAULT_SYSTEM_TAG,
                DEFAULT_USER_TAG,
                DEFAULT_ASSISTANT_TAG,
                DEFAULT_END_TAG,
                callback);
    }

    /**
     * Convenience overload — custom system prompt, default tags.
     */
    public void generate(String userMessage, String systemPrompt,
                         StreamCallback callback) {
        generate(
                userMessage,
                systemPrompt,
                DEFAULT_SYSTEM_TAG,
                DEFAULT_USER_TAG,
                DEFAULT_ASSISTANT_TAG,
                DEFAULT_END_TAG,
                callback);
    }

    public void stop()   { stopGeneration(); }

    public void unload() {
        if (ctx != 0) { freeModel(); ctx = 0; }
    }

    public boolean isReady() { return ctx != 0; }
}