package com.iptv.player.player

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

data class TerrTarget(val kind: String, val id: String, val label: String)

/** Ярлык «Эфирное ТВ»: запуск отдельного приложения телевизора либо переключение входа. */
object TerrestrialTv {

    fun apps(ctx: Context): List<TerrTarget> {
        val pm = ctx.packageManager
        val leanback = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0
        )
        val launcher = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        return (leanback + launcher)
            .map { TerrTarget("app", it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.id != ctx.packageName }
            .distinctBy { it.id }
            .sortedBy { it.label.lowercase() }
    }

    fun inputs(ctx: Context): List<TerrTarget> = runCatching {
        val m = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        m.tvInputList.map { info ->
            val userLabel = info.loadLabel(ctx)?.toString()
            val typeLabel = when (info.type) {
                TvInputInfo.TYPE_HDMI -> "HDMI"
                TvInputInfo.TYPE_TUNER -> "Эфирный тюнер"
                TvInputInfo.TYPE_COMPONENT -> "Компонент"
                TvInputInfo.TYPE_COMPOSITE -> "AV / Композит"
                TvInputInfo.TYPE_VGA -> "VGA"
                else -> "Вход"
            }
            val title = if (!userLabel.isNull_or_blank() && userLabel != info.id) userLabel!! else "$typeLabel (${info.id})"
            TerrTarget("input", info.id, title)
        }
    }.getOrDefault(emptyList())

    private fun String?.isNull_or_blank(): Boolean = this == null || this.trim().isEmpty()

    fun launch(ctx: Context, kind: String, id: String): Boolean = runCatching {
        val intent = when (kind) {
            "app" -> ctx.packageManager.getLeanbackLaunchIntentForPackage(id)
                ?: ctx.packageManager.getLaunchIntentForPackage(id)
            "input" -> Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))
            else -> null
        } ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    }.getOrDefault(false)
}
