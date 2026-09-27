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
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), Sampler.Listener {
    private companion object {
        const val REQ_SHIZUKU = 42
        const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    }

    private lateinit var prefs: Prefs
    private lateinit var table: Table
    private lateinit var list: ListView
    private lateinit var topBar: View
    private lateinit var modeChip: TextView
    private lateinit var btnPause: ImageButton
    private lateinit var btnOverlay: ImageButton
    private lateinit var searchBox: View
    private lateinit var searchField: EditText
    private lateinit var topScrim: View
    private var shownAccess: Access? = null
    private var shownHint = -1
    /** Свёрнутые ветки дерева (PID). */
    private val collapsed = HashSet<Int>()
    private var rows: Map<Int, Row> = emptyMap()

    // шапка списка
    private lateinit var hint: TextView
    private lateinit var cpuCard: CpuCard
    private lateinit var memCard: MemCard
    private lateinit var batteryCard: BatteryCard
    private lateinit var chips: ChipsView
    private lateinit var meters: MetersView
    private lateinit var procsTitle: TextView
    private lateinit var sortScroll: View
    private lateinit var sortBar: SortBar
    private lateinit var header: HeaderView

    private val adapter = ProcAdapter()
    private var snapshot: Snapshot? = null
    private var paused = false
    private var filter = ""
    private var waitingOverlayPermission = false
    private var insetTop = 0
    private var insetBottom = 0

    private fun dp(v: Float) = Ui.dp(this, v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        Sampler.init(this)
        prefs = Prefs(this)
        setupWindow()
        setContentView(R.layout.activity_main)
        table = Table(this).apply { cmdTitle = getString(R.string.col_command) }

        list = findViewById(R.id.list)
        topBar = findViewById(R.id.topBar)
        modeChip = findViewById(R.id.modeChip)
        btnPause = findViewById(R.id.btnPause)
        btnOverlay = findViewById(R.id.btnOverlay)
        searchBox = findViewById(R.id.searchBox)
        searchField = findViewById(R.id.searchField)
        val barFill = Ui.withAlpha(Ui.mix(Ui.base, 0xFF23262E.toInt(), 0.6f), 0.9f)
        findViewById<View>(R.id.bar).background = GlassDrawable(this, 32f, barFill)
        searchBox.background = GlassDrawable(this, 26f, barFill)
        topScrim = findViewById(R.id.topScrim)
        topScrim.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Ui.withAlpha(Ui.base, 0.94f), Ui.withAlpha(Ui.base, 0.7f), Ui.withAlpha(Ui.base, 0f)))

        setupTopBar()
        buildHeader()
        setupInsets()

        list.adapter = adapter
        list.setOnItemClickListener { parent, view, pos, _ ->
            val r = parent.getItemAtPosition(pos) as? Row ?: return@setOnItemClickListener
            // в дереве нажатие по значку сворачивает / раскрывает ветку
            if (r.cont != null && r.kids > 0 && view is ProcItemView && view.lastDownX < view.iconRight) {
                toggleBranch(r.p.pid)
                return@setOnItemClickListener
            }
            openProcess(r.p)
        }
        list.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (table.layout(v.width - dp(24f))) { header.invalidate(); list.invalidateViews() }
        }
        applyPrefs()
    }

    /** Рисуем под системными панелями (edge-to-edge) — на любой версии Android одинаково. */
    @Suppress("DEPRECATION")
    private fun setupWindow() {
        window.setBackgroundDrawable(AuroraDrawable())
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
    }

    @Suppress("DEPRECATION")
    private fun setupInsets() {
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { _, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                insetTop = bars.top; insetBottom = bars.bottom
                topBar.setPadding(dp(12f) + bars.left, bars.top + dp(8f), dp(12f) + bars.right, 0)
                list.setPadding(bars.left, list.paddingTop, bars.right, list.paddingBottom)
            } else {
                insetTop = ins.systemWindowInsetTop; insetBottom = ins.systemWindowInsetBottom
                topBar.setPadding(dp(12f), insetTop + dp(8f), dp(12f), 0)
            }
            updateListPadding()
            ins
        }
        // список начинается под плавающей панелью и прокручивается под неё
        topBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateListPadding() }
    }

    private fun updateListPadding() {
        val scrimH = topBar.height + dp(28f)
        if (topScrim.layoutParams.height != scrimH) topScrim.post {
            topScrim.layoutParams = topScrim.layoutParams.apply { height = scrimH }
        }
        val top = topBar.height + dp(10f)
        val bottom = insetBottom + dp(16f)
        if (list.paddingTop != top || list.paddingBottom != bottom)
            list.post { list.setPadding(list.paddingLeft, top, list.paddingRight, bottom) }
    }

    private fun setupTopBar() {
        findViewById<View>(R.id.btnSearch).setOnClickListener { showSearch(searchBox.visibility != View.VISIBLE) }
        findViewById<View>(R.id.btnSearchClose).setOnClickListener { showSearch(false) }
        btnPause.setOnClickListener {
            paused = !paused
            updateButtons()
            if (!paused) render()
        }
        btnOverlay.setOnClickListener {
            if (OverlayService.running) OverlayService.stop(this) else startOverlay()
            btnOverlay.postDelayed({ updateButtons() }, 300)
        }
        findViewById<View>(R.id.btnMore).setOnClickListener { showMenu(it) }
        modeChip.setOnClickListener { showAccessDialog() }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { filter = s?.toString().orEmpty(); render() }
        })
    }

    private fun showSearch(show: Boolean) {
        val imm = getSystemService(InputMethodManager::class.java)
        searchBox.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            searchField.requestFocus()
            imm.showSoftInput(searchField, 0)
        } else {
            searchField.setText("")
            imm.hideSoftInputFromWindow(searchField.windowToken, 0)
        }
    }

    private fun buildHeader() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), 0, dp(12f), dp(4f))
        }
        fun <T : View> add(v: T, bottom: Float = 10f): T {
            box.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(bottom) })
            return v
        }
        hint = add(TextView(this).apply {
            background = GlassDrawable(this@MainActivity, 24f, 0x33FFC857)
            setPadding(dp(18f), dp(14f), dp(18f), dp(14f))
            setTextColor(0xFFFFE6A8.toInt())
            textSize = 13.5f
            setLineSpacing(0f, 1.15f)
            foreground = Ui.ripple(this@MainActivity, 24f)
            setOnClickListener { showAccessDialog() }
        })
        cpuCard = add(CpuCard(this))
        memCard = add(MemCard(this))
        batteryCard = add(BatteryCard(this))
        chips = add(ChipsView(this), 18f)
        meters = add(MetersView(this, null).apply {
            background = GlassDrawable(this@MainActivity, 24f)
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        })
        procsTitle = add(TextView(this).apply {
            setTextColor(Ui.TEXT)
            textSize = 20f
            typeface = Ui.medium
            setPadding(dp(8f), 0, 0, 0)
        }, 10f)
        sortBar = SortBar(this).apply {
            onSort = { changeSort(it) }
            onTree = { prefs.treeMode = !prefs.treeMode; syncSort(); render() }
        }
        sortScroll = add(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(12f), 0, dp(12f), 0)
            addView(sortBar)
        }, 8f)
        // чипы прокручиваются до самого края экрана, а не до отступа карточек
        (sortScroll.layoutParams as LinearLayout.LayoutParams).apply { leftMargin = -dp(12f); rightMargin = -dp(12f) }
        header = HeaderView(this, null).apply {
            table = this@MainActivity.table
            onSort = { col -> changeSort(col.sort) }
        }
        add(header, 0f)
        list.addHeaderView(box, null, false)
    }

    /** Применить настройки после изменения. */
    fun applyPrefs() {
        table.setFont(prefs.fontSp)
        meters.setFont(prefs.fontSp)
        val t = prefs.tableMode
        cpuCard.visibility = if (t) View.GONE else View.VISIBLE
        memCard.visibility = cpuCard.visibility
        batteryCard.visibility = cpuCard.visibility
        chips.visibility = cpuCard.visibility
        sortScroll.visibility = cpuCard.visibility
        meters.visibility = if (t) View.VISIBLE else View.GONE
        header.visibility = meters.visibility
        syncSort()
        header.requestLayout()
        list.invalidateViews()
        OverlayService.instance?.applyPrefs()
        render()
    }

    private fun syncSort() {
        header.sort = prefs.sortKey(); header.asc = prefs.sortAsc; header.invalidate()
        sortBar.sort = prefs.sortKey(); sortBar.asc = prefs.sortAsc; sortBar.tree = prefs.treeMode; sortBar.refresh()
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
        updateButtons()
    }

    override fun onSnapshot(s: Snapshot) {
        snapshot = s
        if (!paused) render()
    }

    private fun render() {
        val s = snapshot ?: return
        // текст и фон меняем только при изменении: любой setText в шапке списка перераскладывает весь список
        if (shownAccess != s.access) {
            shownAccess = s.access
            modeChip.setText(when (s.access) {
                Access.ROOT -> R.string.mode_root
                Access.SHIZUKU -> R.string.mode_shizuku
                Access.USER -> R.string.mode_user
            })
            val chipColor = if (s.full) Ui.OK else Ui.WARN
            modeChip.setTextColor(chipColor)
            modeChip.background = Ui.pill(this, Ui.withAlpha(chipColor, 0.16f), Ui.withAlpha(chipColor, 0.35f))
            hint.visibility = if (s.full) View.GONE else View.VISIBLE
        }
        val hintRes = when (prefs.access()) {
            Access.ROOT -> R.string.hint_root_denied
            Access.SHIZUKU -> R.string.hint_shizuku_denied
            Access.USER -> R.string.hint_no_root
        }
        if (shownHint != hintRes) { shownHint = hintRes; hint.setText(hintRes) }

        if (prefs.tableMode) meters.snapshot = s
        else { cpuCard.update(s); memCard.update(s); batteryCard.update(s); chips.update(s) }

        val q = filter.trim().lowercase()
        val showKernel = prefs.kernelThreads
        val items = s.procs.filter { p ->
            (showKernel || !p.kernel) && (q.isEmpty() ||
                q in p.title.lowercase() || q in p.name.lowercase() || q in p.user.lowercase() || p.pid.toString() == q)
        }
        val cmp = prefs.sortKey().comparator(prefs.sortAsc)
        // дерево — только без поиска: при поиске показываем найденное списком
        val shown = if (prefs.treeMode && q.isEmpty()) Tree.build(items, cmp, collapsed)
        else items.sortedWith(cmp).map { Row(it) }
        rows = shown.associateBy { it.p.pid }
        val title = getString(R.string.procs_title) + "  ·  " + items.size
        if (procsTitle.text.toString() != title) procsTitle.text = title
        adapter.update(shown, s.clkTck)
    }

    /** Сведения о ветке для диалога: null — не режим дерева или нет потомков. */
    fun branch(pid: Int): Row? = rows[pid]?.takeIf { it.cont != null && it.kids > 0 }

    fun toggleBranch(pid: Int) {
        if (!collapsed.remove(pid)) collapsed += pid
        render()
    }

    fun openProcess(p: ProcInfo) {
        snapshot?.let { ProcessDialog.show(this, p, it) }
    }

    private fun changeSort(sort: Sort) {
        if (prefs.sortKey() == sort) prefs.sortAsc = !prefs.sortAsc
        else { prefs.sort = sort.name; prefs.sortAsc = sort.ascByDefault }
        syncSort()
        render()
    }

    private fun updateButtons() {
        btnPause.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        btnPause.contentDescription = getString(if (paused) R.string.resume else R.string.pause)
        val on = OverlayService.running
        btnOverlay.imageTintList = android.content.res.ColorStateList.valueOf(if (on) Ui.ON_ACCENT else Ui.TEXT)
        btnOverlay.background = if (on) Ui.pill(this, Ui.primary) else {
            val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            a.getDrawable(0).also { a.recycle() }
        }
    }

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor, Gravity.END)
        pm.menuInflater.inflate(R.menu.main, pm.menu)
        pm.menu.findItem(R.id.view_mode).isChecked = prefs.tableMode
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.root -> showAccessDialog()
                R.id.view_mode -> { prefs.tableMode = !prefs.tableMode; applyPrefs() }
                R.id.settings -> SettingsDialog.show(this, prefs)
                R.id.about -> showAbout()
            }
            true
        }
        pm.show()
    }

    /** Выбор режима доступа: обычный, Shizuku или root. */
    private fun showAccessDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), 0)
        }
        val d = AlertDialog.Builder(this)
            .setTitle(R.string.access_title)
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        val current = prefs.access()
        for ((mode, title, desc) in listOf(
            Triple(Access.USER, R.string.access_user, R.string.access_user_desc),
            Triple(Access.SHIZUKU, R.string.access_shizuku, R.string.access_shizuku_desc),
            Triple(Access.ROOT, R.string.access_root, R.string.access_root_desc),
        )) {
            val sel = mode == current
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18f), dp(12f), dp(18f), dp(14f))
                background = if (sel) Ui.pill(this@MainActivity, Ui.withAlpha(Ui.primary, 0.16f), Ui.primary, 20f)
                else GlassDrawable(this@MainActivity, 20f)
                foreground = Ui.ripple(this@MainActivity, 20f)
                addView(TextView(this@MainActivity).apply {
                    setText(title); textSize = 16f; typeface = Ui.medium
                    setTextColor(if (sel) Ui.primary else Ui.TEXT)
                })
                addView(TextView(this@MainActivity).apply {
                    setText(desc); textSize = 13f; setTextColor(Ui.TEXT2); setLineSpacing(0f, 1.1f)
                })
                setOnClickListener { d.dismiss(); chooseAccess(mode) }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8f) })
        }
        d.show()
        Ui.glassDialog(d)
    }

    private fun chooseAccess(mode: Access) {
        if (mode != Access.SHIZUKU) return applyAccess(mode)
        val alive = try { rikka.shizuku.Shizuku.pingBinder() && !rikka.shizuku.Shizuku.isPreV11() } catch (e: Exception) { false }
        if (!alive) return showShizukuMissing()
        if (Shell.shizukuReady()) return applyAccess(mode)
        rikka.shizuku.Shizuku.addRequestPermissionResultListener(object : rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQ_SHIZUKU) return
                rikka.shizuku.Shizuku.removeRequestPermissionResultListener(this)
                if (grantResult == PackageManager.PERMISSION_GRANTED) applyAccess(Access.SHIZUKU)
                else Toast.makeText(this@MainActivity, R.string.shizuku_denied, Toast.LENGTH_LONG).show()
            }
        })
        rikka.shizuku.Shizuku.requestPermission(REQ_SHIZUKU)
    }

    private fun showShizukuMissing() {
        val launch = packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
        val d = AlertDialog.Builder(this)
            .setTitle(R.string.access_shizuku)
            .setMessage(if (launch != null) R.string.shizuku_not_running else R.string.shizuku_not_installed)
            .setPositiveButton(if (launch != null) R.string.shizuku_open else R.string.shizuku_get) { _, _ ->
                startActivity(launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        Ui.glassDialog(d)
    }

    private fun applyAccess(mode: Access) {
        prefs.setAccess(mode)
        if (mode != Access.USER) Toast.makeText(this, R.string.access_asking, Toast.LENGTH_SHORT).show()
        Sampler.setAccess(mode) { ok ->
            if (!ok) Toast.makeText(this,
                if (mode == Access.ROOT) R.string.root_failed else R.string.shizuku_failed, Toast.LENGTH_LONG).show()
            shownHint = -1
            render()
        }
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            val d = AlertDialog.Builder(this)
                .setTitle(R.string.overlay)
                .setMessage(R.string.overlay_permission)
                .setPositiveButton(R.string.grant) { _, _ ->
                    waitingOverlayPermission = true
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            Ui.glassDialog(d)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        OverlayService.start(this)
        Toast.makeText(this, R.string.overlay_started, Toast.LENGTH_SHORT).show()
    }

    private fun showAbout() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val tv = TextView(this).apply {
            text = android.text.Html.fromHtml(getString(R.string.about_text, version), android.text.Html.FROM_HTML_MODE_LEGACY)
            movementMethod = LinkMovementMethod.getInstance()
            setLinkTextColor(Ui.primary)
            setTextColor(Ui.TEXT2)
            setPadding(dp(24f), dp(8f), dp(24f), 0)
            textSize = 15f
        }
        val d = AlertDialog.Builder(this).setTitle(R.string.app_name).setView(tv)
            .setPositiveButton(android.R.string.ok, null).show()
        Ui.glassDialog(d)
    }

    // ---------- список ----------

    private inner class ProcAdapter : BaseAdapter() {
        private var items: List<Row> = emptyList()
        private var clkTck = 100L
        private var metric = Metric.CPU
        private var maxValue = 1f

        fun update(newItems: List<Row>, clk: Long) {
            items = newItems; clkTck = clk
            metric = when (prefs.sortKey()) { Sort.MEM -> Metric.MEM; Sort.TIME -> Metric.TIME; else -> Metric.CPU }
            maxValue = when (metric) {
                Metric.MEM -> (items.maxOfOrNull { it.p.rss } ?: 1L).toFloat()
                Metric.TIME -> (items.maxOfOrNull { it.p.cpuTicks } ?: 1L).toFloat()
                Metric.CPU -> 100f
            }.coerceAtLeast(1f)
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].p.pid.toLong()
        override fun hasStableIds() = true
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (prefs.tableMode) 1 else 0

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            if (prefs.tableMode) {
                val v = convertView as? ProcRowView ?: ProcRowView(this@MainActivity, table)
                v.row = items[position]
                v.clkTck = clkTck
                if (v.measuredHeight != table.rowH) v.requestLayout()
                v.invalidate()
                return v
            }
            val v = convertView as? ProcItemView ?: ProcItemView(this@MainActivity)
            v.row = items[position]
            v.metric = metric
            v.maxValue = maxValue
            v.clkTck = clkTck
            v.invalidate()
            return v
        }
    }
}
