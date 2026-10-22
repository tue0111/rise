package com.tue.nightcam.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import com.tue.nightcam.R
import com.tue.nightcam.record.ConfigStore

/**
 * Cài đặt cấu hình quay. Ghi cùng file SharedPreferences với ConfigStore.
 * Thay đổi áp dụng cho lần quay kế tiếp, không đổi giữa phiên đang chạy.
 */
class SettingsActivity : AppCompatActivity(R.layout.activity_settings) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Cài đặt"
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = ConfigStore.PREFS
            setPreferencesFromResource(R.xml.preferences, rootKey)
            // Chỉ nhận số nguyên trong khoảng hợp lệ; giá trị khác sẽ được rơi về mặc định khi đọc.
            listOf("video_mbps", "fps_floor", "segment_min", "reserve_gb").forEach { key ->
                (findPreference<EditTextPreference>(key))?.setOnBindEditTextListener { edit ->
                    edit.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }
            }
        }
    }
}
