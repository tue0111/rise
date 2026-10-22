package com.tue.nightcam.record

import android.content.Context
import com.tue.nightcam.core.RecordingConfig

/** Lưu cấu hình bằng SharedPreferences, mỗi trường là một key chuỗi. */
class ConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Đọc tất cả giá trị, kể cả công tắc (Boolean) mà màn hình Cài đặt ghi vào. */
    fun load(): RecordingConfig =
        RecordingConfig.fromMap(prefs.all.mapValues { it.value?.toString() })

    fun save(config: RecordingConfig) {
        prefs.edit().apply {
            config.toMap().forEach { (key, value) ->
                if (key in BOOLEAN_KEYS) putBoolean(key, value.toBoolean()) else putString(key, value)
            }
        }.apply()
    }

    companion object {
        /** Tên này dùng chung với PreferenceManager ở màn hình Cài đặt. */
        const val PREFS = "nightcam_config"

        /** Các key lưu dạng Boolean, khớp với SwitchPreference. */
        val BOOLEAN_KEYS = setOf("audio", "eis", "ois", "thermal_downgrade")
    }
}
