package com.example.v2rayconfig.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.v2rayconfig.databinding.ActivityMainBinding
import com.example.v2rayconfig.model.ConfigParser
import com.example.v2rayconfig.model.ServerConfig
import com.example.v2rayconfig.model.TargetAppCatalog
import com.example.v2rayconfig.service.V2RayVpnService
import com.example.v2rayconfig.util.ConfigRepository
import com.example.v2rayconfig.util.FailoverSelector
import com.example.v2rayconfig.util.IpGeoChecker
import com.example.v2rayconfig.util.PingTester
import com.example.v2rayconfig.util.SmartAppTester
import com.example.v2rayconfig.util.SubscriptionManager
import com.example.v2rayconfig.util.XrayEnv
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: ConfigRepository
    private var pendingConfig: ServerConfig? = null
    private var latencies: Map<String, Long> = emptyMap()

    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val vpnPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                pendingConfig?.let { startVpnService(it) }
            } else {
                Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    /** Fired by V2RayVpnService when it auto-switches to a different server (health-check failover). */
    private val activeConfigChangedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val remark = intent?.getStringExtra(V2RayVpnService.EXTRA_NEW_CONFIG_REMARK)
            val reason = intent?.getStringExtra(V2RayVpnService.EXTRA_REASON)
            if (reason == "failover") {
                Toast.makeText(this@MainActivity, "Connection dropped — auto-switched to $remark", Toast.LENGTH_LONG).show()
            }
            refreshList()
            updateConnectionStatus()
            checkExitCountryDelayed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = ConfigRepository(this)
        XrayEnv.ensureInitialized(this)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        refreshList()

        binding.fabAdd.setOnClickListener { showAddConfigDialog() }

        binding.buttonDisconnect.setOnClickListener {
            startService(Intent(this, V2RayVpnService::class.java).apply {
                action = V2RayVpnService.ACTION_STOP
            })
            repo.setActive(null)
            binding.textExitCountry.visibility = android.view.View.GONE
            refreshList()
        }

        binding.buttonSubscription.setOnClickListener { showSubscriptionDialog() }
        binding.buttonRefresh.setOnClickListener { runFullRefreshFlow(userInitiated = true) }
        binding.buttonSmartTest.setOnClickListener { runSmartAppTest() }
        binding.buttonManageApps.setOnClickListener { showManageTargetAppsDialog() }

        // On every app open: if a subscription URL is configured, fetch it,
        // ping-test everything, and auto-connect to the best reachable one.
        runFullRefreshFlow(userInitiated = false)
    }

    // ---------- Subscription + ping-test + auto-connect pipeline ----------

    private fun runFullRefreshFlow(userInitiated: Boolean) {
        val subUrl = repo.getSubscriptionUrl()
        if (subUrl.isNullOrBlank()) {
            if (userInitiated) {
                Toast.makeText(this, "No subscription URL set yet. Tap 'Subscription' to add one.", Toast.LENGTH_LONG).show()
            }
            // No subscription configured — still ping-test whatever configs exist locally.
            runPingAndAutoConnect(userInitiated)
            return
        }

        if (userInitiated) Toast.makeText(this, "Fetching subscription...", Toast.LENGTH_SHORT).show()

        bgExecutor.submit {
            try {
                val fetched = SubscriptionManager.fetchAndParse(subUrl, useFragment = true)
                repo.replaceSubscriptionConfigs(fetched)
                mainHandler.post {
                    refreshList()
                    runPingAndAutoConnect(userInitiated)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(this, "Subscription update failed: ${e.message}", Toast.LENGTH_LONG).show()
                    // Still test/connect with whatever configs we already have saved.
                    runPingAndAutoConnect(userInitiated)
                }
            }
        }
    }

    private fun runPingAndAutoConnect(userInitiated: Boolean) {
        val all = repo.getAll()
        if (all.isEmpty()) return

        if (userInitiated) Toast.makeText(this, "Testing ${all.size} server(s)...", Toast.LENGTH_SHORT).show()

        bgExecutor.submit {
            try {
                val results = PingTester.testAll(this, all)
                mainHandler.post {
                    latencies = results
                    refreshList()

                    val reachableCount = results.values.count { it >= 0 }
                    if (userInitiated) {
                        Toast.makeText(this, "$reachableCount / ${all.size} servers reachable", Toast.LENGTH_SHORT).show()
                    }

                    if (repo.isAutoConnectEnabled()) {
                        val best = FailoverSelector.pickBestCandidate(this, all, repo, precomputedLatencies = results)
                        if (best != null && best.id != repo.getActiveId()) {
                            connect(best)
                        } else if (best == null && userInitiated) {
                            Toast.makeText(this, "No reachable server found.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (userInitiated) {
                        Toast.makeText(this, "Ping test failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // ---------- Smart per-app testing ----------

    private fun runSmartAppTest() {
        val configs = repo.getAll()
        if (configs.isEmpty()) {
            Toast.makeText(this, "Add at least one config first.", Toast.LENGTH_SHORT).show()
            return
        }

        val apps = repo.getActiveTargetApps()
        if (apps.isEmpty()) {
            Toast.makeText(this, "No target apps configured. Tap 'Manage Apps' first.", Toast.LENGTH_LONG).show()
            return
        }
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Smart testing...")
            .setMessage("Starting...")
            .setCancelable(true) // safety net: with per-test timeouts now in place this shouldn't hang, but let the user escape either way
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .create()
        progressDialog.show()

        bgExecutor.submit {
            try {
                val results = SmartAppTester.testAllConfigs(this, configs, apps) { index, total ->
                    mainHandler.post {
                        progressDialog.setMessage("Testing server $index of $total (this can take a few minutes)...")
                    }
                }
                val best = SmartAppTester.bestConfigPerApp(configs, apps, results)
                repo.saveSmartTestResults(results)

                mainHandler.post {
                    progressDialog.dismiss()
                    showSmartTestResults(configs, apps, results, best)
                }
            } catch (e: Exception) {
                // Whatever went wrong (engine init failure, unexpected native
                // exception, etc.) — never leave the dialog stuck on screen.
                mainHandler.post {
                    progressDialog.dismiss()
                    Toast.makeText(this, "Smart test failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showSmartTestResults(
        configs: List<ServerConfig>,
        apps: List<com.example.v2rayconfig.model.TargetApp>,
        results: Map<String, Map<String, Long>>,
        best: Map<String, Pair<ServerConfig, Long>?>
    ) {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val intro = android.widget.TextView(this).apply {
            text = "Tap an app to make failover optimize for it specifically — otherwise failover just uses generic ping."
            setPadding(0, 0, 0, 24)
        }
        container.addView(intro)

        val currentPriority = repo.getPriorityAppId()
        val radioGroup = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }

        val noneRadio = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "No priority app (use generic ping for failover)"
            isChecked = currentPriority == null
        }
        radioGroup.addView(noneRadio)

        val idByViewId = mutableMapOf<Int, String>()
        apps.forEach { app ->
            val result = best[app.id]
            val label = if (result == null) {
                "${app.displayName}: no working server found"
            } else {
                "${app.displayName} — best: ${result.first.remark} (${result.second}ms)"
            }
            val radio = android.widget.RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = label
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                isChecked = app.id == currentPriority
            }
            idByViewId[radio.id] = app.id
            radioGroup.addView(radio)

            // Full per-server breakdown for this app, fastest first, so the
            // person can see every server's speed, not just the winner.
            val ranked = configs
                .map { c -> c to (results[c.id]?.get(app.id) ?: -1L) }
                .sortedWith(compareBy { (_, latency) -> if (latency < 0) Long.MAX_VALUE else latency })

            ranked.forEach { (config, latency) ->
                val line = android.widget.TextView(this).apply {
                    text = if (latency >= 0) {
                        "   • ${config.remark} — ${latency}ms"
                    } else {
                        "   • ${config.remark} — timed out / unreachable"
                    }
                    setPadding(32, 4, 0, 4)
                    if (latency < 0) setTextColor(0xFF999999.toInt())
                }
                radioGroup.addView(line)
            }

            val spacer = android.widget.TextView(this).apply { text = ""; setPadding(0, 4, 0, 4) }
            radioGroup.addView(spacer)
        }
        container.addView(radioGroup)

        val scroll = android.widget.ScrollView(this).apply { addView(container) }

        AlertDialog.Builder(this)
            .setTitle("Speed per app, per server")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val checkedId = radioGroup.checkedRadioButtonId
                val newPriority = idByViewId[checkedId] // null if "None" was checked
                repo.setPriorityAppId(newPriority)
                val name = apps.find { it.id == newPriority }?.displayName
                Toast.makeText(
                    this,
                    if (newPriority != null) "Failover will now optimize for $name" else "Failover priority cleared",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Lets the user add custom sites to test, and toggle/remove built-in ones. */
    private fun showManageTargetAppsDialog() {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        val listContainer = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        container.addView(listContainer)

        fun rebuildList() {
            listContainer.removeAllViews()
            val disabled = repo.getDisabledDefaultAppIds()

            TargetAppCatalog.defaults.forEach { app ->
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val checkBox = android.widget.CheckBox(this).apply {
                    text = "${app.displayName}  (${app.testUrl})"
                    isChecked = app.id !in disabled
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnCheckedChangeListener { _, isChecked ->
                        repo.setDefaultAppEnabled(app.id, isChecked)
                    }
                }
                row.addView(checkBox)
                listContainer.addView(row)
            }

            repo.getCustomTargetApps().forEach { app ->
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val label = android.widget.TextView(this).apply {
                    text = "${app.displayName}  (${app.testUrl})"
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val deleteBtn = android.widget.ImageButton(this).apply {
                    setImageResource(android.R.drawable.ic_menu_delete)
                    background = null
                    setOnClickListener {
                        repo.removeCustomTargetApp(app.id)
                        rebuildList()
                    }
                }
                row.addView(label)
                row.addView(deleteBtn)
                listContainer.addView(row)
            }
        }
        rebuildList()

        val divider = android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 2
            ).apply { topMargin = 24; bottomMargin = 16 }
            setBackgroundColor(0xFFDDDDDD.toInt())
        }
        container.addView(divider)

        val nameInput = EditText(this).apply { hint = "Site name (e.g. My Bank)" }
        val urlInput = EditText(this).apply {
            hint = "https://example.com"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val addBtn = android.widget.Button(this).apply {
            text = "Add site"
            setOnClickListener {
                val name = nameInput.text.toString().trim()
                var url = urlInput.text.toString().trim()
                if (name.isBlank() || url.isBlank()) {
                    Toast.makeText(this@MainActivity, "Enter both a name and a URL.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
                repo.addCustomTargetApp(
                    com.example.v2rayconfig.model.TargetApp(
                        id = "custom_" + java.util.UUID.randomUUID().toString(),
                        displayName = name,
                        testUrl = url
                    )
                )
                nameInput.setText("")
                urlInput.setText("")
                rebuildList()
            }
        }
        container.addView(nameInput)
        container.addView(urlInput)
        container.addView(addBtn)

        val scroll = android.widget.ScrollView(this).apply { addView(container) }

        AlertDialog.Builder(this)
            .setTitle("Sites to test")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .show()
    }

    // ---------- List / config management ----------

    private fun refreshList() {
        val configs = repo.getAll()
        val activeId = repo.getActiveId()
        binding.recyclerView.adapter = ConfigListAdapter(
            configs,
            activeId,
            latencies,
            onClick = { config -> connect(config) },
            onDelete = { config ->
                repo.remove(config.id)
                if (repo.getActiveId() == config.id) repo.setActive(null)
                refreshList()
            }
        )
        binding.emptyState.visibility =
            if (configs.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showAddConfigDialog() {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val input = EditText(this).apply {
            hint = "Paste vmess://, vless://, or ss:// link"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val fragmentCheck = android.widget.CheckBox(this).apply {
            text = "Enable TLS fragmentation (helps against DPI, e.g. in Iran)"
            isChecked = true
        }
        container.addView(input)
        container.addView(fragmentCheck)

        AlertDialog.Builder(this)
            .setTitle("Add Config")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                try {
                    val config = ConfigParser.parse(input.text.toString(), fragmentCheck.isChecked)
                    repo.add(config)
                    refreshList()
                } catch (e: Exception) {
                    Toast.makeText(this, "Invalid link: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSubscriptionDialog() {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val input = EditText(this).apply {
            hint = "https://raw.githubusercontent.com/.../configs.txt"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(repo.getSubscriptionUrl() ?: "")
        }
        val autoConnectCheck = android.widget.CheckBox(this).apply {
            text = "Auto-connect to fastest reachable server on app open"
            isChecked = repo.isAutoConnectEnabled()
        }
        container.addView(input)
        container.addView(autoConnectCheck)

        AlertDialog.Builder(this)
            .setTitle("Subscription source")
            .setMessage(
                "Paste a raw text/GitHub link to a config list (one vmess/vless/ss link " +
                    "per line, or base64 of that). Only add a source you trust — whoever " +
                    "controls the servers in that list can see your proxied traffic."
            )
            .setView(container)
            .setPositiveButton("Save & Refresh") { _, _ ->
                val url = input.text.toString().trim()
                repo.setAutoConnectEnabled(autoConnectCheck.isChecked)
                if (url.isNotBlank()) {
                    repo.setSubscriptionUrl(url)
                    runFullRefreshFlow(userInitiated = true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connect(config: ServerConfig) {
        pendingConfig = config
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startVpnService(config)
        }
    }

    private fun startVpnService(config: ServerConfig) {
        startService(Intent(this, V2RayVpnService::class.java).apply {
            action = V2RayVpnService.ACTION_START
            putExtra(V2RayVpnService.EXTRA_CONFIG_JSON, config.xrayConfigJson)
            putExtra(V2RayVpnService.EXTRA_CONFIG_ID, config.id)
        })
        repo.setActive(config.id)
        refreshList()
        updateConnectionStatus()
        checkExitCountryDelayed()
    }

    /** Waits a moment for the tunnel to come up, then looks up the exit IP's country. */
    private fun checkExitCountryDelayed() {
        binding.textExitCountry.visibility = android.view.View.VISIBLE
        binding.textExitCountry.text = "Checking exit location..."
        mainHandler.postDelayed({
            bgExecutor.submit {
                try {
                    val result = IpGeoChecker.check()
                    mainHandler.post {
                        val flag = IpGeoChecker.flagEmoji(result.countryCode)
                        binding.textExitCountry.text = "$flag ${result.countryName} · ${result.ip}"
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        binding.textExitCountry.text = "Exit location unknown (${e.message})"
                    }
                }
            }
        }, 2500)
    }

    private fun updateConnectionStatus() {
        binding.textConnectionStatus.text = if (V2RayVpnService.isRunning) {
            "Connected — ${V2RayVpnService.activeConfigRemark ?: ""}"
        } else {
            "Disconnected"
        }
    }

    override fun onDestroy() {
        bgExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            activeConfigChangedReceiver,
            android.content.IntentFilter(V2RayVpnService.ACTION_ACTIVE_CONFIG_CHANGED)
        )
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(activeConfigChangedReceiver)
        super.onStop()
    }
}
