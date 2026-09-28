package dev.gab8bit.madovai.service

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.gab8bit.madovai.model.AtacStop
import dev.gab8bit.madovai.model.FavoriteStop
import dev.gab8bit.madovai.model.Pole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val Context.favoritesDataStore: DataStore<Preferences> by preferencesDataStore(name = "favorites")

/**
 * Favorite stops, purely local (Jetpack DataStore). ONE persisted list for both
 * operators, with namespaced ids ("cotral:" / "atac:") so colliding numeric codes can
 * never merge two different favorites.
 */
class FavoritesStore(context: Context, private val scope: CoroutineScope) {
    private val dataStore = context.applicationContext.favoritesDataStore
    private val key = stringPreferencesKey("dev.gab8bit.madovai.favoritePoles")

    private val _favorites = MutableStateFlow<List<FavoriteStop>>(emptyList())
    val favorites: StateFlow<List<FavoriteStop>> = _favorites.asStateFlow()
    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()

    private var loaded = false
    private val pendingToggles = ArrayList<FavoriteStop>()

    init {
        scope.launch {
            val json = dataStore.data.first()[key]
            val decoded = json?.let { decode(it) } ?: emptyList()
            apply(decoded)
            loaded = true
            // Toggles that happened before the initial load finished.
            val pending = pendingToggles.toList()
            pendingToggles.clear()
            pending.forEach { toggle(it) }
            if (decoded.size != (json?.let { rawCount(it) } ?: 0)) persist()
        }
    }

    fun isFavoritePole(poleCode: String): Boolean = "cotral:$poleCode" in _favoriteIds.value
    fun isFavoriteAtacStop(stopId: String): Boolean = "atac:$stopId" in _favoriteIds.value

    fun toggle(pole: Pole) {
        if (pole.codicePalina == null) return
        toggle(FavoriteStop.Cotral(pole))
    }

    fun toggle(stop: AtacStop) = toggle(FavoriteStop.Atac(stop))

    private fun toggle(item: FavoriteStop) {
        if (!loaded) {
            pendingToggles.add(item)
            return
        }
        val ids = _favoriteIds.value
        if (item.id in ids) {
            _favoriteIds.value = ids - item.id
            _favorites.value = _favorites.value.filterNot { it.id == item.id }
        } else {
            _favoriteIds.value = ids + item.id
            _favorites.value = _favorites.value + item
        }
        persist()
    }

    /** Keeps the first occurrence of each id (self-heals any duplicated entries). */
    private fun apply(items: List<FavoriteStop>) {
        val seen = LinkedHashSet<String>()
        val deduped = items.filter { seen.add(it.id) }
        _favorites.value = deduped
        _favoriteIds.value = seen
    }

    private fun persist() {
        val snapshot = encode(_favorites.value)
        scope.launch { dataStore.edit { it[key] = snapshot } }
    }

    // region JSON

    private fun encode(items: List<FavoriteStop>): String {
        val array = JSONArray()
        for (item in items) {
            val o = JSONObject()
            when (item) {
                is FavoriteStop.Cotral -> o.put("type", "cotral").put("pole", poleToJson(item.pole))
                is FavoriteStop.Atac -> o.put("type", "atac").put(
                    "stop",
                    JSONObject().put("stopId", item.stop.stopId).put("stopName", item.stop.stopName)
                        .put("lat", item.stop.lat).put("lon", item.stop.lon),
                )
            }
            array.put(o)
        }
        return array.toString()
    }

    private fun rawCount(json: String): Int = runCatching { JSONArray(json).length() }.getOrDefault(0)

    private fun decode(json: String): List<FavoriteStop> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            when (o.optString("type")) {
                "cotral" -> o.optJSONObject("pole")?.let { FavoriteStop.Cotral(poleFromJson(it)) }
                "atac" -> o.optJSONObject("stop")?.let { s ->
                    val id = s.optString("stopId")
                    if (id.isEmpty()) null
                    else FavoriteStop.Atac(AtacStop(id, s.optString("stopName"), s.optDouble("lat", 0.0), s.optDouble("lon", 0.0)))
                }
                else -> null
            }
        }
    }.getOrDefault(emptyList())

    private fun poleToJson(p: Pole): JSONObject = JSONObject().apply {
        p.codicePalina?.let { put("codicePalina", it) }
        p.codiceStop?.let { put("codiceStop", it) }
        p.nomePalina?.let { put("nomePalina", it) }
        p.nomeStop?.let { put("nomeStop", it) }
        p.localita?.let { put("localita", it) }
        p.comune?.let { put("comune", it) }
        p.coordX?.let { put("coordX", it) }
        p.coordY?.let { put("coordY", it) }
        p.destinazioni?.let { put("destinazioni", JSONArray(it)) }
        p.isCotral?.let { put("isCotral", it) }
        put("isTreno", p.isTreno)
    }

    private fun poleFromJson(o: JSONObject): Pole {
        fun str(k: String): String? = if (o.has(k) && !o.isNull(k)) o.optString(k) else null
        fun dbl(k: String): Double? = if (o.has(k) && !o.isNull(k)) o.optDouble(k) else null
        return Pole(
            codicePalina = str("codicePalina"),
            codiceStop = str("codiceStop"),
            nomePalina = str("nomePalina"),
            nomeStop = str("nomeStop"),
            localita = str("localita"),
            comune = str("comune"),
            coordX = dbl("coordX"),
            coordY = dbl("coordY"),
            destinazioni = o.optJSONArray("destinazioni")?.let { a -> (0 until a.length()).map { a.optString(it) } },
            isCotral = if (o.has("isCotral")) o.optInt("isCotral") else null,
            isTreno = o.optBoolean("isTreno", false),
        )
    }

    // endregion
}
