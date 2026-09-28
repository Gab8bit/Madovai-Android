package dev.gab8bit.madovai.net

import dev.gab8bit.madovai.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Mirrors the iOS `APIError` user-facing messages. */
sealed class ApiException(message: String) : Exception(message) {
    class Network(detail: String?) : ApiException("Impossibile contattare Cotral. Controlla la connessione.") {
        val detail: String? = detail
    }
    class Http(val status: Int) : ApiException("Cotral ha risposto con un errore ($status).")
    class InvalidUrl : ApiException("URL non valido.")
    class NoData : ApiException("Nessun dato disponibile al momento.")
}

object HttpClients {
    /**
     * Short-timeout client for live polling. OkHttp has no response cache unless one is
     * configured, so repeated identical polls always hit the network (the iOS app had to
     * switch to an ephemeral URLSession to get the same guarantee).
     */
    val api: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Config.REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(Config.REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(Config.REQUEST_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
            .build()
    }

    /** Long-timeout client for the GTFS zip downloads (tens of MB). */
    val download: OkHttpClient by lazy {
        api.newBuilder()
            .readTimeout(Config.GTFS_DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }
}

/** Cancellable suspend wrapper around an OkHttp call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** GET → body bytes, mapping failures to [ApiException]. */
suspend fun OkHttpClient.getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
    // The whole exchange INCLUDING the body read must run off the main thread: await()
    // resumes on the caller's dispatcher, and body reads hit the socket (Android throws
    // NetworkOnMainThreadException).
    val request = Request.Builder().url(url).get().build()
    val response = try {
        newCall(request).await()
    } catch (e: IOException) {
        throw ApiException.Network(e.message)
    }
    response.use {
        if (!it.isSuccessful) throw ApiException.Http(it.code)
        try {
            it.body?.bytes() ?: ByteArray(0)
        } catch (e: IOException) {
            throw ApiException.Network(e.message)
        }
    }
}
