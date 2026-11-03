package dev.horizon.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Frolizon Android Launcher — full Java UI layer over the Rust NativeActivity.
 *
 * Manages:
 *  • .hcfg config file system (load/save/create/duplicate/delete/edit key-value pairs)
 *  • Version + renderer + FPS spinner population from app-private JSON catalogue
 *  • Launch button that writes the active .hcfg into native launcher JSON before handing off
 *  • Server address, offline nickname, render scale, performance toggles
 *  • Status bar feedback and progress overlay during launch
 *
 * Config format (.hcfg):
 *   Plain key=value lines, UTF-8, # comments.
 *   Active config path is persisted in SharedPreferences under key "active_cfg".
 *   All .hcfg files live in filesDir/hcfg/.
 */
public final class FrolizonLauncherActivity extends Activity {

    // ─── constants ────────────────────────────────────────────────────────────
    private static final String PREFS_NAME        = "frolizon_prefs";
    private static final String PREF_ACTIVE_CFG   = "active_cfg";
    private static final String PREF_PLAYER_NAME  = "player_name";
    private static final String PREF_SERVER       = "server_address";
    private static final String PREF_RENDERER     = "renderer_mode";
    private static final String PREF_FPS          = "fps_limit";
    private static final String PREF_RENDER_SCALE = "render_scale";
    private static final String PREF_PERF_HINTS   = "perf_hints";
    private static final String PREF_GPU_PROFILE  = "gpu_profile";
    private static final String PREF_RECORD_PERF  = "record_perf";

    private static final String DEFAULT_CFG_NAME  = "default.hcfg";
    private static final String CFG_DIR           = "hcfg";

    private static final String[] RENDERER_LABELS = {
        "Vulkan / совместимость",
        "Vulkan / GPU batching",
        "OpenGL ES"
    };
    private static final String[] RENDERER_VALUES = {
        "vulkan-compatibility",
        "vulkan",
        "opengl"
    };
    private static final int[] FPS_VALUES = { 0, 30, 60, 90, 120, 144, 165 };

    // ─── views ────────────────────────────────────────────────────────────────
    private Spinner     spinnerVersions;
    private Spinner     spinnerCfgFiles;
    private Spinner     spinnerRenderer;
    private Spinner     spinnerFps;
    private EditText    etServerAddress;
    private EditText    etPlayerName;
    private EditText    etRenderScale;
    private CheckBox    cbPerfHints;
    private CheckBox    cbGpuProfile;
    private CheckBox    cbRecordPerf;
    private Button      btnLaunch;
    private Button      btnCfgNew;
    private Button      btnCfgDuplicate;
    private Button      btnCfgDelete;
    private Button      btnCfgEdit;
    private Button      btnOpenMods;
    private Button      btnOpenControls;
    private Button      btnGithubOriginal;
    private Button      btnGithubFork;
    private TextView    tvActiveCfg;
    private TextView    tvActiveCfgHeader;
    private TextView    tvStatus;
    private TextView    tvVersionBadge;
    private LinearLayout llCfgValues;
    private ProgressBar  progressLaunch;

    // ─── state ────────────────────────────────────────────────────────────────
    private SharedPreferences prefs;
    private File cfgDir;
    private List<String> cfgFiles = new ArrayList<>();
    private Map<String, String> activeCfgValues = new LinkedHashMap<>();
    private String activeCfgName = DEFAULT_CFG_NAME;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ─── lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);

        prefs  = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        cfgDir = new File(getFilesDir(), CFG_DIR);
        if (!cfgDir.exists()) cfgDir.mkdirs();

        bindViews();
        populateRendererSpinner();
        populateFpsSpinner();
        populateVersionSpinner();
        restorePreferences();
        refreshCfgSpinner();
        attachListeners();
    }

    @Override
    protected void onPause() {
        super.onPause();
        persistPreferences();
    }

    // ─── view binding ─────────────────────────────────────────────────────────

    private void bindViews() {
        spinnerVersions     = findViewById(R.id.spinner_versions);
        spinnerCfgFiles     = findViewById(R.id.spinner_cfg_files);
        spinnerRenderer     = findViewById(R.id.spinner_renderer);
        spinnerFps          = findViewById(R.id.spinner_fps);
        etServerAddress     = findViewById(R.id.et_server_address);
        etPlayerName        = findViewById(R.id.et_player_name);
        etRenderScale       = findViewById(R.id.et_render_scale);
        cbPerfHints         = findViewById(R.id.cb_perf_hints);
        cbGpuProfile        = findViewById(R.id.cb_gpu_profile);
        cbRecordPerf        = findViewById(R.id.cb_record_perf);
        btnLaunch           = findViewById(R.id.btn_launch_game);
        btnCfgNew           = findViewById(R.id.btn_cfg_new);
        btnCfgDuplicate     = findViewById(R.id.btn_cfg_duplicate);
        btnCfgDelete        = findViewById(R.id.btn_cfg_delete);
        btnCfgEdit          = findViewById(R.id.btn_cfg_edit);
        btnOpenMods         = findViewById(R.id.btn_open_mods);
        btnOpenControls     = findViewById(R.id.btn_open_controls);
        btnGithubOriginal   = findViewById(R.id.btn_github_original);
        btnGithubFork       = findViewById(R.id.btn_github_fork);
        tvActiveCfg         = findViewById(R.id.tv_active_cfg);
        tvActiveCfgHeader   = findViewById(R.id.tv_active_cfg_header);
        tvStatus            = findViewById(R.id.tv_status);
        tvVersionBadge      = findViewById(R.id.tv_version_badge);
        llCfgValues         = findViewById(R.id.ll_cfg_values);
        progressLaunch      = findViewById(R.id.progress_launch);
    }

    // ─── spinners ─────────────────────────────────────────────────────────────

    private void populateRendererSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, RENDERER_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerRenderer.setAdapter(adapter);
    }

    private void populateFpsSpinner() {
        String[] labels = new String[FPS_VALUES.length];
        labels[0] = "Системный лимит";
        for (int i = 1; i < FPS_VALUES.length; i++) {
            labels[i] = FPS_VALUES[i] + " Hz";
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerFps.setAdapter(adapter);
    }

    private void populateVersionSpinner() {
        // Read version catalogue from app-private JSON written by the Rust side.
        // Falls back to a synthetic "bundled" entry so the UI is never empty.
        List<String> versions = new ArrayList<>();
        File catalogue = new File(getFilesDir(), "version-catalogue.json");
        if (catalogue.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(catalogue))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                org.json.JSONArray arr = new org.json.JSONArray(sb.toString());
                for (int i = 0; i < arr.length(); i++) {
                    versions.add(arr.getString(i));
                }
            } catch (Exception ignored) {}
        }
        if (versions.isEmpty()) {
            versions.add("Horizon 26.3 (bundled)");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, versions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerVersions.setAdapter(adapter);
    }

    // ─── preferences ──────────────────────────────────────────────────────────

    private void restorePreferences() {
        etPlayerName.setText(prefs.getString(PREF_PLAYER_NAME, ""));
        etServerAddress.setText(prefs.getString(PREF_SERVER, ""));
        etRenderScale.setText(prefs.getString(PREF_RENDER_SCALE, "1.0"));
        cbPerfHints.setChecked(prefs.getBoolean(PREF_PERF_HINTS, false));
        cbGpuProfile.setChecked(prefs.getBoolean(PREF_GPU_PROFILE, false));
        cbRecordPerf.setChecked(prefs.getBoolean(PREF_RECORD_PERF, false));

        // renderer
        String savedRenderer = prefs.getString(PREF_RENDERER, "vulkan-compatibility");
        for (int i = 0; i < RENDERER_VALUES.length; i++) {
            if (RENDERER_VALUES[i].equals(savedRenderer)) {
                spinnerRenderer.setSelection(i);
                break;
            }
        }

        // fps
        int savedFps = prefs.getInt(PREF_FPS, 0);
        for (int i = 0; i < FPS_VALUES.length; i++) {
            if (FPS_VALUES[i] == savedFps) {
                spinnerFps.setSelection(i);
                break;
            }
        }

        activeCfgName = prefs.getString(PREF_ACTIVE_CFG, DEFAULT_CFG_NAME);
    }

    private void persistPreferences() {
        SharedPreferences.Editor ed = prefs.edit();
        ed.putString(PREF_PLAYER_NAME, etPlayerName.getText().toString().trim());
        ed.putString(PREF_SERVER, etServerAddress.getText().toString().trim());
        ed.putString(PREF_RENDER_SCALE, etRenderScale.getText().toString().trim());
        ed.putBoolean(PREF_PERF_HINTS, cbPerfHints.isChecked());
        ed.putBoolean(PREF_GPU_PROFILE, cbGpuProfile.isChecked());
        ed.putBoolean(PREF_RECORD_PERF, cbRecordPerf.isChecked());
        ed.putString(PREF_RENDERER, RENDERER_VALUES[spinnerRenderer.getSelectedItemPosition()]);
        ed.putInt(PREF_FPS, FPS_VALUES[spinnerFps.getSelectedItemPosition()]);
        ed.putString(PREF_ACTIVE_CFG, activeCfgName);
        ed.apply();
    }

    // ─── .hcfg file system ───────────────────────────────────────────────────

    /** Parse a .hcfg file (key=value, # comments) into a LinkedHashMap preserving order. */
    private Map<String, String> parseCfg(File f) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int eq = trimmed.indexOf('=');
                if (eq < 1) continue;
                String key   = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                map.put(key, value);
            }
        }
        return map;
    }

    /** Serialize map back to .hcfg file. Writes atomically via .partial rename. */
    private void writeCfg(File f, Map<String, String> values) throws IOException {
        File partial = new File(f.getParent(), f.getName() + ".partial");
        try (FileWriter w = new FileWriter(partial)) {
            w.write("# Frolizon .hcfg — auto-managed, do not move this file\n");
            for (Map.Entry<String, String> e : values.entrySet()) {
                w.write(e.getKey() + " = " + e.getValue() + "\n");
            }
        }
        if (!partial.renameTo(f)) {
            // Rename can fail across filesystems on some devices; fallback to copy+delete
            try (FileWriter w = new FileWriter(f)) {
                java.io.FileInputStream fis = new java.io.FileInputStream(partial);
                byte[] buf = new byte[4096];
                int n;
                // just re-write from partial's content via reader
            }
            partial.delete();
            // Retry by writing directly
            try (FileWriter w = new FileWriter(f)) {
                w.write("# Frolizon .hcfg\n");
                for (Map.Entry<String, String> e : values.entrySet()) {
                    w.write(e.getKey() + " = " + e.getValue() + "\n");
                }
            }
        }
    }

    /** Default key-value set for a fresh .hcfg. */
    private Map<String, String> defaultCfgValues() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("renderer",     "vulkan-compatibility");
        m.put("fps_limit",    "0");
        m.put("render_scale", "1.0");
        m.put("perf_hints",   "false");
        m.put("gpu_profile",  "false");
        m.put("record_perf",  "false");
        m.put("player_name",  "");
        m.put("server",       "");
        return m;
    }

    /** Load the active .hcfg; create default.hcfg if nothing exists yet. */
    private void loadActiveCfg() {
        File f = new File(cfgDir, activeCfgName);
        if (!f.exists()) {
            activeCfgValues = defaultCfgValues();
            try {
                writeCfg(f, activeCfgValues);
            } catch (IOException e) {
                showStatus("Ошибка создания конфига: " + e.getMessage());
            }
            return;
        }
        try {
            activeCfgValues = parseCfg(f);
        } catch (IOException e) {
            activeCfgValues = defaultCfgValues();
            showStatus("Ошибка загрузки: " + e.getMessage());
        }
        applyActiveCfgToUi();
    }

    /** Push .hcfg values into spinner / edittext widgets. */
    private void applyActiveCfgToUi() {
        String r = activeCfgValues.getOrDefault("renderer", "vulkan-compatibility");
        for (int i = 0; i < RENDERER_VALUES.length; i++) {
            if (RENDERER_VALUES[i].equals(r)) { spinnerRenderer.setSelection(i); break; }
        }
        try {
            int fps = Integer.parseInt(activeCfgValues.getOrDefault("fps_limit", "0"));
            for (int i = 0; i < FPS_VALUES.length; i++) {
                if (FPS_VALUES[i] == fps) { spinnerFps.setSelection(i); break; }
            }
        } catch (NumberFormatException ignored) {}

        etRenderScale.setText(activeCfgValues.getOrDefault("render_scale", "1.0"));
        cbPerfHints.setChecked("true".equals(activeCfgValues.get("perf_hints")));
        cbGpuProfile.setChecked("true".equals(activeCfgValues.get("gpu_profile")));
        cbRecordPerf.setChecked("true".equals(activeCfgValues.get("record_perf")));

        String name = activeCfgValues.getOrDefault("player_name", "");
        if (!name.isEmpty()) etPlayerName.setText(name);
        String srv = activeCfgValues.getOrDefault("server", "");
        if (!srv.isEmpty()) etServerAddress.setText(srv);
    }

    /** Pull UI state into activeCfgValues (before save). */
    private void collectUiIntoCfg() {
        activeCfgValues.put("renderer", RENDERER_VALUES[spinnerRenderer.getSelectedItemPosition()]);
        activeCfgValues.put("fps_limit", String.valueOf(FPS_VALUES[spinnerFps.getSelectedItemPosition()]));
        activeCfgValues.put("render_scale", etRenderScale.getText().toString().trim());
        activeCfgValues.put("perf_hints", cbPerfHints.isChecked() ? "true" : "false");
        activeCfgValues.put("gpu_profile", cbGpuProfile.isChecked() ? "true" : "false");
        activeCfgValues.put("record_perf", cbRecordPerf.isChecked() ? "true" : "false");
        activeCfgValues.put("player_name", etPlayerName.getText().toString().trim());
        activeCfgValues.put("server", etServerAddress.getText().toString().trim());
    }

    /** Save activeCfgValues to disk. */
    private boolean saveActiveCfg() {
        collectUiIntoCfg();
        File f = new File(cfgDir, activeCfgName);
        try {
            writeCfg(f, activeCfgValues);
            return true;
        } catch (IOException e) {
            showStatus("Ошибка сохранения: " + e.getMessage());
            return false;
        }
    }

    /** Enumerate .hcfg files in cfgDir. */
    private List<String> listCfgFiles() {
        List<String> list = new ArrayList<>();
        File[] files = cfgDir.listFiles((d, n) -> n.endsWith(".hcfg"));
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) list.add(f.getName());
        }
        if (list.isEmpty()) {
            // Create default if nothing exists
            list.add(DEFAULT_CFG_NAME);
            File def = new File(cfgDir, DEFAULT_CFG_NAME);
            if (!def.exists()) {
                try { writeCfg(def, defaultCfgValues()); } catch (IOException ignored) {}
            }
        }
        return list;
    }

    /** Repopulate the CFG spinner + preview panel. */
    private void refreshCfgSpinner() {
        cfgFiles = listCfgFiles();
        if (!cfgFiles.contains(activeCfgName)) {
            activeCfgName = cfgFiles.get(0);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, cfgFiles);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCfgFiles.setAdapter(adapter);

        int pos = cfgFiles.indexOf(activeCfgName);
        spinnerCfgFiles.setSelection(Math.max(0, pos));

        updateCfgBadges();
        loadActiveCfg();
        rebuildCfgPreview();
    }

    private void updateCfgBadges() {
        String label = "КФГ: " + activeCfgName;
        if (tvActiveCfg != null)       tvActiveCfg.setText(label);
        if (tvActiveCfgHeader != null) tvActiveCfgHeader.setText(activeCfgName);
    }

    /** Build the inline key=value preview rows inside the cfg card. */
    private void rebuildCfgPreview() {
        if (llCfgValues == null) return;
        llCfgValues.removeAllViews();
        int shown = 0;
        for (Map.Entry<String, String> e : activeCfgValues.entrySet()) {
            if (shown >= 6) break; // keep preview short
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dpToPx(2);
            row.setLayoutParams(lp);

            TextView tvKey = new TextView(this);
            tvKey.setText(e.getKey());
            tvKey.setTextColor(0xFF9575CD);
            tvKey.setTextSize(11);
            LinearLayout.LayoutParams kp =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tvKey.setLayoutParams(kp);

            TextView tvVal = new TextView(this);
            tvVal.setText(e.getValue());
            tvVal.setTextColor(0xFFEDE7F6);
            tvVal.setTextSize(11);
            LinearLayout.LayoutParams vp =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tvVal.setLayoutParams(vp);

            row.addView(tvKey);
            row.addView(tvVal);
            llCfgValues.addView(row);
            shown++;
        }
        if (activeCfgValues.size() > 6) {
            TextView more = new TextView(this);
            more.setText("+ ещё " + (activeCfgValues.size() - 6) + " параметров…");
            more.setTextColor(0xFF6D5FA0);
            more.setTextSize(10);
            llCfgValues.addView(more);
        }
    }

    // ─── launch ───────────────────────────────────────────────────────────────

    /**
     * Write the launcher config JSON that the Rust entry point reads,
     * merging active .hcfg values into it. Then hand control to HorizonActivity.
     */
    private void doLaunch() {
        if (!saveActiveCfg()) return;

        btnLaunch.setVisibility(View.INVISIBLE);
        progressLaunch.setVisibility(View.VISIBLE);
        btnLaunch.setEnabled(false);

        new Thread(() -> {
            try {
                writeLauncherJson();
                mainHandler.post(() -> {
                    Intent intent = new Intent(this, HorizonActivity.class);
                    startActivity(intent);
                    progressLaunch.setVisibility(View.GONE);
                    btnLaunch.setVisibility(View.VISIBLE);
                    btnLaunch.setEnabled(true);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    progressLaunch.setVisibility(View.GONE);
                    btnLaunch.setVisibility(View.VISIBLE);
                    btnLaunch.setEnabled(true);
                    showStatus("Ошибка запуска: " + e.getMessage());
                });
            }
        }).start();
    }

    /**
     * Merge active cfg into android-launcher.json. The file is written atomically;
     * Rust reads it on next android_main execution.
     *
     * Keys written:
     *   renderer  → catalog::RendererMode (vulkan-compatibility / vulkan / opengl)
     *   display_hz → u16 display rate
     *   render_scale → f32 string
     *   perf_hints, gpu_profile, record_performance → bool
     *   name      → offline username
     *   server    → direct connect address
     *   active_cfg → path of loaded .hcfg (informational)
     */
    private void writeLauncherJson() throws IOException, JSONException {
        File out  = new File(getFilesDir(), "android-launcher.json");
        File part = new File(getFilesDir(), "android-launcher.partial");

        // Read existing to preserve fields we don't touch (profile, microsoft_client_id, etc.)
        JSONObject json = new JSONObject();
        if (out.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(out))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                json = new JSONObject(sb.toString());
            } catch (Exception ignored) {}
        }

        // Overlay from activeCfgValues (already updated by collectUiIntoCfg)
        json.put("renderer", activeCfgValues.getOrDefault("renderer", "vulkan-compatibility"));
        json.put("display_hz", safeInt(activeCfgValues.get("fps_limit"), 0));
        json.put("perf_hints",  "true".equals(activeCfgValues.get("perf_hints")));
        json.put("gpu_profile", "true".equals(activeCfgValues.get("gpu_profile")));
        json.put("record_performance", "true".equals(activeCfgValues.get("record_perf")));
        json.put("name",   activeCfgValues.getOrDefault("player_name", ""));
        json.put("server", activeCfgValues.getOrDefault("server", ""));
        json.put("active_cfg", new File(cfgDir, activeCfgName).getAbsolutePath());

        // Render scale goes in as the display object the Rust side expects: "1.0" or "75%"
        String rsc = activeCfgValues.getOrDefault("render_scale", "1.0");
        json.put("render_scale", rsc);

        // Version selection
        int versionPos = spinnerVersions.getSelectedItemPosition();
        json.put("version_index", versionPos);

        try (FileWriter w = new FileWriter(part)) {
            w.write(json.toString(2));
        }
        if (!part.renameTo(out)) {
            // atomic rename failed, write directly
            try (FileWriter w = new FileWriter(out)) {
                w.write(json.toString(2));
            }
            part.delete();
        }
    }

    // ─── cfg CRUD dialogs ─────────────────────────────────────────────────────

    private void onCfgNew() {
        EditText input = new EditText(this);
        input.setHint("Название (без .hcfg)");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setBackground(getDrawable(R.drawable.bg_frolizon_input));
        input.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        input.setTextColor(0xFFEDE7F6);
        input.setHintTextColor(0xFF4A4060);

        new AlertDialog.Builder(this)
                .setTitle("Новый конфиг")
                .setView(input)
                .setPositiveButton("Создать", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        showStatus("Имя не может быть пустым");
                        return;
                    }
                    if (!name.endsWith(".hcfg")) name += ".hcfg";
                    if (!isValidCfgName(name)) {
                        showStatus("Недопустимое имя файла");
                        return;
                    }
                    File f = new File(cfgDir, name);
                    if (f.exists()) {
                        showStatus("Конфиг уже существует");
                        return;
                    }
                    try {
                        writeCfg(f, defaultCfgValues());
                        activeCfgName = name;
                        prefs.edit().putString(PREF_ACTIVE_CFG, name).apply();
                        refreshCfgSpinner();
                        showStatus("Создан: " + name);
                    } catch (IOException e) {
                        showStatus("Ошибка: " + e.getMessage());
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void onCfgDuplicate() {
        File src = new File(cfgDir, activeCfgName);
        String baseName = activeCfgName.replace(".hcfg", "") + "_copy";
        String newName  = baseName + ".hcfg";
        // Avoid collision
        int suffix = 1;
        while (new File(cfgDir, newName).exists()) {
            newName = baseName + suffix + ".hcfg";
            suffix++;
        }
        File dst = new File(cfgDir, newName);
        try {
            Map<String, String> vals = parseCfg(src);
            writeCfg(dst, vals);
            activeCfgName = newName;
            prefs.edit().putString(PREF_ACTIVE_CFG, newName).apply();
            refreshCfgSpinner();
            showStatus("Скопирован: " + newName);
        } catch (IOException e) {
            showStatus("Ошибка: " + e.getMessage());
        }
    }

    private void onCfgDelete() {
        if (activeCfgName.equals(DEFAULT_CFG_NAME) && cfgFiles.size() == 1) {
            showStatus("Нельзя удалить последний конфиг");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Удалить " + activeCfgName + "?")
                .setMessage("Файл будет удалён безвозвратно.")
                .setPositiveButton("Удалить", (d, w) -> {
                    File f = new File(cfgDir, activeCfgName);
                    f.delete();
                    cfgFiles.remove(activeCfgName);
                    activeCfgName = cfgFiles.isEmpty() ? DEFAULT_CFG_NAME : cfgFiles.get(0);
                    prefs.edit().putString(PREF_ACTIVE_CFG, activeCfgName).apply();
                    refreshCfgSpinner();
                    showStatus("Удалён");
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /**
     * Full inline editor: shows all key=value pairs with editable fields,
     * plus buttons to add/remove keys.
     */
    private void onCfgEdit() {
        // Collect current UI into cfg before showing editor
        collectUiIntoCfg();

        // Build scrollable key-value form
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));

        // One row per entry
        final Map<String, String> editMap = new LinkedHashMap<>(activeCfgValues);
        final List<String[]> rows = new ArrayList<>(); // [0]=key, [1]=value
        for (Map.Entry<String, String> e : editMap.entrySet()) {
            rows.add(new String[]{ e.getKey(), e.getValue() });
        }

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(inner);
        container.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(320)));

        // Button to add new key
        Button btnAddKey = new Button(this);
        btnAddKey.setText("+ Добавить параметр");
        btnAddKey.setBackground(getDrawable(R.drawable.bg_frolizon_button));
        btnAddKey.setTextColor(0xFFFFFFFF);
        btnAddKey.setAllCaps(false);
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addParams.topMargin = dpToPx(8);
        container.addView(btnAddKey, addParams);

        // Populate initial rows
        Runnable buildRows = new Runnable() {
            @Override public void run() {
                inner.removeAllViews();
                for (int i = 0; i < rows.size(); i++) {
                    final int idx = i;
                    LinearLayout row = new LinearLayout(FrolizonLauncherActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(42));
                    rp.bottomMargin = dpToPx(4);
                    row.setLayoutParams(rp);

                    EditText etKey = new EditText(FrolizonLauncherActivity.this);
                    etKey.setText(rows.get(idx)[0]);
                    etKey.setBackground(getDrawable(R.drawable.bg_frolizon_input));
                    etKey.setTextColor(0xFF9575CD);
                    etKey.setTextSize(12);
                    etKey.setPadding(dpToPx(8), 0, dpToPx(8), 0);
                    etKey.addTextChangedListener(new SimpleTextWatcher(s -> rows.get(idx)[0] = s));
                    LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                    kp.marginEnd = dpToPx(4);
                    etKey.setLayoutParams(kp);

                    EditText etVal = new EditText(FrolizonLauncherActivity.this);
                    etVal.setText(rows.get(idx)[1]);
                    etVal.setBackground(getDrawable(R.drawable.bg_frolizon_input));
                    etVal.setTextColor(0xFFEDE7F6);
                    etVal.setTextSize(12);
                    etVal.setPadding(dpToPx(8), 0, dpToPx(8), 0);
                    etVal.addTextChangedListener(new SimpleTextWatcher(s -> rows.get(idx)[1] = s));
                    LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                    vp.marginEnd = dpToPx(4);
                    etVal.setLayoutParams(vp);

                    Button btnDel = new Button(FrolizonLauncherActivity.this);
                    btnDel.setText("✕");
                    btnDel.setBackground(getDrawable(R.drawable.bg_frolizon_button_danger));
                    btnDel.setTextColor(0xFFFFFFFF);
                    btnDel.setTextSize(11);
                    LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                            dpToPx(38), ViewGroup.LayoutParams.MATCH_PARENT);
                    btnDel.setLayoutParams(dp2);
                    btnDel.setOnClickListener(v -> {
                        rows.remove(idx);
                        this.run();
                    });

                    row.addView(etKey);
                    row.addView(etVal);
                    row.addView(btnDel);
                    inner.addView(row);
                }
            }
        };
        buildRows.run();

        btnAddKey.setOnClickListener(v -> {
            rows.add(new String[]{ "key" + rows.size(), "" });
            buildRows.run();
        });

        new AlertDialog.Builder(this)
                .setTitle("Редактор: " + activeCfgName)
                .setView(container)
                .setPositiveButton("Сохранить", (d, w) -> {
                    Map<String, String> newMap = new LinkedHashMap<>();
                    for (String[] row : rows) {
                        String k = row[0].trim();
                        if (!k.isEmpty()) newMap.put(k, row[1].trim());
                    }
                    activeCfgValues = newMap;
                    applyActiveCfgToUi();
                    if (saveActiveCfg()) {
                        refreshCfgSpinner();
                        showStatus("Конфиг сохранён");
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // ─── listeners ────────────────────────────────────────────────────────────

    private void attachListeners() {
        btnLaunch.setOnClickListener(v -> doLaunch());

        btnCfgNew.setOnClickListener(v -> onCfgNew());
        btnCfgDuplicate.setOnClickListener(v -> onCfgDuplicate());
        btnCfgDelete.setOnClickListener(v -> onCfgDelete());
        btnCfgEdit.setOnClickListener(v -> onCfgEdit());

        btnOpenMods.setOnClickListener(v -> {
            // Launch the Rust-based mod catalogue via HorizonActivity with extra flag
            Intent i = new Intent(this, HorizonActivity.class);
            i.putExtra("frolizon_page", "mods");
            startActivity(i);
        });

        btnOpenControls.setOnClickListener(v -> onCfgEdit());

        btnGithubOriginal.setOnClickListener(v ->
                openUrl("https://github.com/horizon-rs/horizon"));
        btnGithubFork.setOnClickListener(v ->
                openUrl("https://github.com/frolizon/frolizon-android"));

        // CFG spinner selection
        spinnerCfgFiles.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                String selected = cfgFiles.get(pos);
                if (!selected.equals(activeCfgName)) {
                    // Save current before switching
                    saveActiveCfg();
                    activeCfgName = selected;
                    prefs.edit().putString(PREF_ACTIVE_CFG, activeCfgName).apply();
                    loadActiveCfg();
                    rebuildCfgPreview();
                    updateCfgBadges();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        // Auto-save UI state into prefs on any change
        SimpleTextWatcher persistWatcher = new SimpleTextWatcher(s -> persistPreferences());
        etPlayerName.addTextChangedListener(persistWatcher);
        etServerAddress.addTextChangedListener(persistWatcher);
        etRenderScale.addTextChangedListener(persistWatcher);
        cbPerfHints.setOnCheckedChangeListener((b, c) -> persistPreferences());
        cbGpuProfile.setOnCheckedChangeListener((b, c) -> persistPreferences());
        cbRecordPerf.setOnCheckedChangeListener((b, c) -> persistPreferences());
        spinnerRenderer.setOnItemSelectedListener(new SimpleSpinnerListener(() -> persistPreferences()));
        spinnerFps.setOnItemSelectedListener(new SimpleSpinnerListener(() -> persistPreferences()));
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private void showStatus(String msg) {
        if (tvStatus == null) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); return; }
        tvStatus.setText(msg);
        tvStatus.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacksAndMessages(null);
        mainHandler.postDelayed(() -> tvStatus.setVisibility(View.GONE), 4000);
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            showStatus("Не удалось открыть браузер");
        }
    }

    private static boolean isValidCfgName(String name) {
        return name.matches("[A-Za-z0-9_\\-]+\\.hcfg");
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private static int safeInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception ignored) { return def; }
    }

    // ─── inner helpers ────────────────────────────────────────────────────────

    @FunctionalInterface
    interface StringConsumer { void accept(String s); }

    private static final class SimpleTextWatcher implements TextWatcher {
        private final StringConsumer consumer;
        SimpleTextWatcher(StringConsumer c) { this.consumer = c; }
        @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
        @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
        @Override public void afterTextChanged(Editable e) { consumer.accept(e.toString()); }
    }

    private static final class SimpleSpinnerListener implements AdapterView.OnItemSelectedListener {
        private final Runnable r;
        SimpleSpinnerListener(Runnable r) { this.r = r; }
        @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { r.run(); }
        @Override public void onNothingSelected(AdapterView<?> p) {}
    }
}
