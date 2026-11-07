package com.tellybox.tv.data.parser.xtream

import kotlinx.serialization.json.JsonElement
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.QueryMap

interface XtreamService {
    @GET("player_api.php")
    suspend fun call(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String? = null,
        @QueryMap params: Map<String, String> = emptyMap(),
    ): JsonElement
}
