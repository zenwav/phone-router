package com.phonerouter.shim;

import android.content.Context;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Privileged helper run via:  su 1000 -c "CLASSPATH=/data/system/phonerouter_shim.jar app_process /system/bin com.phonerouter.shim.Main <cmd> [k=v ...]"
 *
 * Runs as the system uid (1000) so hidden/@SystemApi framework APIs and the
 * TETHER_PRIVILEGED / NETWORK_SETTINGS permissions are available. Everything is
 * reflective so the same binary works across Android versions.
 *
 * Commands:
 *   get-config                              -> JSON of current SoftAp config
 *   set-config ssid=.. pass=.. security=0..3 band=1|2 hidden=true maxclients=10
 *   tether-start                            -> start WiFi tethering (shares mobile data)
 *   tether-stop                             -> stop WiFi tethering
 *   block-client mac=AA:BB:CC:DD:EE:FF
 *   unblock-client mac=AA:BB:CC:DD:EE:FF
 *   data-usage start=.. end=..
 *
 * Output: a single JSON object on stdout. Process exit code 0 on success, 1 on error.
 */
public final class Main {

    private static final int TETHERING_WIFI = 0;

    // SoftApConfiguration security types
    private static final int SEC_OPEN = 0;
    private static final int SEC_WPA2 = 1;
    private static final int SEC_WPA3_TRANS = 2;
    private static final int SEC_WPA3 = 3;

    public static void main(String[] args) {
        try {
            if (args.length == 0) {
                fail("no command");
                return;
            }
            Map<String, String> kv = parseArgs(args);
            Context ctx = systemContext();
            String cmd = args[0];
            switch (cmd) {
                case "get-config":
                    getConfig(ctx);
                    break;
                case "set-config":
                    setConfig(ctx, kv);
                    break;
                case "tether-start":
                    tether(ctx, true);
                    break;
                case "tether-stop":
                    tether(ctx, false);
                    break;
                case "block-client":
                    blockClient(ctx, kv.get("mac"), true);
                    break;
                case "unblock-client":
                    blockClient(ctx, kv.get("mac"), false);
                    break;
                case "data-usage":
                    dataUsage(ctx, kv);
                    break;
                default:
                    fail("unknown command: " + cmd);
            }
        } catch (Throwable t) {
            fail(rootCause(t));
        }
    }

    // ---- framework bootstrap -------------------------------------------------

    private static Context systemContext() throws Exception {
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        return (Context) at.getMethod("getSystemContext").invoke(thread);
    }

    private static Object wifiManager(Context ctx) {
        return ctx.getSystemService(Context.WIFI_SERVICE);
    }

    private static Object tetheringManager(Context ctx) throws Exception {
        // Context.TETHERING_SERVICE = "tethering"
        Object tm = ctx.getSystemService("tethering");
        if (tm == null) throw new IllegalStateException("tethering service unavailable");
        return tm;
    }

    // ---- tethering -----------------------------------------------------------

    private static void tether(Context ctx, boolean start) throws Exception {
        Object tm = tetheringManager(ctx);
        if (!start) {
            Method stop = findMethod(tm.getClass(), "stopTethering", 1);
            stop.invoke(tm, TETHERING_WIFI);
            ok("{"ok":true,"action":"stop"}");
            return;
        }

        Class<?> reqCls = Class.forName("android.net.TetheringManager$TetheringRequest");
        Class<?> builderCls = Class.forName("android.net.TetheringManager$TetheringRequest$Builder");
        Constructor<?> bctor = builderCls.getConstructor(int.class);
        Object builder = bctor.newInstance(TETHERING_WIFI);
        Object request = builderCls.getMethod("build").invoke(builder);

        Class<?> cbCls = Class.forName("android.net.TetheringManager$StartTetheringCallback");
        final CountDownLatch latch = new CountDownLatch(1);
        final int[] errCode = {0};
        final boolean[] started = {false};
        InvocationHandler h = (proxy, method, margs) -> {
            String n = method.getName();
            if ("onTetheringStarted".equals(n)) {
                started[0] = true;
                latch.countDown();
            } else if ("onTetheringFailed".equals(n)) {
                errCode[0] = (margs != null && margs.length > 0 && margs[0] instanceof Integer)
                        ? (Integer) margs[0] : -1;
                latch.countDown();
            }
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) return false;
            if (rt == int.class) return 0;
            return null;
        };
        Object cb = Proxy.newProxyInstance(cbCls.getClassLoader(), new Class[]{cbCls}, h);
        Executor exec = Runnable::run;

        Method start3 = null;
        for (Method m : tm.getClass().getMethods()) {
            if (!m.getName().equals("startTethering")) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 3 && reqCls.isAssignableFrom(p[0])) { start3 = m; break; }
        }
        if (start3 == null) throw new NoSuchMethodException("startTethering(TetheringRequest,Executor,cb)");
        start3.invoke(tm, request, exec, cb);

        latch.await(20, TimeUnit.SECONDS);
        if (started[0]) {
            ok("{"ok":true,"action":"start"}");
        } else {
            fail("tethering failed, code=" + errCode[0]);
        }
    }

    // ---- SoftAp config -------------------------------------------------------

    private static Object currentConfig(Object wm) throws Exception {
        return wm.getClass().getMethod("getSoftApConfiguration").invoke(wm);
    }

    private static void getConfig(Context ctx) throws Exception {
        Object wm = wifiManager(ctx);
        Object cfg = currentConfig(wm);
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append(""ok":true");
        sb.append(","ssid":").append(jstr(readSsid(cfg)));
        sb.append(","passphrase":").append(jstr(readString(cfg, "getPassphrase")));
        sb.append(","security":").append(readInt(cfg, "getSecurityType", 1));
        sb.append(","band":").append(readInt(cfg, "getBand", 1));
        sb.append(","hidden":").append(readBool(cfg, "isHiddenSsid"));
        sb.append(","maxClients":").append(readInt(cfg, "getMaxNumberOfClients", 0));
        sb.append(","blocked":[");
        List<String> blocked = readBlocked(cfg);
        for (int i = 0; i < blocked.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(jstr(blocked.get(i)));
        }
        sb.append("]}");
        ok(sb.toString());
    }

    private static void setConfig(Context ctx, Map<String, String> kv) throws Exception {
        Object wm = wifiManager(ctx);
        Object current = currentConfig(wm);
        Object builder = newBuilderFrom(current);

        String ssid = decodeArg(kv, "ssid");
        int security = current != null ? readInt(current, "getSecurityType", SEC_WPA2) : SEC_WPA2;
        if (kv.containsKey("security")) security = Integer.parseInt(kv.get("security"));
        String passArg = decodeArg(kv, "pass");
        String pass = passArg != null ? passArg : readString(current, "getPassphrase");

        if (ssid != null) {
            builder.getClass().getMethod("setSsid", String.class).invoke(builder, ssid);
        }
        // passphrase + security always set together
        Method setPass = builder.getClass().getMethod("setPassphrase", String.class, int.class);
        if (security == SEC_OPEN) {
            setPass.invoke(builder, null, SEC_OPEN);
        } else {
            setPass.invoke(builder, pass, security);
        }
        if (kv.containsKey("band")) {
            builder.getClass().getMethod("setBand", int.class).invoke(builder, Integer.parseInt(kv.get("band")));
        }
        if (kv.containsKey("hidden")) {
            builder.getClass().getMethod("setHiddenSsid", boolean.class)
                    .invoke(builder, Boolean.parseBoolean(kv.get("hidden")));
        }
        if (kv.containsKey("maxclients")) {
            int mc = Integer.parseInt(kv.get("maxclients"));
            if (mc > 0) builder.getClass().getMethod("setMaxNumberOfClients", int.class).invoke(builder, mc);
        }

        Object cfg = builder.getClass().getMethod("build").invoke(builder);
        boolean r = (Boolean) wm.getClass().getMethod("setSoftApConfiguration",
                Class.forName("android.net.wifi.SoftApConfiguration")).invoke(wm, cfg);
        ok("{"ok":" + r + "}");
    }

    private static void blockClient(Context ctx, String mac, boolean block) throws Exception {
        if (mac == null || !mac.matches("^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$")) {
            fail("valid mac required");
            return;
        }
        Object wm = wifiManager(ctx);
        Object current = currentConfig(wm);
        Object builder = newBuilderFrom(current);

        Class<?> macCls = Class.forName("android.net.MacAddress");
        Method fromString = macCls.getMethod("fromString", String.class);

        List<Object> blocked = new ArrayList<>();
        // copy existing blocked macs
        for (String existing : readBlocked(current)) {
            if (block && existing.equalsIgnoreCase(mac)) continue; // avoid dup
            if (!block && existing.equalsIgnoreCase(mac)) continue; // removing
            blocked.add(fromString.invoke(null, existing));
        }
        if (block) blocked.add(fromString.invoke(null, mac));

        builder.getClass().getMethod("setClientControlByUserEnabled", boolean.class).invoke(builder, true);
        builder.getClass().getMethod("setBlockedClientList", List.class).invoke(builder, blocked);

        Object cfg = builder.getClass().getMethod("build").invoke(builder);
        boolean r = (Boolean) wm.getClass().getMethod("setSoftApConfiguration",
                Class.forName("android.net.wifi.SoftApConfiguration")).invoke(wm, cfg);
        ok("{"ok":" + r + ","mac":" + jstr(mac) + ","blocked":" + block + "}");
    }

    // ---- mobile data usage (NetworkStatsManager, system uid) -----------------

    private static void dataUsage(Context ctx, Map<String, String> kv) throws Exception {
        long start = parseLong(kv.get("start"), 0L);
        long end = parseLong(kv.get("end"), System.currentTimeMillis());
        Object nsm = ctx.getSystemService("netstats"); // NETWORK_STATS_SERVICE
        if (nsm == null) { fail("netstats unavailable"); return; }
        Class<?> nsmCls = Class.forName("android.app.usage.NetworkStatsManager");
        Method q = null;
        for (Method m : nsmCls.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("querySummaryForDevice") && p.length == 4 && p[0] == int.class) { q = m; break; }
        }
        if (q == null) throw new NoSuchMethodException("querySummaryForDevice");
        // ConnectivityManager.TYPE_MOBILE = 0; null subscriberId aggregates all mobile.
        Object bucket = q.invoke(nsm, 0, null, start, end);
        long rx = (Long) bucket.getClass().getMethod("getRxBytes").invoke(bucket);
        long tx = (Long) bucket.getClass().getMethod("getTxBytes").invoke(bucket);
        ok("{"ok":true,"rxBytes":" + rx + ","txBytes":" + tx + ","totalBytes":" + (rx + tx) + "}");
    }

    private static long parseLong(String s, long dflt) {
        if (s == null) return dflt;
        try { return Long.parseLong(s.trim()); } catch (Throwable t) { return dflt; }
    }

    private static Object newBuilderFrom(Object current) throws Exception {
        Class<?> builderCls = Class.forName("android.net.wifi.SoftApConfiguration$Builder");
        Class<?> sacCls = Class.forName("android.net.wifi.SoftApConfiguration");
        if (current != null) {
            try {
                return builderCls.getConstructor(sacCls).newInstance(current);
            } catch (NoSuchMethodException ignored) { }
        }
        return builderCls.getConstructor().newInstance();
    }

    // ---- reflective readers --------------------------------------------------

    private static String readSsid(Object cfg) {
        if (cfg == null) return null;
        try {
            Object s = cfg.getClass().getMethod("getSsid").invoke(cfg);
            if (s != null) return s.toString();
        } catch (Throwable ignored) { }
        try {
            Object w = cfg.getClass().getMethod("getWifiSsid").invoke(cfg);
            if (w != null) {
                String str = w.toString();
                // WifiSsid.toString() yields quoted UTF-8; strip surrounding quotes
                if (str.length() >= 2 && str.startsWith(""") && str.endsWith(""")) {
                    return str.substring(1, str.length() - 1);
                }
                return str;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static String readString(Object cfg, String method) {
        if (cfg == null) return null;
        try {
            Object v = cfg.getClass().getMethod(method).invoke(cfg);
            return v == null ? null : v.toString();
        } catch (Throwable t) { return null; }
    }

    private static int readInt(Object cfg, String method, int dflt) {
        if (cfg == null) return dflt;
        try {
            Object v = cfg.getClass().getMethod(method).invoke(cfg);
            return v == null ? dflt : (Integer) v;
        } catch (Throwable t) { return dflt; }
    }

    private static boolean readBool(Object cfg, String method) {
        if (cfg == null) return false;
        try {
            Object v = cfg.getClass().getMethod(method).invoke(cfg);
            return v != null && (Boolean) v;
        } catch (Throwable t) { return false; }
    }

    @SuppressWarnings("unchecked")
    private static List<String> readBlocked(Object cfg) {
        List<String> out = new ArrayList<>();
        if (cfg == null) return out;
        try {
            Object list = cfg.getClass().getMethod("getBlockedClientList").invoke(cfg);
            if (list instanceof List) {
                for (Object mac : (List<Object>) list) {
                    if (mac != null) out.add(mac.toString());
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    // ---- helpers -------------------------------------------------------------

    private static Method findMethod(Class<?> cls, String name, int paramCount) throws NoSuchMethodException {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == paramCount) return m;
        }
        throw new NoSuchMethodException(name + "/" + paramCount);
    }

    /** Returns kv[key], or base64-decoded kv[key+"b64"], or null. */
    private static String decodeArg(Map<String, String> kv, String key) {
        if (kv.containsKey(key)) return kv.get(key);
        String b64 = kv.get(key + "b64");
        if (b64 != null) {
            return new String(java.util.Base64.getDecoder().decode(b64), java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> kv = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq > 0) kv.put(args[i].substring(0, eq), args[i].substring(eq + 1));
        }
        return kv;
    }

    private static String jstr(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(""");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\""); break;
                case '\': b.append("\\"); break;
                case '
': b.append("\n"); break;
                case '': b.append("\r"); break;
                case '	': b.append("\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String msg = c.getClass().getSimpleName() + ": " + c.getMessage();
        return msg;
    }

    private static void ok(String json) {
        System.out.println(json);
        System.exit(0);
    }

    private static void fail(String msg) {
        System.out.println("{"ok":false,"error":" + jstr(msg) + "}");
        System.exit(1);
    }

    private Main() { }
}
