package com.phonerouter.hotspot

import com.phonerouter.hotspot.root.RootShell

/**
 * Installs a KernelSU boot module that relaunches the hotspot service at boot. This is
 * the reliable autostart path on MIUI/HyperOS, which often blocks ordinary app
 * BOOT_COMPLETED receivers. Writes straight into /data/adb/modules (active next boot).
 */
object KsuModule {

    private const val ID = "phonerouter_boot"
    private const val DIR = "/data/adb/modules/$ID"

    fun isInstalled(): Boolean =
        RootShell.run("test -f $DIR/service.sh && echo yes").out.contains("yes")

    fun install(): Boolean {
        val cmds = listOf(
            "mkdir -p $DIR",
            "cat > $DIR/module.prop <<'PROP'",
            "id=$ID",
            "name=Phone Router Hotspot Autostart",
            "version=1.0",
            "versionCode=1",
            "author=phone-router",
            "description=Starts the hotspot management service automatically at boot",
            "PROP",
            "cat > $DIR/service.sh <<'SH'",
            "#!/system/bin/sh",
            "# Wait for boot to complete, then start the hotspot service.",
            "until [ \"\$(getprop sys.boot_completed)\" = \"1\" ]; do sleep 2; done",
            "sleep 8",
            "am start-foreground-service -n com.phonerouter.hotspot/.HotspotService --ez autostart true",
            "SH",
            "chmod 0755 $DIR/service.sh",
            "chmod 0644 $DIR/module.prop",
            "test -f $DIR/service.sh && echo INSTALLED",
        )
        return RootShell.run(cmds).out.contains("INSTALLED")
    }

    fun uninstall(): Boolean {
        return RootShell.run("rm -rf $DIR && echo REMOVED").out.contains("REMOVED")
    }
}
