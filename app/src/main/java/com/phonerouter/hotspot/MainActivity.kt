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
            setBusy(getString(R.string.switching_hotspot))
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
        setBusy(getString(R.string.refreshing))
        io.execute {
            val rooted = manager.hasRoot()
            if (rooted) manager.init()
            val active = if (rooted) manager.isActive() else false
            val ssid = if (rooted) runCatching { manager.readConfig().ssid }.getOrNull() else null
            val port = manager.prefs.webPort
            runOnUiThread {
                suppressEvents = true
                binding.tvRoot.text = getString(
                    if (rooted) R.string.root_granted else R.string.root_unavailable
                )
                val state = getString(if (active) R.string.hotspot_on else R.string.hotspot_off)
                binding.tvStatus.text = if (ssid != null) {
                    getString(R.string.hotspot_status_with_ssid, state, ssid)
                } else {
                    getString(R.string.hotspot_status_format, state)
                }
                binding.tvWeb.text = getString(R.string.management_address_format, Prefs.GATEWAY, port)
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
            toast(getString(R.string.port_range_error))
            return
        }
        val newPw = binding.etPassword.text.toString().trim()
        if (newPw.length < 8) {
            toast(getString(R.string.password_too_short))
            return
        }
        manager.prefs.webPort = port
        manager.prefs.webPassword = newPw
        // Recreate the service so the web server rebinds with the new settings.
        stopService(Intent(this, HotspotService::class.java))
        HotspotService.start(this, autoStart = false)
        toast(getString(R.string.saved_restarting_web))
        binding.tvWeb.postDelayed({ refresh() }, 1500)
    }

    private fun openWeb() {
        val url = "http://${Prefs.GATEWAY}:${manager.prefs.webPort}"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            toast(getString(R.string.browser_unavailable))
        }
    }

    private fun installKsu() {
        setBusy(getString(R.string.installing_boot_module))
        io.execute {
            val ok = KsuModule.install()
            runOnUiThread {
                toast(getString(if (ok) R.string.boot_module_installed else R.string.boot_module_failed))
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
