package com.anubisproductions.datagate

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Measure, then block, in one place.
 *
 * Every competitor does one or the other. The monitors (GlassWire, My Data Manager) show
 * you the numbers and cannot act on them; the firewalls (NetGuard, the clones) let you
 * block an app but will never tell you which one is the problem. The whole point of this
 * screen is that the ranking and the switch are the same row.
 */
class MainActivity : Activity() {

    private companion object {
        const val REQUEST_CONSENT = 1
        const val REQUEST_NOTIFY = 2
        const val FILTER_ALL = 0
        const val FILTER_MOBILE = 1
        const val FILTER_BACKGROUND = 2
    }

    private lateinit var onboarding: LinearLayout
    private lateinit var dashboard: LinearLayout
    private lateinit var cycleUsed: TextView
    private lateinit var cycleSub: TextView
    private lateinit var bundleBar: ProgressBar
    private lateinit var master: Switch
    private lateinit var masterState: TextView
    private lateinit var backgroundTotal: TextView
    private lateinit var savedTotal: TextView
    private lateinit var filter: Spinner
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var bulk: Button

    private var report: UsageReport? = null
    private var shown: List<AppUsage> = emptyList()
    private var blocked: MutableSet<String> = HashSet()
    private var filterMode = FILTER_ALL

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.row_usage, parent, false)
            val a = shown[position]

            view.findViewById<ImageView>(R.id.icon).setImageDrawable(a.icon)
            view.findViewById<TextView>(R.id.label).text = a.label
            view.findViewById<TextView>(R.id.total).text = UsageRepository.formatBytes(a.total)

            val bar = view.findViewById<ProgressBar>(R.id.bg_bar)
            bar.progress = (a.backgroundShare * 100).toInt()

            val detail = view.findViewById<TextView>(R.id.detail)
            val toggle = view.findViewById<Switch>(R.id.toggle)

            if (a.isProtected) {
                toggle.isChecked = false
                toggle.isEnabled = false
                detail.text = getString(R.string.protected_app)
                view.alpha = 0.6f
            } else {
                toggle.isEnabled = true
                toggle.isChecked = a.packageName in blocked
                val bg = UsageRepository.formatBytes(a.background)
                val mob = UsageRepository.formatBytes(a.mobile)

                // A rule is not the same thing as enforcement. Saying "Restricted" while
                // the engine is down - consent refused, another VPN holding the slot,
                // establish() failing - is the app lying about what it is doing, and it is
                // the complaint that earns one-star reviews in this category. Savings are
                // hidden for the same reason: they would be derived from a false premise.
                val engineUp = BlockVpnService.isRunning
                detail.text = if (a.packageName in blocked) {
                    if (!engineUp) {
                        getString(R.string.restricted_inactive)
                    } else {
                        val saved = Budget.estimatedSavedFor(this@MainActivity, a.packageName)
                        if (saved > 0) {
                            getString(R.string.row_restricted_saved,
                                getString(R.string.restricted),
                                UsageRepository.formatBytes(saved))
                        } else {
                            getString(R.string.restricted)
                        }
                    }
                } else {
                    getString(R.string.row_usage, mob, bg)
                }
                view.alpha = 1f
            }
            return view
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The launch theme carries the splash; swap to the real theme before inflating or
        // the splash background stays behind the content.
        setTheme(R.style.Theme_DataGate)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        onboarding = findViewById(R.id.onboarding)
        dashboard = findViewById(R.id.dashboard)
        cycleUsed = findViewById(R.id.cycle_used)
        cycleSub = findViewById(R.id.cycle_sub)
        bundleBar = findViewById(R.id.bundle_bar)
        master = findViewById(R.id.master)
        masterState = findViewById(R.id.master_state)
        backgroundTotal = findViewById(R.id.background_total)
        savedTotal = findViewById(R.id.saved_total)
        filter = findViewById(R.id.filter)
        search = findViewById(R.id.search)
        list = findViewById(R.id.list)
        bulk = findViewById(R.id.bulk)

        blocked = HashSet(Rules.blockedAny(this))

        findViewById<Button>(R.id.grant).setOnClickListener { openUsageAccess() }
        findViewById<View>(R.id.cycle_block).setOnClickListener { askBundle() }
        bulk.setOnClickListener { restrictWorstOffenders() }
        findViewById<Button>(R.id.open_blocking).setOnClickListener {
            startActivity(Intent(this, BlockingActivity::class.java))
        }

        setUpFilter()
        setUpList()
        setUpMaster()
    }

    override fun onResume() {
        super.onResume()
        // Re-render whenever the engine starts or stops, rather than only when we happen
        // to be drawing. Posted to the main thread: the callback fires from the service.
        BlockVpnService.onStateChange = {
            Handler(Looper.getMainLooper()).post {
                report?.let { renderHeader(it) }
                adapter.notifyDataSetChanged()
            }
        }
        if (UsageRepository.hasAccess(this)) {
            onboarding.visibility = View.GONE
            dashboard.visibility = View.VISIBLE
            reload()
        } else {
            onboarding.visibility = View.VISIBLE
            dashboard.visibility = View.GONE
        }
    }

    override fun onPause() {
        super.onPause()
        BlockVpnService.onStateChange = null
    }

    // ------------------------------------------------------------------ onboarding

    private fun openUsageAccess() {
        val ok = listOf(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        ).any { runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
        if (!ok) Toast.makeText(this, R.string.toast_settings_failed, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ data

    private fun reload() {
        val start = UsageRepository.cycleStart(Budget.resetDay(this))
        val r = UsageRepository.load(this, start) ?: return
        report = r
        applyFilter()
        renderHeader(r)
    }

    private fun renderHeader(r: UsageReport) {
        cycleUsed.text = UsageRepository.formatBytes(r.totalMobile)

        // The headline is mobile only, because that is the number that costs money. But a
        // cycle with the SIM switched off reads as "0 B" and looks broken, so always say
        // what the Wi-Fi figure was and when the cycle started - the app is working, the
        // user simply hasn't spent anything meterable yet.
        val wifiPart = getString(R.string.plus_wifi, UsageRepository.formatBytes(r.totalWifi))
        val since = getString(
            R.string.since_date,
            java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                .format(java.util.Date(r.cycleStart)),
        )

        val bundle = Budget.bundleMb(this)
        if (bundle > 0) {
            val bundleBytes = bundle * 1_048_576L
            val pct = ((r.totalMobile.toDouble() / bundleBytes) * 100).toInt().coerceIn(0, 100)
            bundleBar.visibility = View.VISIBLE
            bundleBar.progress = pct
            cycleSub.text = getString(R.string.of_bundle, UsageRepository.formatBytes(bundleBytes)) +
                " · " + UsageRepository.formatBytes((bundleBytes - r.totalMobile).coerceAtLeast(0)) +
                " left · " + wifiPart
        } else {
            bundleBar.visibility = View.GONE
            cycleSub.text = "$since · $wifiPart · ${getString(R.string.no_bundle_set)}"
        }

        backgroundTotal.text = UsageRepository.formatBytes(r.totalBackground)

        val on = BlockVpnService.isRunning
        savedTotal.text = if (on) {
            UsageRepository.formatBytes(Budget.totalEstimatedSaved(this, blocked))
        } else {
            "—"
        }

        master.isChecked = on
        masterState.setText(
            when {
                on -> R.string.saving_on
                blocked.isNotEmpty() -> R.string.saving_off_with_rules
                else -> R.string.saving_off
            }
        )
    }

    private fun applyFilter() {
        val r = report ?: return
        val q = search.text?.toString()?.trim()?.lowercase().orEmpty()
        var apps = r.apps
        apps = when (filterMode) {
            FILTER_MOBILE -> apps.filter { it.mobile > 0 }.sortedByDescending { it.mobile }
            FILTER_BACKGROUND -> apps.filter { it.background > 0 }.sortedByDescending { it.background }
            else -> apps
        }
        if (q.isNotEmpty()) {
            apps = apps.filter { it.label.lowercase().contains(q) || it.packageName.contains(q) }
        }
        shown = apps
        adapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------------ interaction

    private fun setUpFilter() {
        filter.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.filter_all),
                getString(R.string.filter_mobile),
                getString(R.string.filter_background),
            ),
        )
        filter.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                filterMode = pos
                applyFilter()
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        })
    }

    private fun setUpList() {
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val a = shown[position]
            if (a.isProtected) {
                Toast.makeText(this, getString(R.string.protected_app), Toast.LENGTH_SHORT).show()
                return@setOnItemClickListener
            }
            toggle(a)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
    }

    private fun toggle(a: AppUsage) {
        if (a.packageName in blocked) {
            blocked.remove(a.packageName)
            Budget.clearBlock(this, a.packageName)
        } else {
            blocked.add(a.packageName)
            // Capture the baseline now, while we still have the app's pre-block rate.
            val r = report
            val days = if (r != null) {
                ((r.cycleEnd - r.cycleStart).toDouble() / 86_400_000.0).coerceAtLeast(1.0)
            } else 1.0
            Budget.recordBlock(this, a.packageName, (a.total / days).toLong())
        }
        commit()
    }

    /**
     * Bulk action. A reviewer of a competing app complained that stopping connections one
     * by one "TAKES FOREVER"; this restricts everything spending most of its data in the
     * background in a single tap.
     */
    private fun restrictWorstOffenders() {
        val r = report ?: return
        // Rank by background bytes, which is what the button promises.
        //
        // The first version also required backgroundShare > 0.5, and on real data that
        // matched nothing: the Play Store had 189 MB of background traffic but only a 42%
        // share because its foreground use is large too, while a puzzle game had a 77%
        // share of just 16 MB. Share is a ratio, not a cost - 189 MB is the problem
        // regardless of what fraction of that app's total it represents.
        val candidates = r.apps
            .filter {
                !it.isProtected &&
                    it.packageName !in blocked &&
                    it.background > 10L * 1_048_576L
            }
            .sortedByDescending { it.background }
            .take(5)
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.toast_nothing_background, Toast.LENGTH_SHORT).show()
            return
        }
        val names = candidates.joinToString("\n") {
            "· ${it.label} — ${UsageRepository.formatBytes(it.background)}"
        }
        val total = UsageRepository.formatBytes(candidates.sumOf { it.background })
        AlertDialog.Builder(this)
            .setTitle("Restrict ${candidates.size} app(s)?")
            .setMessage("These spent the most data in the background this cycle — $total between them:\n\n$names")
            .setPositiveButton("Restrict") { _, _ ->
                val days = ((r.cycleEnd - r.cycleStart).toDouble() / 86_400_000.0).coerceAtLeast(1.0)
                candidates.forEach {
                    blocked.add(it.packageName)
                    Budget.recordBlock(this, it.packageName, (it.total / days).toLong())
                }
                commit()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askBundle() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "e.g. 5000"
            val current = Budget.bundleMb(this@MainActivity)
            if (current > 0) setText(current.toString())
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.set_bundle_title)
            .setMessage("Size in MB. Leave empty to hide the gauge.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                Budget.setBundleMb(this, input.text.toString().toIntOrNull() ?: 0)
                reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setUpMaster() {
        master.setOnClickListener {
            if (master.isChecked) {
                if (blocked.isEmpty()) {
                    master.isChecked = false
                    Toast.makeText(this, R.string.toast_restrict_first, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                startEngine()
            } else {
                BlockVpnService.stop(this)
                postRefresh()
            }
        }
    }

    private fun commit() {
        // The usage list has one switch per app, so it restricts on both transports.
        // Per-network control lives in the Blocking screen.
        val previous = Rules.blockedAny(this)
        (previous - blocked).forEach { Rules.setBoth(this, it, false) }
        (blocked - previous).forEach { Rules.setBoth(this, it, true) }
        adapter.notifyDataSetChanged()
        if (blocked.isEmpty()) {
            BlockVpnService.stop(this)
            postRefresh()
        } else {
            startEngine()
        }
    }

    private fun startEngine() {
        askForNotificationsOnce()
        val consent = VpnService.prepare(this)
        if (consent != null) startActivityForResult(consent, REQUEST_CONSENT)
        else {
            BlockVpnService.start(this)
            postRefresh()
        }
    }

    /**
     * On API 33+ POST_NOTIFICATIONS is a runtime permission and starts denied, so the
     * foreground-service notification is silently suppressed: the engine runs with nothing
     * in the shade to say so, and the only clue is the system key icon.
     *
     * That matters beyond tidiness. The listing and the privacy policy both tell the user
     * an ongoing notification will be there whenever blocking is active, and without this
     * request that statement is untrue on most current devices.
     *
     * Asked at the moment the engine starts rather than at launch, so the prompt has a
     * visible reason. Declining is fine - blocking still works, it is just quieter.
     */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        runCatching {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFY)
        }
    }

    @Deprecated("Single consent call; not worth a dependency for the result contract API.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONSENT) return
        if (resultCode == RESULT_OK) BlockVpnService.start(this)
        else Toast.makeText(this, R.string.toast_vpn_required, Toast.LENGTH_LONG).show()
        postRefresh()
    }

    private fun postRefresh() {
        Handler(Looper.getMainLooper()).postDelayed({ report?.let { renderHeader(it) } }, 700)
    }
}
