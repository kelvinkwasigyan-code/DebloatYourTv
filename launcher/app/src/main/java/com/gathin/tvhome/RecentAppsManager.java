package com.gathin.tvhome;

import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages tracking and querying of recently opened apps and closing them.
 */
public final class RecentAppsManager {
    private static final String PREF_NAME = "recent_apps_history";
    private static final String KEY_RECENTS = "recents_list";
    private static final int MAX_RECENTS = 20;

    public static final class RecentApp {
        public final String pkg;
        public final String label;
        public final Drawable icon;
        public final Intent launchIntent;
        public final long lastUsed;

        public RecentApp(String pkg, String label, Drawable icon, Intent launchIntent, long lastUsed) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
            this.launchIntent = launchIntent;
            this.lastUsed = lastUsed;
        }
    }

    private RecentAppsManager() {}

    /** Records that an app was opened. */
    public static synchronized void recordAppOpened(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty() || ctx == null) return;
        if (pkg.equals(ctx.getPackageName())) return;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String current = sp.getString(KEY_RECENTS, "");
            List<String> list = new ArrayList<>();
            if (!current.isEmpty()) {
                for (String p : current.split(",")) {
                    if (!p.isEmpty() && !p.equals(pkg)) list.add(p);
                }
            }
            list.add(0, pkg);
            while (list.size() > MAX_RECENTS) list.remove(list.size() - 1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(list.get(i));
            }
            sp.edit().putString(KEY_RECENTS, sb.toString())
                    .putLong("time_" + pkg, System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {}
    }

    /** Removes an app from the recorded recent list. */
    public static synchronized void removeApp(Context ctx, String pkg) {
        if (pkg == null || ctx == null) return;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String current = sp.getString(KEY_RECENTS, "");
            if (current.isEmpty()) return;
            List<String> list = new ArrayList<>();
            for (String p : current.split(",")) {
                if (!p.isEmpty() && !p.equals(pkg)) list.add(p);
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(list.get(i));
            }
            sp.edit().putString(KEY_RECENTS, sb.toString()).remove("time_" + pkg).apply();
        } catch (Throwable ignored) {}
    }

    /** Kills background processes of the package and removes it from recents. */
    public static boolean closeApp(Context ctx, String pkg) {
        if (ctx == null || pkg == null) return false;
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(pkg);
            }
            removeApp(ctx, pkg);
            return true;
        } catch (Throwable e) {
            removeApp(ctx, pkg);
            return false;
        }
    }

    /** Retrieves list of recent apps sorted by last used time. */
    public static List<RecentApp> getRecentApps(Context ctx) {
        if (ctx == null) return Collections.emptyList();
        PackageManager pm = ctx.getPackageManager();
        SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        Map<String, Long> timeMap = new HashMap<>();

        // 1. Query UsageStatsManager
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm != null) {
                long now = System.currentTimeMillis();
                long start = now - 7 * 24 * 3600 * 1000L;
                Map<String, UsageStats> agg = usm.queryAndAggregateUsageStats(start, now);
                if (agg != null && !agg.isEmpty()) {
                    for (Map.Entry<String, UsageStats> e : agg.entrySet()) {
                        long last = e.getValue().getLastTimeUsed();
                        if (last > 0) timeMap.put(e.getKey(), last);
                    }
                }
                List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, now);
                if (stats != null) {
                    for (UsageStats u : stats) {
                        long last = u.getLastTimeUsed();
                        if (last > 0) {
                            long curr = timeMap.getOrDefault(u.getPackageName(), 0L);
                            timeMap.put(u.getPackageName(), Math.max(last, curr));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 2. Read LastOpened preferences
        try {
            SharedPreferences lo = ctx.getSharedPreferences("last_opened", Context.MODE_PRIVATE);
            Map<String, ?> all = lo.getAll();
            if (all != null) {
                for (Map.Entry<String, ?> e : all.entrySet()) {
                    if (e.getValue() instanceof Number) {
                        long t = ((Number) e.getValue()).longValue();
                        long curr = timeMap.getOrDefault(e.getKey(), 0L);
                        timeMap.put(e.getKey(), Math.max(t, curr));
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 3. Overlay our recorded launch times
        String recorded = sp.getString(KEY_RECENTS, "");
        if (!recorded.isEmpty()) {
            for (String p : recorded.split(",")) {
                if (!p.isEmpty()) {
                    long t = sp.getLong("time_" + p, 0);
                    long current = timeMap.getOrDefault(p, 0L);
                    timeMap.put(p, Math.max(t, current));
                }
            }
        }

        // Exclude system background services and the launcher itself
        Set<String> excluded = new HashSet<>();
        excluded.add(ctx.getPackageName());
        excluded.add("android");
        excluded.add("com.android.systemui");
        excluded.add("com.google.android.apps.tv.dreamx");
        excluded.add("com.google.android.katniss");
        excluded.add("com.google.android.tungsten.setupwraith");
        excluded.add("com.google.android.tv.dfuservice");

        List<Map.Entry<String, Long>> sorted = new ArrayList<>(timeMap.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        List<RecentApp> result = new ArrayList<>();
        Set<String> added = new HashSet<>();

        for (Map.Entry<String, Long> entry : sorted) {
            String pkg = entry.getKey();
            if (excluded.contains(pkg) || added.contains(pkg)) continue;
            RecentApp app = resolveRecentApp(pm, pkg, entry.getValue());
            if (app != null) {
                result.add(app);
                added.add(pkg);
                if (result.size() >= MAX_RECENTS) break;
            }
        }

        // If recorded items are still missing, append them
        if (!recorded.isEmpty() && result.size() < MAX_RECENTS) {
            for (String p : recorded.split(",")) {
                if (!p.isEmpty() && !excluded.contains(p) && !added.contains(p)) {
                    RecentApp app = resolveRecentApp(pm, p, sp.getLong("time_" + p, 0));
                    if (app != null) {
                        result.add(app);
                        added.add(p);
                        if (result.size() >= MAX_RECENTS) break;
                    }
                }
            }
        }

        // Fallback: if user has no recent usage yet, seed with installed TV apps
        if (result.size() < 4) {
            try {
                List<ResolveInfo> installed = pm.queryIntentActivities(
                        new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
                if (installed != null) {
                    for (ResolveInfo ri : installed) {
                        if (ri.activityInfo != null) {
                            String p = ri.activityInfo.packageName;
                            if (!excluded.contains(p) && !added.contains(p)) {
                                RecentApp app = resolveRecentApp(pm, p, 0L);
                                if (app != null) {
                                    result.add(app);
                                    added.add(p);
                                    if (result.size() >= MAX_RECENTS) break;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        return result;
    }

    private static RecentApp resolveRecentApp(PackageManager pm, String pkg, long lastUsed) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg);
            List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
            if (list == null || list.isEmpty()) {
                intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
                list = pm.queryIntentActivities(intent, 0);
            }
            ResolveInfo info = (list != null && !list.isEmpty()) ? list.get(0) : null;
            Intent launch = null;
            if (info != null && info.activityInfo != null) {
                launch = new Intent(Intent.ACTION_MAIN);
                launch.setComponent(new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
                if (intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) {
                    launch.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            } else {
                launch = pm.getLaunchIntentForPackage(pkg);
                if (launch == null) return null;
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                List<ResolveInfo> resolved = pm.queryIntentActivities(launch, 0);
                if (resolved != null && !resolved.isEmpty()) {
                    info = resolved.get(0);
                }
            }

            CharSequence label = null;
            Drawable icon = null;
            if (info != null) {
                label = info.loadLabel(pm);
                if (info.activityInfo != null) icon = info.activityInfo.loadBanner(pm);
                if (icon == null) icon = info.loadIcon(pm);
            }
            if (label == null || label.length() == 0) {
                try {
                    label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                } catch (Throwable ignored) {}
            }
            if (label == null || label.length() == 0) label = pkg;
            if (icon == null) {
                try {
                    icon = pm.getApplicationIcon(pkg);
                } catch (Throwable ignored) {}
            }
            if (icon == null) return null;

            return new RecentApp(pkg, label.toString(), icon, launch, lastUsed);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Formats a timestamp into friendly relative time, e.g. "Just now", "5m ago", "2h ago". */
    public static String formatRelativeTime(long when) {
        if (when <= 0) return "Recent";
        long now = System.currentTimeMillis();
        long diff = now - when;
        if (diff < 60_000L) return "Just now";
        if (diff < 3600_000L) return (diff / 60_000L) + "m ago";
        if (diff < 86400_000L) return (diff / 3600_000L) + "h ago";
        return (diff / 86400_000L) + "d ago";
    }
}
