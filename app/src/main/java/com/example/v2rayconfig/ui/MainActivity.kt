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

    @Volatile private var pingInProgress = false

    private val pingExecutor = Executors.newSingleThreadExecutor()
    private val smartTestExecutor = Executors.newSingleThreadExecutor()
    private val geoExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val vpnPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                pendingConfig?.let { startVpnService(it) }
            } else {
                Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
            }
        }

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
        binding.buttonClearAll.setOnClickListener { confirmClearAll() }

        runFullRefreshFlow(userInitiated = false)
    }

    private fun runFullRefreshFlow(userInitiated: Boolean) {
        val subs = repo.getSubscriptions().filter { it.enabled }
        if (subs.isEmpty()) {
            if (userInitiated) {
                Toast.makeText(this, "No subscriptions added yet. Tap 'Subscription' to add one.", Toast.LENGTH_LONG).show()
            }
            runPingAndAutoConnect(userInitiated)
            return
        }

        if (userInitiated) Toast.makeText(this, "Fetching ${subs.size} subscription(s)...", Toast.LENGTH_SHORT).show()

        pingExecutor.submit {
            val allFetched = mutableListOf<ServerConfig>()
            var anyFailed = false
            for (sub in subs) {
                try {
                    allFetched.addAll(SubscriptionManager.fetchAndParseWithTimeout(sub.url, useFragment = true))
                } catch (e: Exception) {
                    anyFailed = true
                }
            }
            repo.replaceSubscriptionConfigs(allFetched)
            mainHandler.post {
                refreshList()
                if (anyFailed && userInitiated) {
                    Toast.makeText(this, "One or more subscriptions failed to update.", Toast.LENGTH_LONG).show()
                }
                if (userInitiated && allFetched.size > 100) {
                    Toast.makeText(
                        this,
                        "${allFetched.size} servers imported — Refresh+Ping and Smart Test will be slow with this many. Consider using fewer/smaller subscriptions.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                runPingAndAutoConnect(userInitiated)
            }
        }
    }

    private fun runPingAndAutoConnect(userInitiated: Boolean) {
        val all = repo.getAll()
        if (all.isEmpty()) return

        if (pingInProgress) {
            if (userInitiated) {
                Toast.makeText(this, "A test is already running — please wait for it to finish.", Toast.LENGTH_SHORT).show()
            }
            return
        }
        pingInProgress = true

        if (userInitiated) Toast.makeText(this, "Testing ${all.size} server(s)...", Toast.LENGTH_SHORT).show()

        pingExecutor.submit {
            try {
                val results = PingTester.testAll(this, all)
                mainHandler.post {
                    latencies = results

                    val sorted = all.sortedWith(
                        compareBy { c -> results[c.id]?.takeIf { it >= 0 } ?: Long.MAX_VALUE }
                    )
                    repo.save(sorted)

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
            } finally {
                pingInProgress = false
            }
        }
    }

    private fun runSmartAppTest() {
        val allConfigs = repo.getAll()
        if (allConfigs.isEmpty()) {
            Toast.makeText(this, "Add at least one config first.", Toast.LENGTH_SHORT).show()
            return
        }

        val apps = repo.getActiveTargetApps()
        if (apps.isEmpty()) {
            Toast.makeText(this, "No target apps configured. Tap 'Manage Apps' first.", Toast.LENGTH_LONG).show()
            return
        }

        val limit = 40
        if (allConfigs.size > limit) {
            AlertDialog.Builder(this)
                .setTitle("${allConfigs.size} servers is a lot")
                .setMessage(
                    "Testing all ${allConfigs.size} servers × ${apps.size} apps " +
                        "(${allConfigs.size * apps.size} checks) could take a long time, even in parallel. " +
                        "Test only the fastest $limit (by last ping result) instead, or continue with all of them?"
                )
                .setPositiveButton("Fastest $limit only") { _, _ ->
                    startSmartAppTest(allConfigs.take(limit), apps)
                }
                .setNegativeButton("Test all ${allConfigs.size}") { _, _ ->
                    startSmartAppTest(allConfigs, apps)
                }
                .show()
        } else {
            startSmartAppTest(allConfigs, apps)
        }
    }

    private fun startSmartAppTest(configs: List<ServerConfig>, apps: List<com.example.v2rayconfig.model.TargetApp>) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Smart testing...")
            .setMessage("Starting...")
            .setCancelable(true)
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .create()
        progressDialog.show()

        smartTestExecutor.submit {
            try {
                val results = SmartAppTester.testAllConfigs(this, configs, apps) { done, total ->
                    mainHandler.post {
                        progressDialog.setMessage("Testing... $done / $total checks done (${configs.size} servers × ${apps.size} apps)")
                    }
                }
                val best = SmartAppTester.bestConfigPerApp(configs, apps, results)
                repo.saveSmartTestResults(results)

                mainHandler.post {
                    progressDialog.dismiss()
                    showSmartTestResults(configs, apps, results, best)
                }
            } catch (e: Exception) {
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
                val newPriority = idByViewId[checkedId]
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
            },
            onEdit = { config -> showEditConfigDialog(config) },
            onShare = { config -> shareConfig(config) }
        )
        binding.emptyState.visibility =
            if (configs.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showEditConfigDialog(existing: ServerConfig) {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val input = EditText(this).apply {
            hint = "Paste vmess://, vless://, or ss:// link"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(existing.rawLink)
        }
        val fragmentCheck = android.widget.CheckBox(this).apply {
            text = "Enable TLS fragmentation (helps against DPI, e.g. in Iran)"
            isChecked = existing.useFragment
        }
        container.addView(input)
        container.addView(fragmentCheck)

        AlertDialog.Builder(this)
            .setTitle("Edit Config")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                try {
                    val updated = ConfigParser.parse(input.text.toString(), fragmentCheck.isChecked)
                    repo.update(existing.id, updated)
                    refreshList()
                } catch (e: Exception) {
                    Toast.makeText(this, "Invalid link: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareConfig(config: ServerConfig) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, config.rawLink)
            putExtra(Intent.EXTRA_SUBJECT, config.remark)
        }
        startActivity(Intent.createChooser(sendIntent, "Share config"))
    }

    private fun confirmClearAll() {
        val count = repo.getAll().size
        if (count == 0) {
            Toast.makeText(this, "No configs to clear.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Clear all configs?")
            .setMessage("This deletes all $count saved server(s), including manually-added ones. This can't be undone.")
            .setPositiveButton("Delete all") { _, _ ->
                startService(Intent(this, V2RayVpnService::class.java).apply {
                    action = V2RayVpnService.ACTION_STOP
                })
                repo.clearAll()
                latencies = emptyMap()
                binding.textExitCountry.visibility = android.view.View.GONE
                refreshList()
                updateConnectionStatus()
                Toast.makeText(this, "All configs deleted.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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

        val autoConnectCheck = android.widget.CheckBox(this).apply {
            text = "Auto-connect to fastest reachable server on app open"
            isChecked = repo.isAutoConnectEnabled()
            setOnCheckedChangeListener { _, isChecked -> repo.setAutoConnectEnabled(isChecked) }
        }
        container.addView(autoConnectCheck)

        val listLabel = android.widget.TextView(this).apply {
            text = "Your subscriptions:"
            setPadding(0, 24, 0, 8)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        container.addView(listLabel)

        val listContainer = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        container.addView(listContainer)

        fun rebuildList() {
            listContainer.removeAllViews()
            val subs = repo.getSubscriptions()
            if (subs.isEmpty()) {
                listContainer.addView(android.widget.TextView(this@MainActivity).apply {
                    text = "None yet — add one below."
                    setTextColor(0xFF888888.toInt())
                    setPadding(0, 8, 0, 8)
                })
            }
            subs.forEach { sub ->
                val row = android.widget.LinearLayout(this@MainActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, 8, 0, 8)
                }
                val enableCheck = android.widget.CheckBox(this@MainActivity).apply {
                    isChecked = sub.enabled
                    setOnCheckedChangeListener { _, isChecked -> repo.setSubscriptionEnabled(sub.id, isChecked) }
                }
                val label = android.widget.TextView(this@MainActivity).apply {
                    text = "${sub.name}\n${sub.url}"
                    textSize = 13f
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val editBtn = android.widget.ImageButton(this@MainActivity).apply {
                    setImageResource(android.R.drawable.ic_menu_edit)
                    background = null
                    setOnClickListener { showEditSubscriptionDialog(sub) { rebuildList() } }
                }
                val deleteBtn = android.widget.ImageButton(this@MainActivity).apply {
                    setImageResource(android.R.drawable.ic_menu_delete)
                    background = null
                    setOnClickListener {
                        repo.removeSubscription(sub.id)
                        rebuildList()
                    }
                }
                row.addView(enableCheck)
                row.addView(label)
                row.addView(editBtn)
                row.addView(deleteBtn)
                listContainer.addView(row)
            }
        }
        rebuildList()

        val divider1 = android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 2
            ).apply { topMargin = 16; bottomMargin = 16 }
            setBackgroundColor(0xFFDDDDDD.toInt())
        }
        container.addView(divider1)

        val addLabel = android.widget.TextView(this).apply {
            text = "Add a subscription:"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        container.addView(addLabel)

        val presetsLabel = android.widget.TextView(this).apply {
            text = "Quick picks (tap to fill in the fields below):"
            setPadding(0, 0, 0, 8)
            textSize = 13f
        }
        container.addView(presetsLabel)

        val nameInput = EditText(this).apply { hint = "Name (e.g. My Sub)" }
        val urlInput = EditText(this).apply {
            hint = "https://raw.githubusercontent.com/.../configs.txt"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }

        com.example.v2rayconfig.model.SubscriptionPresets.presets.forEach { preset ->
            val btn = android.widget.Button(this).apply {
                text = preset.name
                setOnClickListener {
                    nameInput.setText(preset.name)
                    urlInput.setText(preset.url)
                }
            }
            container.addView(btn)
        }

        container.addView(nameInput)
        container.addView(urlInput)

        val addBtn = android.widget.Button(this).apply {
            text = "Add subscription"
            setOnClickListener {
                val name = nameInput.text.toString().trim().ifBlank { "Subscription" }
                val url = urlInput.text.toString().trim()
                if (url.isBlank()) {
                    Toast.makeText(this@MainActivity, "Enter a URL first.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                repo.addSubscription(name, url)
                nameInput.setText("")
                urlInput.setText("")
                rebuildList()
            }
        }
        container.addView(addBtn)

        val warning = android.widget.TextView(this).apply {
            text = "Only add sources you trust — whoever controls the servers in a " +
                "subscription can see your proxied traffic. Quick picks are public, " +
                "widely-used aggregators, not something we personally vouch for."
            setPadding(0, 16, 0, 0)
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }
        container.addView(warning)

        val scroll = android.widget.ScrollView(this).apply { addView(container) }

        AlertDialog.Builder(this)
            .setTitle("Subscriptions")
            .setView(scroll)
            .setPositiveButton("Refresh now") { _, _ -> runFullRefreshFlow(userInitiated = true) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showEditSubscriptionDialog(sub: com.example.v2rayconfig.model.Subscription, onDone: () -> Unit) {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val nameInput = EditText(this).apply { setText(sub.name) }
        val urlInput = EditText(this).apply {
            setText(sub.url)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        container.addView(nameInput)
        container.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle("Edit subscription")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim().ifBlank { "Subscription" }
                val url = urlInput.text.toString().trim()
                if (url.isNotBlank()) {
                    repo.updateSubscription(sub.id, name, url)
                }
                onDone()
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

    private fun checkExitCountryDelayed() {
        binding.textExitCountry.visibility = android.view.View.VISIBLE
        binding.textExitCountry.text = "Checking exit location..."
        mainHandler.postDelayed({
            geoExecutor.submit {
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
        pingExecutor.shutdownNow()
        smartTestExecutor.shutdownNow()
        geoExecutor.shutdownNow()
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
