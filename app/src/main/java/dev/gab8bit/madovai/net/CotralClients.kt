package dev.gab8bit.madovai.net

import dev.gab8bit.madovai.Config
import dev.gab8bit.madovai.model.AstralStation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import kotlin.math.abs

object CotralTimeUtils {
    /**
     * Cotral's raw XML gives times as integer seconds; converts to "HH:MM" (optionally
     * "-HH:MM"), like the reference server's `convertToReadableTime`.
     *
     * @param wrapClockAt24 for times of day, wrap e.g. "24:48" → "00:48". `ritardo` is a
     *   duration, not a time of day, so it must never be wrapped.
     */
    fun readableTime(raw: String, wrapClockAt24: Boolean = true): String {
        val seconds = raw.trim().toIntOrNull() ?: return "00:00"
        val sign = if (seconds < 0) "-" else ""
        val absValue = abs(seconds)
        val totalMinutes = absValue / 60
        var hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        if (wrapClockAt24) hours %= 24
        return sign + String.format(Locale.ROOT, "%02d", hours) + ":" + String.format(Locale.ROOT, "%02d", minutes)
    }

    /**
     * Cotral's cmd=1 has lat/lon swapped for many poles. In Lazio latitude is ~41-43 and
     * longitude ~11-14; if "latitude" is under 20 it's really longitude.
     */
    fun normalizeLatLon(lat: Double, lon: Double): Pair<Double, Double> =
        if (lat > 0 && lat < 20 && lon > 20) lon to lat else lat to lon
}

/** Talks directly to Cotral's own internal live-data endpoint and parses its XML. */
class CotralXmlClient(private val client: OkHttpClient = HttpClients.api) {
    /** @return the parsed root node, or null if Cotral had no data for this query. */
    suspend fun fetchXml(endpoint: String, params: Map<String, String>): XmlNode? {
        val base = "${Config.COTRAL_BASE_URL}/$endpoint".toHttpUrlOrNull() ?: throw ApiException.InvalidUrl()
        val url = base.newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val bytes = client.getBytes(url.toString())
        return withContext(Dispatchers.Default) { XmlNodeParser.parse(bytes) }
    }
}

/** Raw `/api/transit` entry. An empty `ritardo` means "not yet computed", not zero. */
data class AstralTransitDto(
    val orario: String,
    val corsa: String,
    val ritardo: String,
    val soppressa: String,
    val busSostitutivo: String,
)

/**
 * ASTRAL's live-schedule API (gestionecorse.astralspa.it) — public, no auth, JSON POST.
 * Completely separate backend from Cotral's PIV.do.
 */
class AstralTrainClient(private val client: OkHttpClient = HttpClients.api) {
    /** `POST /api/fermate/<percorso>` — ordered station list for one line+direction. */
    suspend fun fetchStations(percorso: String): List<AstralStation> {
        val array = postArray("fermate/$percorso", JSONObject())
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("nomeFermata", "")
            val code = o.optString("codice", "")
            if (code.isEmpty()) return@mapNotNull null
            AstralStation(nomeFermata = name, codice = code, ordine = o.optInt("ordine", 0))
        }
    }

    /** `POST /api/transit` — one station's entire day of passages for a line+direction. */
    suspend fun fetchTransits(percorso: String, fermata: String): List<AstralTransitDto> {
        val body = JSONObject().put("percorso", percorso).put("fermata", fermata)
        val array = postArray("transit", body)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            AstralTransitDto(
                orario = o.optStringOrEmpty("orario"),
                corsa = o.optStringOrEmpty("corsa"),
                ritardo = o.optStringOrEmpty("ritardo"),
                soppressa = o.optStringOrEmpty("soppressa"),
                busSostitutivo = o.optStringOrEmpty("busSostitutivo"),
            )
        }
    }

    private suspend fun postArray(path: String, body: JSONObject): JSONArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${Config.ASTRAL_BASE_URL}/$path")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw ApiException.Network(e.message)
        }
        val text = response.use {
            if (!it.isSuccessful) throw ApiException.Http(it.code)
            try { it.body?.string() ?: "" } catch (e: IOException) { throw ApiException.Network(e.message) }
        }
        try {
            JSONArray(text)
        } catch (e: JSONException) {
            throw ApiException.NoData()
        }
    }

    /** JSON null → "" (org.json's optString would return the literal "null"). */
    private fun JSONObject.optStringOrEmpty(key: String): String =
        if (isNull(key)) "" else optString(key, "")
}
