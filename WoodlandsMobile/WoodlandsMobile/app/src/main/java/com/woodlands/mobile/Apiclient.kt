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

    private const val HOSTED_BASE_URL =
        "https://insy7315-api-repository.onrender.com"

    private const val LOCAL_BASE_URL =
        "http://10.0.2.2:5000"

    const val WEB_BASE_URL =
        "https://insy7315-g3-2026-insy7315-2026-project-xfft.onrender.com"
    private val jsonType =
        "application/json; charset=utf-8".toMediaType()

    /** JWT returned by /api/auth/login. Set by Session; sent as "Authorization: Bearer ..." on every call. */
    @Volatile
    var authToken: String? = null

    /** Called when the API rejects the saved token (expired, or the account was disabled/removed). */
    @Volatile
    var onUnauthorized: (() -> Unit)? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val httpSlow = http.newBuilder()
        .readTimeout(70, TimeUnit.SECONDS)
        .build()

    fun get(path: String): ApiResult =
        call(path) { url ->
            Request.Builder()
                .url(url)
                .get()
                .build()
        }

    fun post(path: String, body: String): ApiResult =
        call(path) { url ->
            Request.Builder()
                .url(url)
                .post(body.toRequestBody(jsonType))
                .build()
        }

    fun put(path: String, body: String): ApiResult =
        call(path) { url ->
            Request.Builder()
                .url(url)
                .put(body.toRequestBody(jsonType))
                .build()
        }

    fun delete(path: String): ApiResult =
        call(path) { url ->
            Request.Builder()
                .url(url)
                .delete()
                .build()
        }

    private fun call(
        path: String,
        requestFactory: (String) -> Request
    ): ApiResult {

        val hostedUrl = HOSTED_BASE_URL + path

        try {
            return execute(requestFactory(hostedUrl))
        } catch (e: IOException) {
            // Railway could not be reached.
            // Only now do we try the local API.
        }

        val localUrl = LOCAL_BASE_URL + path

        return try {
            execute(requestFactory(localUrl))
        } catch (e: IOException) {
            ApiResult.Offline(e)
        }
    }

    private fun execute(original: Request): ApiResult {
        // Login/register are public - everything else carries the signed-in user's token
        val token = authToken
        val sendToken = token != null && !original.url.encodedPath.startsWith("/api/auth/")
        val request = if (sendToken) {
            original.newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            original
        }

        val client = if (request.method == "GET") http else httpSlow
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()

            if (response.code == 401 && sendToken) {
                onUnauthorized?.invoke()
            }

            if (response.isSuccessful) {
                ApiResult.Success(text)
            } else {
                ApiResult.Failure(
                    response.code,
                    errorMessage(text, response.code)
                )
            }
        }
    }

    private fun errorMessage(body: String, code: Int): String =
        try {
            JSONObject(body)
                .optString("error")
                .ifBlank { "Request failed ($code)" }
        } catch (e: Exception) {
            "Request failed ($code)"
        }
}