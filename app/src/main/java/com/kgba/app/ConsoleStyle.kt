package com.kgba.app

import android.content.Context
import org.json.JSONObject

/** Coordinates are relative to the available play area, independently for each orientation. */
class ConsoleStyle {
    var shell = 0xFF2378D9.toInt()
    var buttons = 0xFFDADBD8.toInt()
    data class Placement(val x: Float, val y: Float, val scale: Float)
    val placements = mutableMapOf<String, Placement>()

    fun encode(): String = JSONObject().apply {
        put("shell", shell); put("buttons", buttons)
        put("placements", JSONObject().apply {
            placements.forEach { (key, value) -> put(key, JSONObject().apply {
                put("x", value.x); put("y", value.y); put("scale", value.scale)
            }) }
        })
    }.toString()

    fun save(context: Context) {
        context.getSharedPreferences("kgba", Context.MODE_PRIVATE).edit()
            .putString("console_style", encode()).apply()
    }

    companion object {
        fun load(context: Context) = decode(context.getSharedPreferences("kgba", Context.MODE_PRIVATE)
            .getString("console_style", null))

        fun decode(raw: String?): ConsoleStyle = ConsoleStyle().apply {
            if (raw == null) return@apply
            runCatching {
                val json = JSONObject(raw)
                shell = json.optInt("shell", shell)
                buttons = json.optInt("buttons", buttons)
                val values = json.optJSONObject("placements") ?: return@runCatching
                values.keys().forEach { key ->
                    val p = values.getJSONObject(key)
                    val x = p.getDouble("x").toFloat()
                    val y = p.getDouble("y").toFloat()
                    val scale = p.getDouble("scale").toFloat()
                    if (x.isFinite() && y.isFinite() && scale.isFinite())
                        placements[key] = Placement(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), scale.coerceIn(0.65f, 1.5f))
                }
            }
        }
    }
}
