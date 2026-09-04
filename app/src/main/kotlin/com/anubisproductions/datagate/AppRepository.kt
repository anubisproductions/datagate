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
            val isProtected = pkg in guarded

            out += AppEntry(
                packageName = pkg,
                label = label,
                icon = icon,
                isProtected = isProtected,
                protectedReason = if (isProtected) Protected.reasonFor(pkg) else "",
            )
        }

        return out.sortedWith(
            compareBy<AppEntry> { it.isProtected }.thenBy { it.label.lowercase() }
        )
    }
}
