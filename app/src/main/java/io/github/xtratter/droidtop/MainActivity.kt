package io.github.xtratter.droidtop

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.SearchView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), Sampler.Listener {
    private lateinit var prefs: Prefs
    private lateinit var table: Table
    private lateinit var meters: MetersView
    private lateinit var header: HeaderView
    private lateinit var hint: TextView
    private lateinit var list: ListView
    private val adapter = ProcAdapter()
    private var snapshot: Snapshot? = null
    private var paused = false
    private var filter = ""
    private var waitingOverlayPermission = false
    private var menu: Menu? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        Sampler.init(this)
        table = Table(this).apply { cmdTitle = getString(R.string.col_command) }
        meters = findViewById(R.id.meters)
        header = findViewById(R.id.header)
        hint = findViewById(R.id.hint)
        list = findViewById(R.id.list)

        header.table = table
        header.onSort = { col -> changeSort(col.sort) }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            snapshot?.let { ProcessDialog.show(this, adapter.getItem(pos), it) }
        }
        list.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (table.layout(v.width)) { header.invalidate(); list.invalidateViews() }
        }
        hint.setOnClickListener { setRoot(true) }
        applyPrefs()
    }

    /** Применить настройки после изменения. */
    fun applyPrefs() {
        table.setFont(prefs.fontSp)
        meters.setFont(prefs.fontSp)
        header.sort = prefs.sortKey()
        header.asc = prefs.sortAsc
        header.requestLayout()
        header.invalidate()
        list.invalidateViews()
        OverlayService.instance?.applyPrefs()
        render()
    }

    override fun onStart() {
        super.onStart()
        Sampler.add(this)
    }

    override fun onStop() {
        Sampler.remove(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (waitingOverlayPermission) {
            waitingOverlayPermission = false
            if (Settings.canDrawOverlays(this)) startOverlay()
        }
        updateMenu()
    }

    override fun onSnapshot(s: Snapshot) {
        snapshot = s
        if (!paused) render()
        updateMenu()
    }

    private fun render() {
        val s = snapshot ?: return
        meters.snapshot = s
        actionBar?.subtitle = getString(if (s.root) R.string.mode_root else R.string.mode_user)
        hint.visibility = if (s.root) View.GONE else View.VISIBLE
        hint.setText(if (prefs.root) R.string.hint_root_denied else R.string.hint_no_root)

        val q = filter.trim().lowercase()
        val showKernel = prefs.kernelThreads
        val items = s.procs.filter { p ->
            (showKernel || !p.kernel) && (q.isEmpty() ||
                q in p.title.lowercase() || q in p.name.lowercase() || q in p.user.lowercase() || p.pid.toString() == q)
        }.sortedWith(prefs.sortKey().comparator(prefs.sortAsc))
        adapter.update(items, s.clkTck)
    }

    private fun changeSort(sort: Sort) {
        if (prefs.sortKey() == sort) prefs.sortAsc = !prefs.sortAsc
        else { prefs.sort = sort.name; prefs.sortAsc = sort.ascByDefault }
        header.sort = sort
        header.asc = prefs.sortAsc
        header.invalidate()
        render()
        list.setSelection(0)
    }

    // ---------- меню ----------

    override fun onCreateOptionsMenu(m: Menu): Boolean {
        menuInflater.inflate(R.menu.main, m)
        menu = m
        val sv = m.findItem(R.id.search).actionView as SearchView
        sv.queryHint = getString(R.string.search_hint)
        sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = true
            override fun onQueryTextChange(q: String?): Boolean {
                filter = q.orEmpty(); render(); return true
            }
        })
        updateMenu()
        return true
    }

    private fun updateMenu() {
        val m = menu ?: return
        m.findItem(R.id.root).isChecked = prefs.root
        m.findItem(R.id.overlay).isChecked = OverlayService.running
        m.findItem(R.id.pause).apply {
            setIcon(if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
            setTitle(if (paused) R.string.resume else R.string.pause)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.pause -> { paused = !paused; updateMenu(); if (!paused) render() }
            R.id.root -> setRoot(!prefs.root)
            R.id.overlay -> if (OverlayService.running) OverlayService.stop(this) else startOverlay()
            R.id.settings -> SettingsDialog.show(this, prefs)
            R.id.about -> showAbout()
            else -> return super.onOptionsItemSelected(item)
        }
        updateMenu()
        return true
    }

    private fun setRoot(root: Boolean) {
        prefs.root = root
        if (root) Toast.makeText(this, R.string.root_asking, Toast.LENGTH_SHORT).show()
        Sampler.setRoot(root) { ok ->
            if (root && !ok) Toast.makeText(this, R.string.root_failed, Toast.LENGTH_LONG).show()
            updateMenu()
        }
        updateMenu()
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.overlay)
                .setMessage(R.string.overlay_permission)
                .setPositiveButton(R.string.grant) { _, _ ->
                    waitingOverlayPermission = true
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        OverlayService.start(this)
        list.postDelayed({ updateMenu() }, 300)
    }

    private fun showAbout() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val tv = TextView(this).apply {
            text = android.text.Html.fromHtml(getString(R.string.about_text, version), android.text.Html.FROM_HTML_MODE_LEGACY)
            movementMethod = LinkMovementMethod.getInstance()
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, 0)
            textSize = 15f
        }
        AlertDialog.Builder(this).setTitle(R.string.app_name).setView(tv)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    // ---------- список ----------

    private inner class ProcAdapter : BaseAdapter() {
        private var items: List<ProcInfo> = emptyList()
        private var clkTck = 100L

        fun update(newItems: List<ProcInfo>, clk: Long) {
            items = newItems; clkTck = clk
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].pid.toLong()
        override fun hasStableIds() = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView as? ProcRowView ?: ProcRowView(this@MainActivity, table)
            v.proc = items[position]
            v.clkTck = clkTck
            if (v.measuredHeight != table.rowH) v.requestLayout()
            v.invalidate()
            return v
        }
    }
}
