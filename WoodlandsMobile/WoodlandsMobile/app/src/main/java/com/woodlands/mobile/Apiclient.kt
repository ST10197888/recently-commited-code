package com.woodlands.mobile

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class ApiResult {
    data class Success(val body: String) : ApiResult()
    data class Failure(val code: Int, val message: String) : ApiResult()
    data class Offline(val cause: IOException) : ApiResult()
}

object ApiClient {
    const val BASE_URL = "http://10.0.2.2:5000"

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun get(path: String): ApiResult =
        call(Request.Builder().url(BASE_URL + path).get().build())

    fun post(path: String, body: String): ApiResult =
        call(Request.Builder().url(BASE_URL + path).post(body.toRequestBody(jsonType)).build())

    fun put(path: String, body: String): ApiResult =
        call(Request.Builder().url(BASE_URL + path).put(body.toRequestBody(jsonType)).build())

    fun delete(path: String): ApiResult =
        call(Request.Builder().url(BASE_URL + path).delete().build())

    private fun call(request: Request): ApiResult = try {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.isSuccessful) ApiResult.Success(text)
            else ApiResult.Failure(response.code, errorMessage(text, response.code))
        }
    } catch (e: IOException) {
        ApiResult.Offline(e)
    }

    private fun errorMessage(body: String, code: Int): String = try {
        JSONObject(body).optString("error").ifBlank { "Request failed ($code)" }
    } catch (e: Exception) {
        "Request failed ($code)"
    }
}