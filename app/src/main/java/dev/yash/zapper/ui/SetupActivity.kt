package dev.yash.zapper.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import dev.yash.zapper.R
import dev.yash.zapper.WakeService
import dev.yash.zapper.data.ConfigServer
import dev.yash.zapper.data.Repo
import dev.yash.zapper.data.StalkerClient

/** On-TV settings (mainly a fallback — the phone page is easier). Reached by holding ☰ for 3 seconds. */
class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        ConfigServer.start(this)
        setContentView(R.layout.activity_setup)
        Fonts.apply(window.decorView)

        val portal = findViewById<EditText>(R.id.portalUrl)
        val mac = findViewById<EditText>(R.id.macAddress)
        val m3u = findViewById<EditText>(R.id.m3uUrl)
        val epg = findViewById<EditText>(R.id.epgUrl)
        val autoStart = findViewById<CheckBox>(R.id.autoStart)
        val requireVpn = findViewById<CheckBox>(R.id.requireVpn)
        val reverse = findViewById<CheckBox>(R.id.reverseZap)
        portal.setText(Repo.portalUrl)
        mac.setText(Repo.mac)
        m3u.setText(Repo.m3uUrl)
        epg.setText(Repo.epgUrl)
        autoStart.isChecked = Repo.autoStart
        requireVpn.isChecked = Repo.requireVpn
        reverse.isChecked = Repo.reverseZap

        val addr = ConfigServer.address()
        findViewById<TextView>(R.id.phoneHint).text = if (addr != null)
            "Easier: on a phone on the same Wi-Fi, open\n\n$addr\n\nto paste links, hide categories and more."
        else "Connect the TV to Wi-Fi to set up from your phone."

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            val usePortal = portal.text.isNotBlank()
            if (usePortal && !StalkerClient.isValidMac(mac.text.toString())) {
                Toast.makeText(this, "Enter the MAC like 00:1A:79:12:34:56", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (usePortal && portal.text.isBlank()) {
                Toast.makeText(this, "Enter the portal URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!usePortal && m3u.text.isBlank()) {
                Toast.makeText(this, "Enter a portal URL, or a playlist URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Repo.saveConfig(
                Repo.Settings(
                    source = if (usePortal) "portal" else "m3u",
                    portal = portal.text.toString(),
                    mac = mac.text.toString(),
                    m3u = m3u.text.toString(),
                    epg = epg.text.toString(),
                    autoStart = autoStart.isChecked,
                    requireVpn = requireVpn.isChecked,
                    reverse = reverse.isChecked,
                    forceReload = true,
                )
            )
            if (Repo.autoStart) WakeService.start(this) else WakeService.stop(this)
            finish()
        }
        findViewById<Button>(R.id.cancelButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.newMacButton).setOnClickListener {
            mac.setText(Repo.generateMac())
            Toast.makeText(this, "New MAC created. Press Save, then give it to your provider.", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.saveButton).requestFocus()
    }

    override fun onResume() {
        super.onResume()
        ConfigServer.listener = {
            Toast.makeText(this, "Settings received from your phone", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        ConfigServer.listener = null
    }
}
