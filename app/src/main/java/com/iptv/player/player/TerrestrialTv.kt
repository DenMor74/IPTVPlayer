package com.iptv.player.player

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputManager

data class TerrTarget(val kind: String, val id: String, val label: String)

/** Ярлык «Эфирное ТВ»: запуск отдельного приложения телевизора либо переключение входа. */
object TerrestrialTv {

    fun apps(ctx: Context): List<TerrTarget> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { TerrTarget("app", it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.id != ctx.packageName }
            .distinctBy { it.id }
            .sortedBy { it.label.lowercase() }
    }

    fun inputs(ctx: Context): List<TerrTarget> = runCatching {
        val m = ctx.getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
        m.tvInputList.map { TerrTarget("input", it.id, it.loadLabel(ctx)?.toString() ?: it.id) }
    }.getOrDefault(emptyList())

    fun launch(ctx: Context, kind: String, id: String): Boolean = runCatching {
        val intent = when (kind) {
            "app" -> ctx.packageManager.getLeanbackLaunchIntentForPackage(id)
                ?: ctx.packageManager.getLaunchIntentForPackage(id)
            // для HDMI/AV-входов; встроенный тюнер на многих телевизорах запускается через приложение
            "input" -> Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))
            else -> null
        } ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    }.getOrDefault(false)
}
