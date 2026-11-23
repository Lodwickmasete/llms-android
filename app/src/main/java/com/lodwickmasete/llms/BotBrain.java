package com.lodwickmasete.llms;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Everything about the local AI: storage access, the .gguf models folder,
 * loading the model, building prompts and streaming a reply from LlamaEngine.
 * Runs fully on the phone - nothing here uses the network.
 */
class BotBrain {

    interface Listener {
        void onToken(String token);

        /** error == null on success. */
        void onDone(String error);
    }

    static final int REQ_STORAGE = 301;

    // Conversation history sent to the model with every prompt (keeps the context small)
    private static final int MAX_HISTORY_MSGS = 8;
    private static final int MAX_HISTORY_CHARS = 3000;

    private final Activity act;
    private final Data data;
    private final Handler handler;

    /** Called (on the UI thread) when the "loading model" state changes. */
    Runnable onStateChanged;

    private LlamaEngine engine;                 // created lazily on a worker thread
    private String loadedPath = null;
    private volatile boolean modelLoading = false;
    private volatile boolean workerBusy = false;
    private int genCounter = 0;                 // invalidates callbacks of cancelled generations

    BotBrain(Activity act, Data data, Handler handler) {
        this.act = act;
        this.data = data;
        this.handler = handler;
    }

    boolean isLoading() {
        return modelLoading;
    }

    boolean isBusy() {
        return workerBusy;
    }

    // ── Storage access (models live in shared storage) ────────────────────────
    private boolean needsAllFilesAccess() {
        return Build.VERSION.SDK_INT >= 30 && act.getApplicationInfo().targetSdkVersion >= 30;
    }

    private boolean isAllFilesAccess() {
        try {
            return (Boolean) Environment.class.getMethod("isExternalStorageManager").invoke(null);
        } catch (Throwable t) {
            return false;
        }
    }

    boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT < 23) return true;
        if (needsAllFilesAccess()) return isAllFilesAccess();
        if (Build.VERSION.SDK_INT >= 30 && isAllFilesAccess()) return true;
        return act.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    void ensureStorageAccess() {
        if (hasStorageAccess()) return;
        if (needsAllFilesAccess()) {
            new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Storage access")
                    .setMessage("To load models from\n" + modelsDirPath()
                            + "\n\nallow \"All files access\" for this app.")
                    .setNegativeButton("Later", null)
                    .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            openAllFilesSettings();
                        }
                    })
                    .show();
        } else {
            act.requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    private void openAllFilesSettings() {
        try {
            act.startActivity(new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
                    Uri.parse("package:" + act.getPackageName())));
        } catch (Exception e) {
            try {
                act.startActivity(new Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"));
            } catch (Exception e2) {
                Toast.makeText(act, "Open Settings > Apps > this app > Permissions and allow file access",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ── Models folder ─────────────────────────────────────────────────────────
    String defaultModelsDirPath() {
        return new File(Environment.getExternalStorageDirectory(), "llms").getAbsolutePath();
    }

    String modelsDirPath() {
        String saved = data.str(Data.KEY_MODELS_DIR);
        return saved.length() > 0 ? saved : defaultModelsDirPath();
    }

    String modelPath(String name) {
        return new File(modelsDirPath(), name).getAbsolutePath();
    }

    /** All .gguf files in the models folder (empty list if none / no access). */
    List<String> listModels() {
        List<String> names = new ArrayList<String>();
        File dir = new File(modelsDirPath());
        if (!dir.exists() && hasStorageAccess()) dir.mkdirs();
        File[] files = dir.listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                File f = files[i];
                if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".gguf")) {
                    names.add(f.getName());
                }
            }
        }
        Collections.sort(names);
        return names;
    }

    /** The model used for new chats: the saved one if it still exists, else the first one. null = none. */
    String defaultModel() {
        List<String> models = listModels();
        if (models.isEmpty()) return null;
        String saved = data.prefs().getString(Data.KEY_MODEL, null);
        if (saved != null && models.contains(saved)) return saved;
        String first = models.get(0);
        data.prefs().edit().putString(Data.KEY_MODEL, first).apply();
        return first;
    }

    void setDefaultModel(String name) {
        data.prefs().edit().putString(Data.KEY_MODEL, name).apply();
    }

    void showFolderDialog(final Runnable onChanged) {
        final EditText et = new EditText(act);
        et.setText(modelsDirPath());
        et.setTextColor(Color.WHITE);
        et.setSingleLine(true);
        et.setSelection(et.getText().length());
        LinearLayout wrap = new LinearLayout(act);
        wrap.setPadding(Ui.dp(act, 20), Ui.dp(act, 8), Ui.dp(act, 20), 0);
        wrap.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Models folder")
                .setMessage("Folder that contains your .gguf model files.")
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Reset", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        data.prefs().edit().remove(Data.KEY_MODELS_DIR).apply();
                        onChanged.run();
                        Toast.makeText(act, "Folder reset", Toast.LENGTH_SHORT).show();
                    }
                })
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String path = et.getText().toString().trim();
                        if (path.length() == 0 || path.equals(defaultModelsDirPath())) {
                            data.prefs().edit().remove(Data.KEY_MODELS_DIR).apply();
                        } else {
                            data.prefs().edit().putString(Data.KEY_MODELS_DIR, path).apply();
                        }
                        if (!new File(modelsDirPath()).exists()) {
                            Toast.makeText(act, "Folder does not exist yet", Toast.LENGTH_SHORT).show();
                        }
                        onChanged.run();
                    }
                })
                .show();
    }

    // ── Engine (LlamaEngine) ──────────────────────────────────────────────────
    /** Loads the model on first use or when it changed. Runs on the worker thread. */
    private synchronized String ensureLoaded(String path) {
        File f = new File(path);
        if (!f.exists() || !f.canRead()) {
            return "Model file not found or not readable:\n" + path;
        }
        try {
            if (engine == null) engine = new LlamaEngine();
        } catch (Throwable t) {
            engine = null;
            return "Could not load native libraries (llama, llama_jni): " + t.getMessage();
        }
        if (engine.isReady() && path.equals(loadedPath)) return null;

        modelLoading = true;
        postState();
        try {
            engine.unload();
            loadedPath = null;
            boolean ok = engine.load(path);
            if (!ok) {
                return "Failed to load the model. The file may be invalid or the device may be out of memory.";
            }
            loadedPath = path;
            return null;
        } catch (Throwable t) {
            return "Failed to load the model: " + t.getMessage();
        } finally {
            modelLoading = false;
            postState();
        }
    }

    private void postState() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (onStateChanged != null) onStateChanged.run();
            }
        });
    }

    /**
     * Generates a reply to the last user message of {@code chat} on a worker thread.
     * The chat must already end with [user message, empty bot message].
     * Callbacks run on the UI thread and are ignored once the generation is cancelled.
     */
    void generate(Data.Chat chat, String model, String question, final Listener listener) {
        final int myGen = ++genCounter;
        final String path = modelPath(model);
        final String userMessage = buildChatMessage(chat.msgs, question, chat.msgs.size() - 2);
        final String systemPrompt = buildSystemPrompt();
        workerBusy = true;

        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    error = ensureLoaded(path);
                    if (error == null) {
                        engine.generate(userMessage, systemPrompt,
                                LlamaEngine.DEFAULT_SYSTEM_TAG,
                                LlamaEngine.DEFAULT_USER_TAG,
                                LlamaEngine.DEFAULT_ASSISTANT_TAG,
                                LlamaEngine.DEFAULT_END_TAG,
                                new LlamaEngine.StreamCallback() {
                                    @Override
                                    public void onToken(final String token) {
                                        handler.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                if (myGen == genCounter) listener.onToken(token);
                                            }
                                        });
                                    }
                                });
                    }
                } catch (Throwable t) {
                    error = "Generation failed: " + t.getMessage();
                } finally {
                    workerBusy = false;
                }
                final String finalError = error;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (myGen == genCounter) listener.onDone(finalError);
                    }
                });
            }
        }, "llama-worker").start();
    }

    /** Stops native generation and ignores any further callbacks from it. */
    void cancel() {
        genCounter++;
        if (engine != null && workerBusy) {
            try {
                engine.stop();
            } catch (Throwable ignored) {
            }
        }
    }

    void unload() {
        if (engine != null && !workerBusy && !modelLoading) {
            try {
                engine.unload();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Text placed between the user tag and end tag by LlamaEngine.generate().
     * Earlier turns are embedded with ChatML tags so the model sees the conversation.
     */
    private String buildChatMessage(List<Data.Message> msgs, String question, int historyEnd) {
        ArrayList<Data.Message> hist = new ArrayList<Data.Message>();
        int chars = 0;
        for (int i = historyEnd - 1; i >= 0 && hist.size() < MAX_HISTORY_MSGS; i--) {
            Data.Message m = msgs.get(i);
            if (m.system || m.text.isEmpty() || m.error) continue;
            if (chars + m.text.length() > MAX_HISTORY_CHARS) break;
            chars += m.text.length();
            hist.add(0, m);
        }
        while (!hist.isEmpty() && !hist.get(0).fromUser) hist.remove(0);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hist.size(); i++) {
            Data.Message m = hist.get(i);
            if (i > 0) {
                sb.append(LlamaEngine.DEFAULT_END_TAG)
                        .append(m.fromUser ? LlamaEngine.DEFAULT_USER_TAG : LlamaEngine.DEFAULT_ASSISTANT_TAG);
            }
            sb.append(m.text);
        }
        if (!hist.isEmpty()) {
            sb.append(LlamaEngine.DEFAULT_END_TAG).append(LlamaEngine.DEFAULT_USER_TAG);
        }
        sb.append(question);
        return sb.toString();
    }

    /**
     * System prompt. The answer style always applies; name / occupation / about are
     * added only when the user turned on "Share my profile with the AI".
     */
    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder(LlamaEngine.DEFAULT_SYSTEM_PROMPT);
        if (data.flag(Data.KEY_INCLUDE, false)) {
            String name = data.str(Data.KEY_NAME);
            String role = data.str(Data.KEY_ROLE);
            String info = data.str(Data.KEY_INFO);
            String village = data.str(Data.KEY_VILLAGE);
            if (name.length() > 0) sb.append(" The user's name is ").append(name).append(".");
            if (role.length() > 0) sb.append(" The user's occupation: ").append(role).append(".");
            if (village.length() > 0) sb.append(" The user lives in: ").append(village).append(".");
            if (info.length() > 0) sb.append(" About the user: ").append(info).append(".");
        }
        int style = data.prefs().getInt(Data.KEY_STYLE, 1);
        if (style == 0) sb.append(" Keep answers short and to the point.");
        if (style == 2) sb.append(" Give detailed, thorough answers.");
        return sb.toString();
    }
}
