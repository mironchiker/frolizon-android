package dev.horizon.android;

import android.content.Context;

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
 * HcfgManager — low-level .hcfg file system.
 *
 * .hcfg format:
 *   # comment lines ignored
 *   key = value
 *   key=value        (spaces around '=' optional)
 *   key =            (empty value allowed)
 *
 * All file I/O is synchronous. Call from background thread in production.
 * Writes use write-to-partial → atomic rename to prevent corruption on
 * interrupted writes (power loss, force-stop).
 *
 * Config directory: Context.getFilesDir()/hcfg/
 * Default config  : default.hcfg  (auto-created if missing)
 */
public final class HcfgManager {

    // ── public constants ─────────────────────────────────────────────────────
    public static final String DIR_NAME     = "hcfg";
    public static final String DEFAULT_NAME = "default.hcfg";
    public static final String EXT          = ".hcfg";

    /** Standard keys the Frolizon launcher recognises in .hcfg files. */
    public static final class Key {
        public static final String RENDERER      = "renderer";
        public static final String FPS_LIMIT     = "fps_limit";
        public static final String RENDER_SCALE  = "render_scale";
        public static final String PERF_HINTS    = "perf_hints";
        public static final String GPU_PROFILE   = "gpu_profile";
        public static final String RECORD_PERF   = "record_perf";
        public static final String PLAYER_NAME   = "player_name";
        public static final String SERVER        = "server";
        public static final String CUSTOM_ARGS   = "extra_args";
        public static final String WORKER_LIMIT  = "worker_limit";
        public static final String CACHE_MIB     = "cache_budget_mib";
        public static final String ORIENTATION   = "orientation";
        public static final String MOD_SOURCE    = "mod_source";
        public static final String SHADER_SOURCE = "shader_source";
        private Key() {}
    }

    // ── fields ───────────────────────────────────────────────────────────────
    private final File dir;

    // ── constructors ─────────────────────────────────────────────────────────

    public HcfgManager(Context ctx) {
        this(new File(ctx.getFilesDir(), DIR_NAME));
    }

    public HcfgManager(File dir) {
        this.dir = dir;
        if (!dir.exists()) dir.mkdirs();
    }

    // ── public API ───────────────────────────────────────────────────────────

    /** List all .hcfg file names, sorted. Never null, at least one entry. */
    public List<String> list() {
        File[] files = dir.listFiles((d, n) -> n.endsWith(EXT));
        List<String> out = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) out.add(f.getName());
        }
        if (out.isEmpty()) {
            // Bootstrap default
            try { write(DEFAULT_NAME, defaults()); } catch (IOException ignored) {}
            out.add(DEFAULT_NAME);
        }
        return out;
    }

    /** Load a named config. Returns defaults if file is missing or corrupt. */
    public Map<String, String> load(String name) throws IOException {
        File f = file(name);
        if (!f.exists()) {
            Map<String, String> d = defaults();
            write(name, d);
            return d;
        }
        return parse(f);
    }

    /** Save a named config atomically. Creates the file if it doesn't exist. */
    public void save(String name, Map<String, String> values) throws IOException {
        write(name, values);
    }

    /**
     * Create a new config with default values.
     * @throws IllegalArgumentException if name is invalid or already exists
     */
    public void create(String name) throws IOException {
        if (!isValidName(name)) throw new IllegalArgumentException("Invalid name: " + name);
        File f = file(name);
        if (f.exists()) throw new IllegalArgumentException("Already exists: " + name);
        write(name, defaults());
    }

    /**
     * Duplicate src as dst.
     * @throws IOException on I/O failure
     * @throws IllegalArgumentException if dst already exists
     */
    public void duplicate(String src, String dst) throws IOException {
        if (!isValidName(dst)) throw new IllegalArgumentException("Invalid name: " + dst);
        File fdst = file(dst);
        if (fdst.exists()) throw new IllegalArgumentException("Already exists: " + dst);
        Map<String, String> vals = load(src);
        write(dst, vals);
    }

    /**
     * Delete a config. Returns false if file did not exist.
     * @throws IllegalStateException if deleting the only remaining config
     */
    public boolean delete(String name) {
        if (list().size() == 1 && list().contains(name)) {
            throw new IllegalStateException("Cannot delete the last config");
        }
        return file(name).delete();
    }

    /** Rename src to dst. Active-config tracking is the caller's responsibility. */
    public boolean rename(String src, String dst) throws IOException {
        if (!isValidName(dst)) throw new IllegalArgumentException("Invalid name: " + dst);
        File fsrc = file(src);
        File fdst = file(dst);
        if (!fsrc.exists()) throw new IOException("Source not found: " + src);
        if (fdst.exists())  throw new IOException("Destination exists: " + dst);
        return fsrc.renameTo(fdst);
    }

    /** Convenience: get a single value from a loaded config. */
    public static String get(Map<String, String> cfg, String key, String fallback) {
        String v = cfg.get(key);
        return (v != null && !v.isEmpty()) ? v : fallback;
    }

    public static boolean getBool(Map<String, String> cfg, String key, boolean fallback) {
        String v = cfg.get(key);
        if (v == null) return fallback;
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
    }

    public static int getInt(Map<String, String> cfg, String key, int fallback) {
        try { return Integer.parseInt(cfg.getOrDefault(key, "")); }
        catch (NumberFormatException e) { return fallback; }
    }

    public static float getFloat(Map<String, String> cfg, String key, float fallback) {
        try { return Float.parseFloat(cfg.getOrDefault(key, "")); }
        catch (NumberFormatException e) { return fallback; }
    }

    /** Return the on-disk File for a config name. */
    public File file(String name) { return new File(dir, name); }

    /** Validate .hcfg file name: alphanumeric, underscores, hyphens, ends in .hcfg. */
    public static boolean isValidName(String name) {
        return name != null && name.matches("[A-Za-z0-9_\\-]+\\.hcfg")
                && name.length() <= 64;
    }

    // ── internal ─────────────────────────────────────────────────────────────

    /** Parse key=value lines from a .hcfg file, preserving insertion order. */
    private static Map<String, String> parse(File f) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                // strip inline comment
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash);
                line = line.trim();
                if (line.isEmpty()) continue;
                int eq = line.indexOf('=');
                if (eq < 1) continue;
                String key   = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (!key.isEmpty()) map.put(key, value);
            }
        }
        return map;
    }

    /**
     * Atomically write a .hcfg file.
     * Strategy: write .partial alongside target, then rename.
     * If rename fails (different filesystem mount points on some OEMs),
     * fall back to direct overwrite.
     */
    private void write(String name, Map<String, String> values) throws IOException {
        File target  = file(name);
        File partial = new File(dir, name + ".partial");

        String content = serialise(name, values);
        try (FileWriter w = new FileWriter(partial)) {
            w.write(content);
        }
        if (!partial.renameTo(target)) {
            partial.delete();
            try (FileWriter w = new FileWriter(target)) {
                w.write(content);
            }
        }
    }

    /** Serialise map to .hcfg text. */
    private static String serialise(String name, Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Frolizon config: ").append(name).append('\n');
        sb.append("# Managed by FrolizonLauncherActivity — safe to hand-edit\n");
        sb.append('\n');
        for (Map.Entry<String, String> e : values.entrySet()) {
            sb.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    /** Factory: default key-value set shipped with every new config. */
    public static Map<String, String> defaults() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(Key.RENDERER,      "vulkan-compatibility");
        m.put(Key.FPS_LIMIT,     "0");
        m.put(Key.RENDER_SCALE,  "1.0");
        m.put(Key.PERF_HINTS,    "false");
        m.put(Key.GPU_PROFILE,   "false");
        m.put(Key.RECORD_PERF,   "false");
        m.put(Key.PLAYER_NAME,   "");
        m.put(Key.SERVER,        "");
        m.put(Key.CUSTOM_ARGS,   "");
        m.put(Key.WORKER_LIMIT,  "0");
        m.put(Key.CACHE_MIB,     "512");
        m.put(Key.ORIENTATION,   "landscape");
        m.put(Key.MOD_SOURCE,    "horizon");
        m.put(Key.SHADER_SOURCE, "horizon");
        return m;
    }
}
