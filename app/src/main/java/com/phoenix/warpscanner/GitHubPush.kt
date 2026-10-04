package com.phoenix.warpscanner

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Pushes endpoints.json to the Warp-IP-Scanner repo via the GitHub
 * contents API. Call off the main thread.
 *
 * The token is used only in the Authorization header and is never logged.
 */
object GitHubPush {

    private const val API =
        "https://api.github.com/repos/myokooo2004/Warp-IP-Scanner/contents/endpoints.json"

    sealed class Result {
        object Ok : Result()
        data class Err(val reason: String) : Result()
    }

    fun push(token: String, json: String): Result {
        // One retry on timeout — mobile networks stall transiently.
        var attempt = 0
        while (true) {
            try {
                val sha = getSha(token) // null when the file doesn't exist yet
                return put(token, json, sha)
            } catch (e: SocketTimeoutException) {
                if (++attempt >= 2) return Result.Err("timed out — check connection")
            } catch (e: Exception) {
                return Result.Err(e.message ?: "network error")
            }
        }
    }

    /** Current blob sha, or null if the file doesn't exist yet (404). */
    private fun getSha(token: String): String? {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 30000
            readTimeout = 60000
        }
        try {
            return when (c.responseCode) {
                200 -> JSONObject(c.inputStream.bufferedReader().readText())
                    .optString("sha", null)
                404 -> null
                else -> throw Exception("GitHub GET failed: HTTP ${c.responseCode}")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun put(token: String, json: String, sha: String?): Result {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 30000
            readTimeout = 60000
        }
        try {
            val body = JSONObject().apply {
                put("message", "Update endpoints.json from scanner")
                put(
                    "content",
                    Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                )
                if (sha != null) put("sha", sha)
            }.toString()
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code == 200 || code == 201) return Result.Ok
            val errBody = try {
                c.errorStream?.bufferedReader()?.readText()?.take(300)
            } catch (_: Exception) {
                null
            }
            val reason = try {
                errBody?.let { JSONObject(it).optString("message", null) }
            } catch (_: Exception) {
                null
            } ?: "HTTP $code"
            return Result.Err(reason)
        } finally {
            c.disconnect()
        }
    }
}
