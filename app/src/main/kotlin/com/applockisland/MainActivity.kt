package com.applockisland

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.applockisland.xposed.hook.SecurityCenter.AppLockAuthHook

/**
 * 模块设置界面。
 *
 * 配置保存在 SharedPreferences 中，通过 LSPosed 的 RemotePreferences 机制
 * 同步到宿主进程（systemui / securitycenter）。
 *
 * 注意：SharedPreferences 名称必须与 ConfigManager.PREFS_NAME 一致。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("AppLockIslandPrefs", MODE_PRIVATE)

        setupSwitches()
        setupTestButton()
    }

    /** 发送测试广播给 SystemUI 中的模块接收器，验证超级岛链路 */
    private fun setupTestButton() {
        findViewById<Button>(R.id.btn_test_island).setOnClickListener {
            val intent = Intent(AppLockAuthHook.ACTION_APP_LOCK_TEST)
                .setPackage("com.android.systemui")
            sendBroadcast(intent)
            Toast.makeText(this, R.string.test_island_sent, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSwitches() {
        // 总开关
        setupSwitch(R.id.switch_enabled, "pref_app_lock_island_enabled", true)
        // 显示成功动画
        setupSwitch(R.id.switch_success_anim, "pref_app_lock_show_success_anim", true)
        // 隐藏原始对话框
        setupSwitch(R.id.switch_hide_dialog, "pref_app_lock_hide_original_dialog", false)
        // 成功状态保持
        setupSwitch(R.id.switch_keep_success, "pref_app_lock_keep_success", false)
    }

    private fun setupSwitch(switchId: Int, key: String, defValue: Boolean) {
        val switch = findViewById<Switch>(switchId)
        switch.isChecked = prefs.getBoolean(key, defValue)
        switch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(key, isChecked).apply()
        }
    }
}
