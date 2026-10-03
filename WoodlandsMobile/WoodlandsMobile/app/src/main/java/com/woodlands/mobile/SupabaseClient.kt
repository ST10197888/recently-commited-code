package com.woodlands.mobile

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

// DataStore keys for auth tokens
val Context.dataStore by preferencesDataStore(name = "auth")
private val ACCESS_TOKEN_KEY = stringPreferencesKey("access_token")
private val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")

object SupabaseClient {
    private const val BASE_URL = "https://hgpwxbmkkerbobtyvnjx.supabase.co"
    private const val ANON_KEY = "sb_publishable_Ar_sAP3XB_t8EVPA4td9Iw_RSk63Eoy"

    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()


    suspend fun get(
        endpoint: String,
        accessToken: String? = null
    ): ApiResponse<String> = try {
        val requestBuilder = Request.Builder()
            .url("$BASE_URL$endpoint")
            .header("apikey", ANON_KEY)
            .header("Content-Type", "application/json")

        if (accessToken != null) {
            requestBuilder.header("Authorization", "Bearer $accessToken")
        }

        val response = httpClient.newCall(requestBuilder.build()).execute()
        val body = response.body?.string() ?: ""

        if (response.isSuccessful) {
            ApiResponse.Success(body)
        } else {
            ApiResponse.Error(response.code, body)
        }
    } catch (e: Exception) {
        ApiResponse.Exception(e)
    }

    suspend fun post(
        endpoint: String,
        body: Any,
        accessToken: String? = null
    ): ApiResponse<String> = try {
        val jsonBody = gson.toJson(body).toRequestBody("application/json".toMediaType())

        val requestBuilder = Request.Builder()
            .url("$BASE_URL$endpoint")
            .post(jsonBody)
            .header("apikey", ANON_KEY)
            .header("Content-Type", "application/json")

        if (accessToken != null) {
            requestBuilder.header("Authorization", "Bearer $accessToken")
        }

        val response = httpClient.newCall(requestBuilder.build()).execute()
        val responseBody = response.body?.string() ?: ""

        if (response.isSuccessful) {
            ApiResponse.Success(responseBody)
        } else {
            ApiResponse.Error(response.code, responseBody)
        }
    } catch (e: Exception) {
        ApiResponse.Exception(e)
    }

    suspend fun patch(
        endpoint: String,
        body: Any,
        accessToken: String? = null
    ): ApiResponse<String> = try {
        val jsonBody = gson.toJson(body).toRequestBody("application/json".toMediaType())

        val requestBuilder = Request.Builder()
            .url("$BASE_URL$endpoint")
            .patch(jsonBody)
            .header("apikey", ANON_KEY)
            .header("Content-Type", "application/json")

        if (accessToken != null) {
            requestBuilder.header("Authorization", "Bearer $accessToken")
        }

        val response = httpClient.newCall(requestBuilder.build()).execute()
        val responseBody = response.body?.string() ?: ""

        if (response.isSuccessful) {
            ApiResponse.Success(responseBody)
        } else {
            ApiResponse.Error(response.code, responseBody)
        }
    } catch (e: Exception) {
        ApiResponse.Exception(e)
    }
}


sealed class ApiResponse<T> {
    data class Success<T>(val data: T) : ApiResponse<T>()
    data class Error<T>(val code: Int, val message: String) : ApiResponse<T>()
    data class Exception<T>(val exception: kotlin.Exception) : ApiResponse<T>()
}


class AuthTokenManager(private val context: Context) {

    suspend fun saveTokens(accessToken: String, refreshToken: String) {
        context.dataStore.updateData { preferences ->
            preferences
                .toMutablePreferences()
                .apply {
                    this[ACCESS_TOKEN_KEY] = accessToken
                    this[REFRESH_TOKEN_KEY] = refreshToken
                }
        }
    }

    fun getAccessTokenFlow(): Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[ACCESS_TOKEN_KEY]
    }

    fun getRefreshTokenFlow(): Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[REFRESH_TOKEN_KEY]
    }

    suspend fun clearTokens() {
        context.dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                remove(ACCESS_TOKEN_KEY)
                remove(REFRESH_TOKEN_KEY)
            }
        }
    }
}