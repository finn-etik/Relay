package dev.field.dial

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Holds DIAL's state for the screen and the playback service, and persists
 * the parts that outlive a session (stations, selection, total listening
 * time, volume, RETRY). Everything is called on the main thread.
 */
object DialStore {

    private const val FILE = "field_dial"
    private const val STATIONS = "STATIONS"
    private const val SEL = "SEL"
    private const val TOTAL_MS = "TOTAL_MS"
    private const val VOL = "VOL"
    private const val RETRY = "RETRY"

    private val _state = MutableStateFlow(DialState())
    val state: StateFlow<DialState> = _state.asStateFlow()

    /** Last catalog search; kept here so it survives rotation. Not persisted. */
    val results = MutableStateFlow<List<SearchHit>>(emptyList())

    /** Set by the running DialService: volume, mute or the sleep timer changed. */
    var onControls: (() -> Unit)? = null

    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val list = ArrayList<Station>()
        try {
            val arr = JSONArray(p.getString(STATIONS, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Station(o.getInt("id"), o.getString("n"), o.getString("u")))
            }
        } catch (_: Exception) {
            list.clear()
        }
        _state.value = DialState(
            // Seed only a phone that never had a list; an emptied list stays empty.
            stations = if (list.isEmpty() && !p.contains(STATIONS)) Dial.SEED else list,
            selId = p.getInt(SEL, -1),
            totalBankedMs = p.getLong(TOTAL_MS, 0L),
            vol = p.getInt(VOL, 7).coerceIn(0, Dial.MAX_VOLUME),
            retry = p.getBoolean(RETRY, true),
        )
    }

    @Synchronized
    fun update(context: Context, change: (DialState) -> DialState) {
        val before = _state.value
        val after = change(before)
        if (after == before) return
        _state.value = after

        val saved = before.stations != after.stations || before.selId != after.selId ||
            before.totalBankedMs != after.totalBankedMs || before.vol != after.vol || before.retry != after.retry
        if (saved) {
            val edit = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            if (before.stations != after.stations) {
                val arr = JSONArray()
                after.stations.forEach { arr.put(JSONObject().put("id", it.id).put("n", it.name).put("u", it.url)) }
                edit.putString(STATIONS, arr.toString())
            }
            edit.putInt(SEL, after.selId)
                .putLong(TOTAL_MS, after.totalBankedMs)
                .putInt(VOL, after.vol)
                .putBoolean(RETRY, after.retry)
                .apply()
        }
        if (before.vol != after.vol || before.muted != after.muted || before.sleepAt != after.sleepAt) onControls?.invoke()
    }
}
