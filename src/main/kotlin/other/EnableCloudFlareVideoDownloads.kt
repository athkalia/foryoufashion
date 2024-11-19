package other

import cloudFlareAccountId
import cloudFlareStreamApiToken
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import okhttp3.*

@Serializable
data class VideoResponse(val result: List<Video>)

@Serializable
data class CloudflareErrorResponse(
    val success: Boolean,
    val errors: List<ErrorDetail>
)

@Serializable
data class ErrorDetail(
    val code: Int,
    val message: String
)


@Serializable
data class Video(val uid: String)

fun main() = runBlocking {
    val apiToken = cloudFlareStreamApiToken
    val accountId = cloudFlareAccountId
    val client = OkHttpClient()

    val videoUids = fetchVideoUids(client, apiToken, accountId)

    for (uid in videoUids) {
        enableMp4Download(client, apiToken, accountId, uid)
    }
}

fun fetchVideoUids(client: OkHttpClient, apiToken: String, accountId: String): List<String> {
    val request = Request.Builder()
        .url("https://api.cloudflare.com/client/v4/accounts/$accountId/stream")
        .addHeader("Authorization", "Bearer $apiToken")
        .build()

    val response = client.newCall(request).execute()
    val body = response.body?.string() ?: throw Exception("Failed to fetch video list")

    // Try to parse the response as a CloudflareErrorResponse
    val json = Json { ignoreUnknownKeys = true }
    val errorResponse = runCatching { json.decodeFromString<CloudflareErrorResponse>(body) }.getOrNull()

    if (errorResponse!=null && !errorResponse.success) {
        // If there's an error in the response, handle it
        println("Error: ${errorResponse.errors.joinToString { it.message }}")
        throw Exception("Authentication or other error occurred.")
    }

    return try {
        val videoResponse = json.decodeFromString<VideoResponse>(body)
        videoResponse.result.map { it.uid }
    } catch (e: Exception) {
        println("Failed to parse video data: ${e.message}")
        throw e
    }
}

fun enableMp4Download(client: OkHttpClient, apiToken: String, accountId: String, videoUid: String) {
    val request = Request.Builder()
        .url("https://api.cloudflare.com/client/v4/accounts/$accountId/stream/$videoUid/downloads")
        .post(RequestBody.create(null, ""))
        .addHeader("Authorization", "Bearer $apiToken")
        .build()

    val response = client.newCall(request).execute()

    // Check if the request was successful
    if (response.isSuccessful) {
        println("Enabled MP4 download for video UID: $videoUid")
    } else {
        // Print the response body to help with debugging
        val errorBody = response.body?.string() ?: "No error message"
        println("Failed to enable MP4 download for video UID: $videoUid")
        println("Response Code: ${response.code}")
        println("Error Body: $errorBody")
    }
}
