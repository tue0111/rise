package com.tue.nightcam.record

import android.content.Context
import com.tue.nightcam.core.RecordingConfig

/** Lưu cấu hình bằng SharedPreferences, mỗi trường là một key chuỗi. */
class ConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): RecordingConfig =
        RecordingConfig.fromMap(prefs.all.mapValues { it.value as? String })

    fun save(config: RecordingConfig) {
        prefs.edit().apply {
            config.toMap().forEach { (key, value) -> putString(key, value) }
        }.apply()
    }

    companion object {
        private const val PREFS = "nightcam_config"
    }
}
