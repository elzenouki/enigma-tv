package com.zenouki.enigmatv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class MainActivity : Activity() {

    data class Bouquet(val ref: String, val name: String)
    data class Channel(
        val ref: String,
        val name: String,
        var now: String = "",
        var begin: Long = 0L,
        var duration: Long = 0L,
        val picon: String? = null,
        var next: String = "",
        var nextBegin: Long = 0L
    )

    data class Row(val num: String, val title: String, val sub: String, val picon: String? = null, val showPicon: Boolean = false)

    private lateinit var prefs: SharedPreferences
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var numberEntry: TextView
    private lateinit var banner: View
    private lateinit var bNum: TextView
    private lateinit var bName: TextView
    private lateinit var bNow: TextView
    private lateinit var bProgress: ProgressBar
    private lateinit var bBouquet: TextView
    private lateinit var bPicon: ImageView
    private lateinit var bNext: TextView
    private lateinit var bClock: TextView
    private lateinit var bTech: TextView
    private val sleepRunnable = Runnable { goToSleep() }
    private var sleepAt = 0L
    private lateinit var panel: View
    private lateinit var bouquetList: ListView
    private lateinit var channelList: ListView
    private lateinit var channelHeader: TextView

    private val io = Executors.newFixedThreadPool(2)
    private val ui = Handler(Looper.getMainLooper())

    private var bouquets: List<Bouquet> = emptyList()
    private var playBouquet: Bouquet? = null
    private var playChannels: List<Channel> = emptyList()
    private var playIndex = -1

    private var browseBouquetIdx = -1
    private var browseChannels: List<Channel> = emptyList()
    private var browseToken = 0

    private lateinit var bouquetAdapter: RowAdapter
    private lateinit var channelAdapter: RowAdapter

    private var numberBuffer = ""
    private var lastBackPress = 0L
    private var retried = false
    private var loaded = false

    private val hideBanner = Runnable { banner.visibility = View.GONE }
    private val commitNumber = Runnable { applyNumber() }
    private val pendingPlay = Runnable { startStream() }
    private val retryRunnable = Runnable { startStream() }

    // --- sync with main receiver (single cable) ---
    private var currentRef: String? = null
    private var lastOwnZap = 0L
    private var started = false
    private var updateChecked = false
    private val poller = object : Runnable {
        override fun run() {
            ui.removeCallbacks(this)
            if (!started) return
            if (syncMain() && loaded) checkReceiverChannel()
            ui.postDelayed(this, 4000)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("enigma", Context.MODE_PRIVATE)
        if (prefs.getString("host", "").isNullOrBlank()) {
            prefs.edit().putString("host", DEFAULT_HOST).apply()
        }

        playerView = findViewById(R.id.player)
        status = findViewById(R.id.status)
        numberEntry = findViewById(R.id.numberEntry)
        banner = findViewById(R.id.banner)
        bNum = findViewById(R.id.bNum)
        bName = findViewById(R.id.bName)
        bNow = findViewById(R.id.bNow)
        bProgress = findViewById(R.id.bProgress)
        bBouquet = findViewById(R.id.bBouquet)
        bPicon = findViewById(R.id.bPicon)
        bNext = findViewById(R.id.bNext)
        bClock = findViewById(R.id.bClock)
        bTech = findViewById(R.id.bTech)
        playerView.resizeMode = RESIZE_MODES[prefs.getInt("resize", 0).coerceIn(0, RESIZE_MODES.size - 1)]
        panel = findViewById(R.id.panel)
        bouquetList = findViewById(R.id.bouquetList)
        channelList = findViewById(R.id.channelList)
        channelHeader = findViewById(R.id.channelHeader)

        bouquetAdapter = RowAdapter(this)
        channelAdapter = RowAdapter(this)
        bouquetList.adapter = bouquetAdapter
        channelList.adapter = channelAdapter

        bouquetList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos < bouquets.size && pos != browseBouquetIdx) {
                    ui.removeCallbacks(browseLoader)
                    pendingBrowseIdx = pos
                    ui.postDelayed(browseLoader, 350)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        bouquetList.setOnItemClickListener { _, _, pos, _ ->
            if (pos >= bouquets.size) {
                showSettings()
            } else {
                if (pos != browseBouquetIdx) loadBrowse(pos, focusChannels = true, selectRef = null)
                else channelList.requestFocus()
            }
        }
        channelList.setOnItemClickListener { _, _, pos, _ ->
            if (browseBouquetIdx in bouquets.indices && pos < browseChannels.size) {
                playBouquet = bouquets[browseBouquetIdx]
                playChannels = browseChannels
                hidePanel()
                playAt(pos, immediate = true)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        initPlayer()
        if (!loaded) loadAll() else if (playIndex >= 0) startStream()
        ui.postDelayed(poller, 4000)
        if (!updateChecked) {
            updateChecked = true
            ui.postDelayed({ checkForUpdate() }, 6000)
        }
    }

    override fun onStop() {
        super.onStop()
        started = false
        ui.removeCallbacks(poller)
        ui.removeCallbacks(pendingPlay)
        ui.removeCallbacks(retryRunnable)
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    // ---------------------------------------------------------------- player

    private fun initPlayer() {
        if (player != null) return
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(20000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("EnigmaTV")
        authHeader()?.let { http.setDefaultRequestProperties(mapOf("Authorization" to it)) }

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!retried) {
                    retried = true
                    showStatus("بيعيد المحاولة...")
                    ui.postDelayed(retryRunnable, 2500)
                } else {
                    showStatus("مش قادر يشغّل القناة دي\nيمكن التيونرين مشغولين أو القناة مشفّرة")
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (banner.visibility == View.VISIBLE) updateTech()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    status.visibility = View.GONE
                    retried = false
                }
            }
        })
        applyTrackPrefs(p)
        Picons.auth = authHeader()
        playerView.player = p
        player = p
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    private fun playAt(index: Int, immediate: Boolean) {
        if (playChannels.isEmpty()) return
        val n = playChannels.size
        playIndex = ((index % n) + n) % n
        val ch = playChannels[playIndex]
        prefs.edit()
            .putString("lastBouquet", playBouquet?.ref)
            .putString("lastChannel", ch.ref)
            .apply()
        showBanner()
        ui.removeCallbacks(pendingPlay)
        ui.removeCallbacks(retryRunnable)
        retried = false
        clearTrackOverrides()
        if (immediate) startStream() else ui.postDelayed(pendingPlay, 450)
    }

    private fun startStream() {
        if (player == null) return
        if (playIndex !in playChannels.indices) return
        val ch = playChannels[playIndex]
        status.visibility = View.GONE
        currentRef = ch.ref
        if (syncMain() && !isIptv(ch.ref)) {
            // single cable: switch the main receiver first, then stream the same service
            lastOwnZap = System.currentTimeMillis()
            io.execute {
                try { httpGet("/api/zap?sRef=" + enc(ch.ref)) } catch (_: Exception) {}
                ui.postDelayed({ if (currentRef == ch.ref) openStream(ch.ref) }, 600)
            }
        } else {
            openStream(ch.ref)
        }
    }

    private fun openStream(ref: String) {
        val p = player ?: return
        p.setMediaItem(MediaItem.fromUri(Uri.parse(streamUrl(ref))))
        p.prepare()
        p.playWhenReady = true
    }

    /** If someone changed the channel with the receiver's own remote, follow it. */
    private fun checkReceiverChannel() {
        if (System.currentTimeMillis() - lastOwnZap < 8000) return
        io.execute {
            val ref = try {
                JSONObject(httpGet("/api/statusinfo")).optString("currservice_serviceref", "")
            } catch (_: Exception) { "" }
            if (ref.isBlank() || isMarker(ref)) return@execute
            ui.post { followReceiver(ref) }
        }
    }

    private fun followReceiver(ref: String) {
        if (System.currentTimeMillis() - lastOwnZap < 8000) return
        val cur = currentRef
        if (cur != null && norm(ref) == norm(cur)) return
        val i = playChannels.indexOfFirst { norm(it.ref) == norm(ref) }
        if (i >= 0) {
            playIndex = i
            currentRef = playChannels[i].ref
            prefs.edit().putString("lastChannel", currentRef).apply()
            showBanner()
            openStream(playChannels[i].ref)
            return
        }
        // not in the current bouquet: search the other bouquets
        currentRef = ref
        val bqs = bouquets
        io.execute {
            for (bq in bqs) {
                val chs = try { fetchChannels(bq.ref) } catch (_: Exception) { null } ?: continue
                val idx = chs.indexOfFirst { norm(it.ref) == norm(ref) }
                if (idx >= 0) {
                    fillEpg(bq.ref, chs)
                    ui.post {
                        if (currentRef == ref) {
                            playBouquet = bq
                            playChannels = chs
                            playIndex = idx
                            prefs.edit().putString("lastBouquet", bq.ref).putString("lastChannel", ref).apply()
                            showBanner()
                            openStream(chs[idx].ref)
                        }
                    }
                    return@execute
                }
            }
            ui.post { if (currentRef == ref) openStream(ref) }
        }
    }

    private fun zap(delta: Int) {
        if (playChannels.isEmpty()) return
        playAt(playIndex + delta, immediate = false)
    }

    // ---------------------------------------------------------------- data

    private fun loadAll() {
        showStatus("جاري الاتصال بالرسيفر ${host()} ...")
        io.execute {
            try {
                val bqs = fetchBouquets()
                if (bqs.isEmpty()) throw IllegalStateException("no bouquets")
                val lastB = prefs.getString("lastBouquet", null)
                val bq = bqs.firstOrNull { it.ref == lastB } ?: bqs.first()
                val chs = fetchChannels(bq.ref)
                fillEpg(bq.ref, chs)
                val lastC = prefs.getString("lastChannel", null)
                val idx = chs.indexOfFirst { it.ref == lastC }.let { if (it < 0) 0 else it }
                ui.post {
                    bouquets = bqs
                    playBouquet = bq
                    playChannels = chs
                    loaded = true
                    refreshBouquetRows()
                    if (chs.isEmpty()) {
                        showStatus("الباقة دي فاضية\nدوس OK واختار باقة تانية")
                    } else {
                        playAt(idx, immediate = true)
                    }
                }
            } catch (e: Exception) {
                ui.post {
                    showStatus("مش قادر أوصل للرسيفر على ${host()}\nاتأكد إن الرسيفر شغال وإن الـ IP صح")
                    showSettings()
                }
            }
        }
    }

    private fun fetchBouquets(): List<Bouquet> {
        val json = JSONObject(httpGet("/api/getservices"))
        val arr = json.optJSONArray("services") ?: return emptyList()
        val out = ArrayList<Bouquet>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ref = o.optString("servicereference")
            val name = o.optString("servicename")
            if (ref.isNotBlank()) out.add(Bouquet(ref, name))
        }
        return out
    }

    private fun fetchChannels(bouquetRef: String): List<Channel> {
        val json = JSONObject(httpGet("/api/getservices?picon=1&sRef=" + enc(bouquetRef)))
        val arr = json.optJSONArray("services") ?: return emptyList()
        val out = ArrayList<Channel>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ref = o.optString("servicereference")
            val name = o.optString("servicename")
            if (ref.isBlank() || isMarker(ref) || name.isBlank()) continue
            val pic = o.optString("picon", "")
            val picUrl = when {
                pic.isBlank() -> null
                pic.startsWith("http") -> pic
                else -> "http://${host()}" + (if (pic.startsWith("/")) pic else "/$pic")
            }
            out.add(Channel(ref, name, picon = picUrl))
        }
        return out
    }

    private fun fillEpg(bouquetRef: String, channels: List<Channel>) {
        try {
            val json = JSONObject(httpGet("/api/epgnow?bRef=" + enc(bouquetRef)))
            val arr = json.optJSONArray("events") ?: return
            val map = HashMap<String, JSONObject>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                map[norm(o.optString("sref"))] = o
            }
            for (c in channels) {
                val e = map[norm(c.ref)] ?: continue
                c.now = e.optString("title", "")
                c.begin = e.optLong("begin_timestamp", 0L)
                c.duration = e.optLong("duration_sec", 0L)
            }
        } catch (_: Exception) {
        }
    }

    private var pendingBrowseIdx = -1
    private val browseLoader = Runnable {
        if (pendingBrowseIdx >= 0) loadBrowse(pendingBrowseIdx, focusChannels = false, selectRef = null)
    }

    private fun loadBrowse(idx: Int, focusChannels: Boolean, selectRef: String?) {
        if (idx !in bouquets.indices) return
        browseBouquetIdx = idx
        val bq = bouquets[idx]
        val token = ++browseToken
        channelHeader.text = bq.name
        channelAdapter.rows = listOf(Row("", "جاري التحميل...", ""))
        io.execute {
            val chs = try {
                fetchChannels(bq.ref).also { fillEpg(bq.ref, it) }
            } catch (e: Exception) {
                null
            }
            ui.post {
                if (token != browseToken) return@post
                if (chs == null) {
                    channelAdapter.rows = listOf(Row("", "خطأ في التحميل", ""))
                    return@post
                }
                browseChannels = chs
                channelAdapter.rows = chs.mapIndexed { i, c -> Row("${i + 1}", c.name, c.now, c.picon, true) }
                if (chs.isEmpty()) channelAdapter.rows = listOf(Row("", "الباقة فاضية", ""))
                val sel = if (selectRef != null) chs.indexOfFirst { it.ref == selectRef } else -1
                val active = if (playBouquet?.ref == bq.ref) playIndex else -1
                if (active in chs.indices) channelList.setItemChecked(active, true) else channelList.clearChoices()
                val target = if (sel >= 0) sel else 0
                channelList.setSelection(target)
                if (focusChannels && chs.isNotEmpty()) {
                    channelList.requestFocus()
                    channelList.setSelection(target)
                }
                // keep playing list's EPG fresh too
                if (playBouquet?.ref == bq.ref && chs.size == playChannels.size) {
                    for (i in chs.indices) {
                        playChannels[i].now = chs[i].now
                        playChannels[i].begin = chs[i].begin
                        playChannels[i].duration = chs[i].duration
                    }
                }
            }
        }
    }

    private fun refreshBouquetRows() {
        val rows = bouquets.map { Row("", it.name, "") }.toMutableList()
        rows.add(Row("", "⚙  الإعدادات", "IP الرسيفر: ${host()}"))
        bouquetAdapter.rows = rows
    }

    private fun refreshPlayingEpg() {
        val bq = playBouquet ?: return
        val chs = playChannels
        io.execute {
            fillEpg(bq.ref, chs)
            ui.post { if (banner.visibility == View.VISIBLE) updateBannerText() }
        }
    }

    // ---------------------------------------------------------------- UI

    private fun showPanel() {
        if (!loaded) return
        ui.removeCallbacks(hideBanner)
        banner.visibility = View.GONE
        panel.visibility = View.VISIBLE
        refreshBouquetRows()
        val bIdx = bouquets.indexOfFirst { it.ref == playBouquet?.ref }.let { if (it < 0) 0 else it }
        bouquetList.setItemChecked(bIdx, true)
        bouquetList.setSelection(bIdx)
        val currentRef = if (playIndex in playChannels.indices) playChannels[playIndex].ref else null
        channelList.requestFocus()
        loadBrowse(bIdx, focusChannels = true, selectRef = currentRef)
    }

    private fun hidePanel() {
        panel.visibility = View.GONE
        ui.removeCallbacks(browseLoader)
    }

    private fun showBanner() {
        if (playIndex !in playChannels.indices) return
        updateBannerText()
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, 5000)
        fetchNowNext(playChannels[playIndex])
    }

    private fun fetchNowNext(ch: Channel) {
        io.execute {
            try {
                val arr = JSONObject(httpGet("/api/epgservicenownext?sRef=" + enc(ch.ref))).optJSONArray("events")
                if (arr != null && arr.length() > 0) {
                    val now = arr.getJSONObject(0)
                    if (now.optLong("begin_timestamp", 0L) > 0) {
                        ch.now = now.optString("title", "")
                        ch.begin = now.optLong("begin_timestamp", 0L)
                        ch.duration = now.optLong("duration_sec", 0L)
                    }
                    if (arr.length() > 1) {
                        val nx = arr.getJSONObject(1)
                        ch.next = nx.optString("title", "")
                        ch.nextBegin = nx.optLong("begin_timestamp", 0L)
                    }
                }
            } catch (_: Exception) {
            }
            ui.post {
                if (banner.visibility == View.VISIBLE && playIndex in playChannels.indices && playChannels[playIndex] === ch) updateBannerText()
            }
        }
    }

    private fun updateBannerText() {
        if (playIndex !in playChannels.indices) return
        val ch = playChannels[playIndex]
        bNum.text = "${playIndex + 1}"
        bName.text = ch.name
        bBouquet.text = playBouquet?.name ?: ""
        Picons.load(ch.picon, bPicon)
        val hm = SimpleDateFormat("HH:mm", Locale.US)
        bClock.text = hm.format(Date())
        bNext.text = if (ch.next.isNotBlank() && ch.nextBegin > 0) "بعده  ${hm.format(Date(ch.nextBegin * 1000))}   ${ch.next}" else ""
        bNext.visibility = if (bNext.text.isEmpty()) View.GONE else View.VISIBLE
        updateTech()
        if (ch.now.isNotBlank() && ch.begin > 0) {
            val fmt = SimpleDateFormat("HH:mm", Locale.US)
            val from = fmt.format(Date(ch.begin * 1000))
            val to = fmt.format(Date((ch.begin + ch.duration) * 1000))
            bNow.text = "$from - $to   ${ch.now}"
            val nowSec = System.currentTimeMillis() / 1000
            val prog = if (ch.duration > 0) ((nowSec - ch.begin) * 1000 / ch.duration).toInt() else 0
            bProgress.progress = prog.coerceIn(0, 1000)
            bProgress.visibility = View.VISIBLE
        } else {
            bNow.text = ch.now
            bProgress.visibility = View.INVISIBLE
        }
    }

    private fun showStatus(msg: String) {
        status.text = msg
        status.visibility = View.VISIBLE
    }

    private fun typeDigit(d: Int) {
        if (playChannels.isEmpty()) return
        if (numberBuffer.length >= 4) numberBuffer = ""
        numberBuffer += d.toString()
        numberEntry.text = numberBuffer
        numberEntry.visibility = View.VISIBLE
        ui.removeCallbacks(commitNumber)
        ui.postDelayed(commitNumber, 1500)
    }

    private fun applyNumber() {
        val n = numberBuffer.toIntOrNull() ?: 0
        numberBuffer = ""
        numberEntry.visibility = View.GONE
        if (n in 1..playChannels.size) {
            playAt(n - 1, immediate = true)
        } else {
            Toast.makeText(this, "مفيش قناة رقم $n في الباقة دي", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- options (audio / subs / picture / sleep)

    private fun showOptions() {
        hidePanel()
        val labels = arrayOf(
            "🔊  الصوت:  " + currentTrackLabel(C.TRACK_TYPE_AUDIO),
            "💬  الترجمة:  " + currentTrackLabel(C.TRACK_TYPE_TEXT),
            "🖼  مقاس الصورة:  " + RESIZE_NAMES[prefs.getInt("resize", 0).coerceIn(0, RESIZE_NAMES.size - 1)],
            "⏰  مؤقت النوم:  " + sleepLabel(),
            "⚙  إعدادات الرسيفر"
        )
        AlertDialog.Builder(this)
            .setTitle("خيارات")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> chooseTrack(C.TRACK_TYPE_AUDIO)
                    1 -> chooseTrack(C.TRACK_TYPE_TEXT)
                    2 -> cycleResize()
                    3 -> chooseSleep()
                    4 -> showSettings()
                }
            }
            .show()
    }

    private data class TrackOpt(val group: androidx.media3.common.Tracks.Group, val index: Int, val label: String, val lang: String?)

    private fun tracksOf(type: Int): List<TrackOpt> {
        val p = player ?: return emptyList()
        val out = ArrayList<TrackOpt>()
        var n = 0
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                n++
                val f = g.getTrackFormat(i)
                out.add(TrackOpt(g, i, trackLabel(f, n, type), f.language))
            }
        }
        return out
    }

    private fun currentTrackLabel(type: Int): String {
        val p = player ?: return "-"
        if (type == C.TRACK_TYPE_TEXT && p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return "مقفولة"
        var n = 0
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                n++
                if (g.isTrackSelected(i)) return trackLabel(g.getTrackFormat(i), n, type)
            }
        }
        return if (type == C.TRACK_TYPE_TEXT) "مقفولة" else "-"
    }

    private fun trackLabel(f: Format, n: Int, type: Int): String {
        val lang = langName(f.language)
        val base = when {
            !f.label.isNullOrBlank() && lang != null -> "${f.label} ($lang)"
            !f.label.isNullOrBlank() -> f.label!!
            lang != null -> lang
            else -> (if (type == C.TRACK_TYPE_AUDIO) "صوت " else "ترجمة ") + n
        }
        val mime = f.sampleMimeType ?: ""
        val codec = when {
            mime.contains("ac3", true) || mime.contains("eac3", true) -> " • Dolby"
            mime.contains("aac", true) -> " • AAC"
            mime.contains("dvbsubs", true) -> " • DVB"
            else -> ""
        }
        return base + codec
    }

    private fun langName(code: String?): String? {
        if (code.isNullOrBlank() || code == "und") return null
        return when (code.lowercase(Locale.US).take(3)) {
            "ar", "ara" -> "عربي"
            "en", "eng" -> "إنجليزي"
            "fr", "fre", "fra" -> "فرنساوي"
            "de", "ger", "deu" -> "ألماني"
            "tr", "tur" -> "تركي"
            "es", "spa" -> "إسباني"
            "it", "ita" -> "إيطالي"
            "fa", "per", "fas" -> "فارسي"
            "hi", "hin" -> "هندي"
            "ur", "urd" -> "أوردو"
            "qaa", "mul" -> "أصلي"
            else -> code
        }
    }

    private fun chooseTrack(type: Int) {
        val p = player ?: return
        val opts = tracksOf(type)
        if (opts.isEmpty()) {
            Toast.makeText(this, if (type == C.TRACK_TYPE_AUDIO) "مفيش أصوات تانية للقناة دي" else "مفيش ترجمة للقناة دي", Toast.LENGTH_SHORT).show()
            return
        }
        val items = ArrayList<String>()
        if (type == C.TRACK_TYPE_TEXT) items.add("بدون ترجمة")
        items.addAll(opts.map { it.label })
        AlertDialog.Builder(this)
            .setTitle(if (type == C.TRACK_TYPE_AUDIO) "اختار الصوت" else "اختار الترجمة")
            .setItems(items.toTypedArray()) { _, which ->
                val b = p.trackSelectionParameters.buildUpon()
                if (type == C.TRACK_TYPE_TEXT && which == 0) {
                    b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    prefs.edit().putString("subLang", "off").apply()
                } else {
                    val o = opts[if (type == C.TRACK_TYPE_TEXT) which - 1 else which]
                    b.setTrackTypeDisabled(type, false)
                    b.setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
                    if (type == C.TRACK_TYPE_AUDIO) {
                        b.setPreferredAudioLanguage(o.lang)
                        prefs.edit().putString("audioLang", o.lang ?: "").apply()
                    } else {
                        b.setPreferredTextLanguage(o.lang)
                        prefs.edit().putString("subLang", o.lang ?: "").apply()
                    }
                }
                p.trackSelectionParameters = b.build()
            }
            .show()
    }

    private fun applyTrackPrefs(p: ExoPlayer) {
        val b = p.trackSelectionParameters.buildUpon()
        val a = prefs.getString("audioLang", "") ?: ""
        if (a.isNotBlank()) b.setPreferredAudioLanguage(a)
        when (val t = prefs.getString("subLang", "off") ?: "off") {
            "off" -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            "" -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            else -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(t)
        }
        p.trackSelectionParameters = b.build()
    }

    /** When the channel changes, drop per-channel overrides but keep language preferences. */
    private fun clearTrackOverrides() {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverrides().build()
    }

    private fun updateTech() {
        val p = player ?: return
        val f = p.videoFormat
        val res = when {
            f == null || f.height <= 0 -> ""
            f.height >= 2000 -> "4K"
            f.height >= 1000 -> "FHD"
            f.height >= 700 -> "HD"
            else -> "SD"
        }
        val audio = currentTrackLabel(C.TRACK_TYPE_AUDIO).let { if (it == "-") "" else it }
        bTech.text = listOf(res, audio).filter { it.isNotBlank() }.joinToString("  •  ")
    }

    private fun cycleResize() {
        val next = (prefs.getInt("resize", 0) + 1) % RESIZE_MODES.size
        prefs.edit().putInt("resize", next).apply()
        playerView.resizeMode = RESIZE_MODES[next]
        Toast.makeText(this, "مقاس الصورة: " + RESIZE_NAMES[next], Toast.LENGTH_SHORT).show()
    }

    private fun sleepLabel(): String {
        if (sleepAt == 0L) return "مقفول"
        val left = ((sleepAt - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
        return "باقي $left دقيقة"
    }

    private fun chooseSleep() {
        val mins = intArrayOf(0, 15, 30, 60, 90, 120)
        val names = arrayOf("إلغاء المؤقت", "15 دقيقة", "30 دقيقة", "ساعة", "ساعة ونص", "ساعتين")
        AlertDialog.Builder(this)
            .setTitle("مؤقت النوم")
            .setItems(names) { _, w ->
                ui.removeCallbacks(sleepRunnable)
                if (mins[w] == 0) {
                    sleepAt = 0L
                    Toast.makeText(this, "اتلغى مؤقت النوم", Toast.LENGTH_SHORT).show()
                } else {
                    sleepAt = System.currentTimeMillis() + mins[w] * 60_000L
                    ui.postDelayed(sleepRunnable, mins[w] * 60_000L)
                    Toast.makeText(this, "التطبيق هيقفل بعد ${names[w]}", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun goToSleep() {
        sleepAt = 0L
        player?.stop()
        finish()
    }

    private fun showSettings() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun field(hint: String, value: String, password: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine()
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            box.addView(this)
        }
        val hostF = field("IP الرسيفر (مثال 192.168.1.169)", host())
        val userF = field("اسم المستخدم (سيبه فاضي لو مفيش)", prefs.getString("user", "") ?: "")
        val passF = field("الباسورد", prefs.getString("pass", "") ?: "", password = true)
        val syncF = CheckBox(this).apply {
            text = "تحويل الرسيفر الأساسي مع الشاشة (كابل واحد)"
            isChecked = syncMain()
            box.addView(this)
        }

        AlertDialog.Builder(this)
            .setTitle("إعدادات الرسيفر")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                prefs.edit()
                    .putString("host", hostF.text.toString().trim().ifBlank { DEFAULT_HOST })
                    .putString("user", userF.text.toString().trim())
                    .putString("pass", passF.text.toString())
                    .putBoolean("syncMain", syncF.isChecked)
                    .apply()
                hidePanel()
                Picons.clear()
                releasePlayer()
                initPlayer()
                loaded = false
                loadAll()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ---------------------------------------------------------------- keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val code = event.keyCode

        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS) {
            showOptions(); return true
        }

        if (panel.visibility == View.VISIBLE) {
            when (code) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { hidePanel(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (bouquetList.hasFocus()) {
                    ui.removeCallbacks(browseLoader)
                    val pos = bouquetList.selectedItemPosition
                    if (pos in bouquets.indices && pos != browseBouquetIdx) {
                        loadBrowse(pos, focusChannels = true, selectRef = null)
                    } else channelList.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (channelList.hasFocus()) {
                    bouquetList.requestFocus(); return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { zap(+1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { zap(-1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_GUIDE -> {
                if (numberBuffer.isNotEmpty()) { ui.removeCallbacks(commitNumber); applyNumber() }
                else showPanel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> { showOptions(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> { showBanner(); return true }
            KeyEvent.KEYCODE_LAST_CHANNEL -> { return true }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (banner.visibility == View.VISIBLE) { banner.visibility = View.GONE; return true }
                val now = System.currentTimeMillis()
                if (now - lastBackPress < 2000) { finish(); return true }
                lastBackPress = now
                Toast.makeText(this, "دوس رجوع تاني للخروج", Toast.LENGTH_SHORT).show()
                return true
            }
        }
        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { typeDigit(code - KeyEvent.KEYCODE_0); return true }
        if (code in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) { typeDigit(code - KeyEvent.KEYCODE_NUMPAD_0); return true }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- auto update

    @Suppress("DEPRECATION")
    private fun myVersionCode(): Int = try {
        packageManager.getPackageInfo(packageName, 0).versionCode
    } catch (_: Exception) { 0 }

    private fun checkForUpdate() {
        io.execute {
            try {
                val c = URL(RELEASES_API).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 15000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val body = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name")
                val latest = tag.substringAfterLast('.').toIntOrNull() ?: return@execute
                if (latest <= myVersionCode()) return@execute
                val assets = json.optJSONArray("assets") ?: return@execute
                var url: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
                }
                val apkUrl = url ?: return@execute
                ui.post { askUpdate(tag, apkUrl) }
            } catch (_: Exception) {
            }
        }
    }

    private fun askUpdate(tag: String, apkUrl: String) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("تحديث جديد")
            .setMessage("فيه نسخة جديدة $tag\nتحب تحدّث دلوقتي؟")
            .setPositiveButton("تحديث") { _, _ -> downloadAndInstall(apkUrl) }
            .setNegativeButton("بعدين", null)
            .show()
    }

    private fun downloadAndInstall(apkUrl: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "اسمح لـ Enigma TV بتثبيت التطبيقات وبعدين ارجع", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } catch (_: Exception) {}
            }
            updateChecked = false
            return
        }
        showStatus("جاري تحميل التحديث...")
        io.execute {
            try {
                val out = File(cacheDir, "enigma-tv-update.apk")
                var link = apkUrl
                var conn: HttpURLConnection
                var hops = 0
                while (true) {
                    conn = URL(link).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 10000
                    conn.readTimeout = 30000
                    val code = conn.responseCode
                    if (code in 300..399 && hops < 5) {
                        link = conn.getHeaderField("Location")
                        conn.disconnect()
                        hops++
                        continue
                    }
                    break
                }
                conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                conn.disconnect()
                ui.post {
                    status.visibility = View.GONE
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", out)
                    val intent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
            } catch (e: Exception) {
                ui.post { showStatus("فشل تحميل التحديث\n${e.message ?: ""}") ; ui.postDelayed({ status.visibility = View.GONE }, 4000) }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun host(): String = (prefs.getString("host", DEFAULT_HOST) ?: DEFAULT_HOST)
        .removePrefix("http://").removePrefix("https://").trimEnd('/')

    private fun authHeader(): String? {
        val u = prefs.getString("user", "") ?: ""
        if (u.isBlank()) return null
        val p = prefs.getString("pass", "") ?: ""
        return "Basic " + Base64.encodeToString("$u:$p".toByteArray(), Base64.NO_WRAP)
    }

    private fun httpGet(path: String): String {
        val c = URL("http://${host()}$path").openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 15000
        authHeader()?.let { c.setRequestProperty("Authorization", it) }
        try {
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun streamUrl(ref: String): String {
        val parts = ref.split(":")
        if (parts.size > 10 && parts[10].startsWith("http", ignoreCase = true)) {
            return URLDecoder.decode(parts[10], "UTF-8")
        }
        val hostOnly = host().substringBefore(':')
        return "http://$hostOnly:8001/$ref"
    }

    private fun syncMain() = prefs.getBoolean("syncMain", true)

    private fun isIptv(ref: String): Boolean {
        val parts = ref.split(":")
        return parts.size > 10 && parts[10].startsWith("http", ignoreCase = true)
    }

    private fun isMarker(ref: String): Boolean {
        val flags = ref.split(":").getOrNull(1)?.toIntOrNull() ?: 0
        return (flags and 64) != 0
    }

    private fun norm(ref: String) = ref.trim().trimEnd(':').uppercase(Locale.US)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------------------------------------------------------------- adapter

    class RowAdapter(private val ctx: Context) : BaseAdapter() {
        var rows: List<Row> = emptyList()
            set(value) { field = value; notifyDataSetChanged() }

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_row, parent, false)
            val r = rows[position]
            val num = v.findViewById<TextView>(R.id.num)
            num.text = r.num
            num.visibility = if (r.num.isEmpty()) View.GONE else View.VISIBLE
            v.findViewById<TextView>(R.id.title).text = r.title
            val pic = v.findViewById<ImageView>(R.id.picon)
            if (r.showPicon) {
                pic.visibility = View.VISIBLE
                Picons.load(r.picon, pic)
            } else {
                pic.visibility = View.GONE
            }
            val sub = v.findViewById<TextView>(R.id.sub)
            sub.text = r.sub
            sub.visibility = if (r.sub.isEmpty()) View.GONE else View.VISIBLE
            return v
        }
    }

    companion object {
        const val DEFAULT_HOST = "192.168.1.169"
        val RESIZE_MODES = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        )
        val RESIZE_NAMES = arrayOf("عادي", "تكبير (يملا الشاشة)", "مطّ")
        const val RELEASES_API = "https://api.github.com/repos/elzenouki/enigma-tv/releases/latest"
    }
}
