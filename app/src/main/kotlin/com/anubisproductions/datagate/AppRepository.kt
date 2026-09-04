package com.anubisproductions.datagate

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isProtected: Boolean,
    val protectedReason: String,
)

object AppRepository {

    /**
     * Every app with a launcher entry, protected ones last.
     *
     * Filtering on "has a launcher intent" rather than "is a third-party app" matters:
     * on this device YouTube is preinstalled, so a third-party-only list leaves out one
     * of the apps most people actually want to restrict.
     */
    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val guarded = Protected.setFor(ctx)

        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)

        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>(resolved.size)

        for (info in resolved) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (!seen.add(pkg)) continue

            val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()
                ?: runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
                ?: pkg
            val icon = runCatching { info.loadIcon(pm) }.getOrNull()
            val isProtected = pkg in guarded || Protected.isSystemUid(pm, pkg)

            out += AppEntry(
                packageName = pkg,
                label = label,
                icon = icon,
                isProtected = isProtected,
                protectedReason = if (isProtected) Protected.reasonFor(ctx, pkg) else "",
            )
        }

        /*
         * Protected apps with no launcher entry - Play Services above all - are invisible
         * to the query above, so the guard was doing its job silently. That is backwards:
         * Play Services is the one entry that explains why the list is guarded at all, and
         * a user who cannot see it has no reason to believe their notifications are safe.
         * Listed here explicitly, disabled, with the reason on the row.
         */
        for (pkg in guarded) {
            if (pkg in seen) continue
            val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
            if (!appInfo.enabled) continue
            seen += pkg

            out += AppEntry(
                packageName = pkg,
                label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrNull() ?: pkg,
                icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull(),
                isProtected = true,
                protectedReason = Protected.reasonFor(ctx, pkg),
            )
        }

        return out.sortedWith(
            compareBy<AppEntry> { it.isProtected }.thenBy { it.label.lowercase() }
        )
    }
}
