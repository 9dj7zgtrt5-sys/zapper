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

/** On-TV settings (mainly a fallback — the phone page is easier). Reached by holding ☰ for 3 seconds. */
class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        ConfigServer.start(this)
        setContentView(R.layout.activity_setup)

        val m3u = findViewById<EditText>(R.id.m3uUrl)
        val epg = findViewById<EditText>(R.id.epgUrl)
        val autoStart = findViewById<CheckBox>(R.id.autoStart)
        val requireVpn = findViewById<CheckBox>(R.id.requireVpn)
        val reverse = findViewById<CheckBox>(R.id.reverseZap)
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
            if (m3u.text.isBlank()) {
                Toast.makeText(this, "Enter a playlist URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Repo.saveConfig(
                Repo.Settings(
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
