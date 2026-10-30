package com.phonerouter.hotspot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.phonerouter.hotspot.databinding.ActivityMainBinding
import com.phonerouter.hotspot.hotspot.HotspotManager
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var manager: HotspotManager
    private val io = Executors.newSingleThreadExecutor()
    private var suppressEvents = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        manager = HotspotManager(this)
        requestPermissions()
        HotspotService.start(this, autoStart = false)

        binding.etPort.setText(manager.prefs.webPort.toString())
        binding.etPassword.setText(manager.prefs.webPassword)
        binding.swAutostart.isChecked = manager.prefs.autoStartHotspot

        binding.swHotspot.setOnCheckedChangeListener { _, checked ->
            if (suppressEvents) return@setOnCheckedChangeListener
            setBusy("正在切换热点…")
            io.execute {
                manager.setHotspot(checked)
                runOnUiThread { refresh() }
            }
        }
        binding.swAutostart.setOnCheckedChangeListener { _, checked ->
            if (!suppressEvents) manager.prefs.autoStartHotspot = checked
        }

        binding.btnSaveWeb.setOnClickListener { saveWeb() }
        binding.btnOpenWeb.setOnClickListener { openWeb() }
        binding.btnInstallKsu.setOnClickListener { installKsu() }
        binding.btnRefresh.setOnClickListener { refresh() }

        refresh()
    }

    private fun refresh() {
        setBusy("刷新中…")
        io.execute {
            val rooted = manager.hasRoot()
            if (rooted) manager.init()
            val active = if (rooted) manager.isActive() else false
            val ssid = if (rooted) runCatching { manager.readConfig().ssid }.getOrNull() else null
            val port = manager.prefs.webPort
            runOnUiThread {
                suppressEvents = true
                binding.tvRoot.text = if (rooted) "Root: 已获取 (KernelSU)" else "Root: 不可用 — 请在管理器中授权"
                binding.tvStatus.text = "热点状态: ${if (active) "已开启" else "已关闭"}" +
                    (if (ssid != null) "   SSID: $ssid" else "")
                binding.tvWeb.text = "管理地址: http://${Prefs.GATEWAY}:$port"
                binding.swHotspot.isChecked = active
                binding.btnInstallKsu.isEnabled = rooted
                suppressEvents = false
            }
        }
    }

    private fun setBusy(msg: String) {
        binding.tvStatus.text = msg
    }

    private fun saveWeb() {
        val port = binding.etPort.text.toString().toIntOrNull()
        if (port == null || port !in 1024..65535) {
            toast("端口需在 1024-65535 之间")
            return
        }
        val newPw = binding.etPassword.text.toString().trim()
        if (newPw.length < 8) {
            toast("密码长度至少需 8 位")
            return
        }
        manager.prefs.webPort = port
        manager.prefs.webPassword = newPw
        // Recreate the service so the web server rebinds with the new settings.
        stopService(Intent(this, HotspotService::class.java))
        HotspotService.start(this, autoStart = false)
        toast("已保存，Web 服务重启中")
        binding.tvWeb.postDelayed({ refresh() }, 1500)
    }

    private fun openWeb() {
        val url = "http://${Prefs.GATEWAY}:${manager.prefs.webPort}"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            toast("无法打开浏览器")
        }
    }

    private fun installKsu() {
        setBusy("安装开机模块中…")
        io.execute {
            val ok = KsuModule.install()
            runOnUiThread {
                toast(if (ok) "开机自启模块已安装，重启后生效" else "安装失败，请确认 KernelSU 已授权")
                refresh()
            }
        }
    }

    private fun requestPermissions() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
