package com.tellybox.tv.data.parser.xtream

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.TimeZone

class XtreamClient(
    val credentials: XtreamCredentials,
    httpClient: OkHttpClient,
) {
    private val service: XtreamService = Retrofit.Builder()
        .baseUrl(credentials.server + "/")
        .client(httpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(XtreamService::class.java)

    private suspend fun call(action: String?, params: Map<String, String> = emptyMap()) =
        service.call(credentials.username, credentials.password, action, params)

    /** Checks the login. Throws [XtreamAuthException] if the panel rejects it. */
    suspend fun authenticate(): XtreamAccount = XtreamMapper.checkLogin(XtreamMapper.account(call(null)))

    suspend fun liveCategories() = XtreamMapper.categories(call("get_live_categories"))
    suspend fun liveStreams(categoryId: String? = null) =
        XtreamMapper.liveStreams(call("get_live_streams", categoryParam(categoryId)))

    suspend fun vodCategories() = XtreamMapper.categories(call("get_vod_categories"))
    suspend fun vodStreams(categoryId: String? = null) =
        XtreamMapper.vodStreams(call("get_vod_streams", categoryParam(categoryId)))

    suspend fun seriesCategories() = XtreamMapper.categories(call("get_series_categories"))
    suspend fun series(categoryId: String? = null) =
        XtreamMapper.series(call("get_series", categoryParam(categoryId)))

    suspend fun vodInfo(streamId: Long) =
        XtreamMapper.vodInfo(call("get_vod_info", mapOf("vod_id" to streamId.toString())))

    suspend fun seriesInfo(seriesId: Long) =
        XtreamMapper.seriesInfo(call("get_series_info", mapOf("series_id" to seriesId.toString())))

    suspend fun shortEpg(streamId: Long, limit: Int = 4, serverTimeZone: TimeZone? = null) =
        XtreamMapper.epgListings(
            call("get_short_epg", mapOf("stream_id" to streamId.toString(), "limit" to limit.toString())),
            serverTimeZone,
        )

    /** Full EPG for one stream, including past programmes flagged with archive availability. */
    suspend fun fullEpg(streamId: Long, serverTimeZone: TimeZone? = null) =
        XtreamMapper.epgListings(
            call("get_simple_data_table", mapOf("stream_id" to streamId.toString())),
            serverTimeZone,
        )

    private fun categoryParam(id: String?) = if (id == null) emptyMap() else mapOf("category_id" to id)

    companion object {
        internal val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
