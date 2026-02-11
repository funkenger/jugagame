package com.jugaplatform

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import java.net.URLEncoder
import java.util.UUID
import kotlin.coroutines.resume

@Serializable
data class RemotePayload(
    val game: RemoteGame = RemoteGame(),
    val ui: RemoteUi = RemoteUi(),
    val leaderboard: RemoteLeaderboard = RemoteLeaderboard()
)

@Serializable
data class RemoteGame(
    val name: String = "JugaPlatform",
    val metric: String = "distance_m",
    @SerialName("record_label") val recordLabel: String = "Mejor distancia"
)

@Serializable
data class RemoteUi(
    val button1: RemoteButton = RemoteButton(id = "start", type = "bigbutton", action = "startgame", title = "Iniciar"),
    val button2: RemoteButton = RemoteButton(id = "policy", type = "smallbutton", action = "policy", title = "Política", url = "https://jugalatamgame.com/policy.php")
)

@Serializable
data class RemoteButton(
    val id: String,
    val type: String,
    val action: String,
    val title: String,
    val url: String? = null
)

@Serializable
data class RemoteLeaderboard(
    val best: LeaderboardItem = LeaderboardItem("Alejandro", 13240, "2026-02-02"),
    val history: List<LeaderboardItem> = emptyList()
)

@Serializable
data class LeaderboardItem(
    val player: String,
    @SerialName("distance_m") val distanceM: Int,
    val date: String
)

private const val PREFS = "juga_prefs"
private const val KEY_UUID = "client_uuid"
private const val KEY_REF = "install_referrer"
private const val KEY_NICK = "nickname"
private const val KEY_BEST_DISTANCE = "best_distance"

class AppStorage(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getOrCreateUuid(): String {
        val existing = prefs.getString(KEY_UUID, null)
        if (existing != null) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_UUID, created).apply()
        return created
    }

    fun saveInstallReferrer(value: String) = prefs.edit().putString(KEY_REF, value).apply()
    fun installReferrer(): String = prefs.getString(KEY_REF, "organic") ?: "organic"

    fun nickname(): String? = prefs.getString(KEY_NICK, null)
    fun saveNickname(value: String) = prefs.edit().putString(KEY_NICK, value).apply()

    fun bestDistance(): Int = prefs.getInt(KEY_BEST_DISTANCE, 0)
    fun saveBestDistance(value: Int) = prefs.edit().putInt(KEY_BEST_DISTANCE, value).apply()
}

suspend fun fetchInstallReferrer(context: Context): String = suspendCancellableCoroutine { cont ->
    val client = InstallReferrerClient.newBuilder(context).build()
    client.startConnection(object : InstallReferrerStateListener {
        override fun onInstallReferrerSetupFinished(responseCode: Int) {
            val referrer = runCatching {
                if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                    client.installReferrer.installReferrer
                } else {
                    "organic"
                }
            }.getOrDefault("organic")
            runCatching { client.endConnection() }
            if (cont.isActive) cont.resume(referrer.ifBlank { "organic" })
        }

        override fun onInstallReferrerServiceDisconnected() {
            if (cont.isActive) cont.resume("organic")
        }
    })
}

suspend fun loadRemotePayload(referrer: String, uuid: String): RemotePayload = withContext(Dispatchers.IO) {
    val json = Json { ignoreUnknownKeys = true }
    val encodedRef = URLEncoder.encode(referrer, "UTF-8")
    val encodedId = URLEncoder.encode(uuid, "UTF-8")
    val url = "https://jugalatamgame.com/json.php?installReferrer=$encodedRef&uuid=$encodedId"
    val client = OkHttpClient.Builder().build()
    val request = Request.Builder().url(url).build()

    runCatching {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            json.decodeFromString<RemotePayload>(body)
        }
    }.getOrElse {
        // fallback from prompt sample
        json.decodeFromString(
            RemotePayload.serializer(),
            """{
              "game": {"name":"JugaPlatform","metric":"distance_m","record_label":"Mejor distancia"},
              "ui": {
                "button1":{"id":"start","type":"bigbutton","action":"startgame","title":"Iniciar"},
                "button2":{"id":"policy","type":"smallbutton","action":"policy","title":"Política","url":"https://jugalatamgame.com/policy.php"}
              },
              "leaderboard": {
                "best":{"player":"Alejandro","distance_m":13240,"date":"2026-02-02"},
                "history":[
                  {"player":"Mateo","distance_m":12890,"date":"2026-02-10"},
                  {"player":"Sofía","distance_m":12110,"date":"2026-02-08"},
                  {"player":"Diego","distance_m":11870,"date":"2026-02-05"}
                ]
              }
            }""".trimIndent()
        )
    }
}

fun shareRecord(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Compartir récord"))
}

fun openExternal(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

fun saveResultImage(context: Context, nickname: String, distance: Int, score: Int): Result<String> {
    return runCatching {
        val bitmap = Bitmap.createBitmap(1080, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(10, 22, 35))

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(224, 180, 0)
            textSize = 96f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 58f
        }

        canvas.drawText("JugaPlatform", 120f, 220f, titlePaint)
        canvas.drawText("Jugador: $nickname", 120f, 420f, bodyPaint)
        canvas.drawText("Distancia: $distance m", 120f, 520f, bodyPaint)
        canvas.drawText("Puntos: $score", 120f, 620f, bodyPaint)

        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "jugaplatform_result_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/JugaPlatform")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Cannot create media uri")
        val stream: OutputStream = resolver.openOutputStream(uri) ?: error("Cannot open output stream")
        stream.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        "Saved to gallery"
    }
}
