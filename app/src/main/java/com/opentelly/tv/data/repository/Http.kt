package com.opentelly.tv.data.repository

import android.content.Context
import android.net.Uri
import com.opentelly.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

const val DEFAULT_USER_AGENT = "OpenTelly/${BuildConfig.VERSION_NAME} (Android TV)"

fun createHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    // Guide and playlist downloads can be slow, large and bursty.
    .readTimeout(90, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .retryOnConnectionFailure(true)
    .addInterceptor { chain ->
        val req = chain.request()
        if (req.header("User-Agent") != null) chain.proceed(req)
        else chain.proceed(req.newBuilder().header("User-Agent", DEFAULT_USER_AGENT).build())
    }
    .build()

/**
 * Opens an http(s) URL, a content:// URI picked through the system file picker, or a
 * plain file path, and hands the stream to [block]. The stream is closed afterwards.
 */
suspend fun <T> openSource(
    context: Context,
    http: OkHttpClient,
    location: String,
    userAgent: String? = null,
    block: (InputStream) -> T,
): T = withContext(Dispatchers.IO) {
    val trimmed = location.trim()
    when {
        trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> {
            val req = Request.Builder().url(trimmed)
                .apply { if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent) }
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Server returned HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("Empty response")
                body.byteStream().use(block)
            }
        }
        trimmed.startsWith("content://", true) || trimmed.startsWith("file://", true) -> {
            val stream = context.contentResolver.openInputStream(Uri.parse(trimmed))
                ?: throw IOException("Can't open $trimmed")
            stream.use(block)
        }
        else -> File(trimmed).inputStream().use(block)
    }
}

/** Shares the connection pool while applying the playlist's provider request header. */
internal fun OkHttpClient.withUserAgent(value: String?): OkHttpClient =
    if (value.isNullOrBlank()) this else newBuilder().addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", value).build())
    }.build()
