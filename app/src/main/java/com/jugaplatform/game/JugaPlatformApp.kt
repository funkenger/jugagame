package com.jugaplatform

import android.Manifest
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlin.math.sin

private val SkyTop = Color(0xFF032B5D)
private val SkyMid = Color(0xFF0A4B8C)
private val SkyBottom = Color(0xFFF6A04D)
private val GroundColor = Color(0xFF5A3623)
private val GroundStripe = Color(0xFF8B4F2C)
private val SunColor = Color(0xFFFFD27F)

private sealed class ScreenState {
    data object Loading : ScreenState()
    data object Home : ScreenState()
    data class Policy(val url: String) : ScreenState()
    data object Game : ScreenState()
}

@Composable
fun JugaPlatformApp(gameViewModel: GameViewModel = viewModel()) {
    val context = LocalContext.current
    val storage = remember { AppStorage(context) }
    val scope = rememberCoroutineScope()

    var payload by remember { mutableStateOf<RemotePayload?>(null) }
    var screen by remember { mutableStateOf<ScreenState>(ScreenState.Loading) }
    var loadingError by remember { mutableStateOf<String?>(null) }
    var nickname by remember { mutableStateOf(storage.nickname()) }
    var showNickDialog by remember { mutableStateOf(false) }
    var saveMsg by remember { mutableStateOf<String?>(null) }

    val filePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        val uuid = storage.getOrCreateUuid()
        val referrer = fetchInstallReferrer(context)
        storage.saveInstallReferrer(referrer)
        payload = runCatching { loadRemotePayload(referrer, uuid) }
            .onFailure { loadingError = it.message }
            .getOrNull() ?: RemotePayload()
        screen = ScreenState.Home
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (val current = screen) {
                ScreenState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                ScreenState.Home -> {
                    val p = payload ?: RemotePayload()
                    HomeScreen(
                        payload = p,
                        nickname = nickname,
                        loadingError = loadingError,
                        onStart = {
                            if (nickname.isNullOrBlank()) showNickDialog = true
                            else {
                                saveMsg = null
                                gameViewModel.restartGame()
                                screen = ScreenState.Game
                            }
                        },
                        onPolicy = { url -> screen = ScreenState.Policy(url) }
                    )

                    if (showNickDialog) {
                        NicknameDialog(
                            onSave = {
                                storage.saveNickname(it)
                                nickname = it
                                showNickDialog = false
                                gameViewModel.restartGame()
                                screen = ScreenState.Game
                            },
                            onDismiss = { showNickDialog = false }
                        )
                    }
                }

                is ScreenState.Policy -> {
                    PolicyScreen(url = current.url)
                }

                ScreenState.Game -> {
                    val ui = gameViewModel.uiState
                    val lb = payload?.leaderboard ?: RemoteLeaderboard()

                    if (ui.gameOver) {
                        if (ui.distanceMeters > storage.bestDistance()) storage.saveBestDistance(ui.distanceMeters)
                    }

                    Box(Modifier.fillMaxSize()) {
                        GamePlayCanvas(ui = ui, onJump = { gameViewModel.jump() }, onWorldReady = { gameViewModel.onWorldReady(it) })
                        ScoreHud(
                            score = ui.score,
                            best = storage.bestDistance(),
                            distance = ui.distanceMeters,
                            cityTag = ui.cityTag,
                            bonusCount = ui.bonusCount,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
                        )

                        if (ui.gameOver) {
                            val player = nickname ?: "Jugador"
                            val rank = rankFor(player, ui.distanceMeters, lb)
                            GameOverCard(
                                score = ui.score,
                                best = storage.bestDistance(),
                                rank = rank,
                                leaderboard = lb,
                                onRestart = { gameViewModel.restartGame() },
                                onHome = { screen = ScreenState.Home },
                                onShareX = { shareRecord(context, "Mi resultado en JugaPlatform: ${ui.distanceMeters} m #JugaPlatform") },
                                onShareFb = { shareRecord(context, "Mi resultado en JugaPlatform: ${ui.distanceMeters} m") },
                                onSaveImage = {
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                                        filePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                                    }
                                    saveMsg = saveResultImage(context, player, ui.distanceMeters, ui.score)
                                        .fold(onSuccess = { it }, onFailure = { "Save failed: ${it.message}" })
                                },
                                saveMessage = saveMsg,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun rankFor(player: String, distance: Int, board: RemoteLeaderboard): Int {
    val all = (board.history + board.best + LeaderboardItem(player, distance, "today"))
        .sortedByDescending { it.distanceM }
    return all.indexOfFirst { it.player == player && it.distanceM == distance } + 1
}

@Composable
private fun HomeScreen(
    payload: RemotePayload,
    nickname: String?,
    loadingError: String?,
    onStart: () -> Unit,
    onPolicy: (String) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0C1726)).padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(0.86f)
        ) {
            Text("JugaPlatform", color = Color(0xFFE0B400), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
            Text("Jugador: ${nickname ?: "(sin nombre)"}", color = Color(0xFFD8E5F3))
            loadingError?.let { Text("Network fallback: $it", color = Color.Yellow) }

            Button(
                onClick = { if (payload.ui.button1.action == "startgame") onStart() },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF476F))
            ) { Text(payload.ui.button1.title, modifier = Modifier.padding(vertical = 12.dp)) }

            TextButton(onClick = {
                if (payload.ui.button2.action == "policy") onPolicy(payload.ui.button2.url.orEmpty())
            }) { Text(payload.ui.button2.title) }
        }
    }
}

@Composable
private fun NicknameDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Escribe tu apodo") },
        text = { TextField(value = value, onValueChange = { value = it }, singleLine = true, placeholder = { Text("Ingresa tu apodo") }) },
        confirmButton = { TextButton(onClick = { if (value.isNotBlank()) onSave(value.trim()) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun PolicyScreen(url: String) {
    var loading by remember { mutableStateOf(true) }
    var uploadCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uploadCallback?.onReceiveValue(uris.toTypedArray())
        uploadCallback = null
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                        val cookie = CookieManager.getInstance()
                        cookie.setAcceptCookie(true)
                        cookie.setAcceptThirdPartyCookies(this, true)

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        settings.databaseEnabled = true
                        settings.loadsImagesAutomatically = true
                        settings.mediaPlaybackRequiresUserGesture = false

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val target = request?.url?.toString().orEmpty()
                                return if (target.contains("play.google.com")) {
                                    openExternal(ctx, target)
                                    true
                                } else false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallback: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?
                            ): Boolean {
                                uploadCallback = filePathCallback
                                filePicker.launch(arrayOf("*/*"))
                                return true
                            }
                        }
                        loading = true
                        loadUrl(url)
                    }
                }
        )

        if (loading) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun GamePlayCanvas(ui: GameUiState, onJump: () -> Unit, onWorldReady: (androidx.compose.ui.unit.IntSize) -> Unit) {
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(ui.isRunning, ui.gameOver) { detectTapGestures { scope.launch { onJump() } } }
            .onSizeChanged(onWorldReady)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val groundY = height * 0.80f
            drawRect(brush = Brush.verticalGradient(listOf(SkyTop, SkyMid, SkyBottom)), size = size)
            drawCircle(color = SunColor.copy(alpha = 0.8f), radius = width * 0.13f, center = Offset(width * 0.84f, height * 0.18f))
            drawMountains(width, height, ui.timestampMs)
            ui.clouds.forEach { drawCloud(it) }
            drawRect(color = GroundColor, topLeft = Offset(0f, groundY), size = Size(width, height - groundY))
            repeat(35) { idx ->
                val stripeX = ((idx * 140f) - (ui.speed * 0.12f + ui.timestampMs * 0.3f) % 140f)
                drawRoundRect(color = GroundStripe, topLeft = Offset(stripeX, groundY + (idx % 5) * 12f), size = Size(100f, 10f), cornerRadius = CornerRadius(5f))
            }
            drawHero(ui.hero)
            ui.obstacles.forEach { drawObstacle(it) }
            ui.bonuses.forEach { drawBonus(it) }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMountains(width: Float, height: Float, timestampMs: Long) {
    val wave = sin(timestampMs / 9000f) * 22f
    val baseY = height * 0.80f
    val back = Path().apply {
        moveTo(0f, baseY); lineTo(width * 0.16f, height * 0.52f + wave); lineTo(width * 0.34f, height * 0.64f)
        lineTo(width * 0.55f, height * 0.48f - wave); lineTo(width * 0.78f, height * 0.63f); lineTo(width, height * 0.54f + wave)
        lineTo(width, baseY); close()
    }
    val front = Path().apply {
        moveTo(0f, baseY); lineTo(width * 0.10f, height * 0.60f); lineTo(width * 0.23f, height * 0.73f)
        lineTo(width * 0.46f, height * 0.57f); lineTo(width * 0.64f, height * 0.72f); lineTo(width * 0.86f, height * 0.56f)
        lineTo(width, height * 0.70f); lineTo(width, baseY); close()
    }
    drawPath(back, color = Color(0x5B3D6EA9)); drawPath(front, color = Color(0x9A233F6A))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCloud(cloud: Cloud) {
    val color = Color.White.copy(alpha = 0.32f)
    val h = cloud.width * 0.38f
    drawOval(color, Offset(cloud.x, cloud.y), Size(cloud.width * 0.62f, h))
    drawOval(color, Offset(cloud.x + cloud.width * 0.26f, cloud.y - h * 0.3f), Size(cloud.width * 0.54f, h * 1.1f))
    drawOval(color, Offset(cloud.x + cloud.width * 0.48f, cloud.y), Size(cloud.width * 0.52f, h))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHero(hero: HeroState) {
    drawRoundRect(Color(0xFFEF476F), Offset(hero.x - hero.width * 0.12f, hero.y + hero.height * 0.28f), Size(hero.width * 0.4f, hero.height * 0.58f), CornerRadius(20f))
    drawRoundRect(Color(0xFFF4D35E), Offset(hero.x, hero.y), Size(hero.width, hero.height), CornerRadius(18f))
    drawRoundRect(Color(0xFF073B4C), Offset(hero.x + hero.width * 0.08f, hero.y + hero.height * 0.18f), Size(hero.width * 0.84f, hero.height * 0.27f), CornerRadius(14f))
    drawCircle(Color.White, hero.width * 0.07f, Offset(hero.x + hero.width * 0.34f, hero.y + hero.height * 0.31f))
    drawCircle(Color.White, hero.width * 0.07f, Offset(hero.x + hero.width * 0.66f, hero.y + hero.height * 0.31f))
    val legLift = if (hero.isJumping) hero.height * 0.02f else sin(hero.animationPhase) * hero.height * 0.06f
    drawRoundRect(Color(0xFF1D3557), Offset(hero.x + hero.width * 0.15f, hero.y + hero.height * 0.72f + legLift), Size(hero.width * 0.25f, hero.height * 0.30f), CornerRadius(8f))
    drawRoundRect(Color(0xFF1D3557), Offset(hero.x + hero.width * 0.60f, hero.y + hero.height * 0.72f - legLift), Size(hero.width * 0.25f, hero.height * 0.30f), CornerRadius(8f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawObstacle(obstacle: Obstacle) {
    if (obstacle.type == ObstacleType.CACTUS) {
        drawRoundRect(Color(0xFF2D6A4F), Offset(obstacle.x, obstacle.y), Size(obstacle.width, obstacle.height), CornerRadius(14f))
        drawRoundRect(Color(0xFF40916C), Offset(obstacle.x - obstacle.width * 0.26f, obstacle.y + obstacle.height * 0.35f), Size(obstacle.width * 0.32f, obstacle.height * 0.24f), CornerRadius(14f))
    } else {
        drawRoundRect(Color(0xFF9C6644), Offset(obstacle.x, obstacle.y), Size(obstacle.width, obstacle.height), CornerRadius(9f))
        drawRoundRect(Color(0xFF7F5539), Offset(obstacle.x + obstacle.width * 0.08f, obstacle.y + obstacle.height * 0.36f), Size(obstacle.width * 0.84f, obstacle.height * 0.18f), CornerRadius(6f))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBonus(bonus: Bonus) {
    drawCircle(Color(0xFFFFD166), bonus.size * 0.5f, Offset(bonus.x + bonus.size * 0.5f, bonus.y + bonus.size * 0.5f))
    drawCircle(Color(0xFFFFF1B0), bonus.size * 0.22f, Offset(bonus.x + bonus.size * 0.5f, bonus.y + bonus.size * 0.5f))
}

@Composable
private fun ScoreHud(score: Int, best: Int, distance: Int, cityTag: String, bonusCount: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.background(Color(0x4D101418), shape = RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Stat("Puntos", score.toString()); Stat("Record", "$best m"); Stat("Distancia", "$distance m"); Stat("Estrellas", bonusCount.toString()); Stat("Zona", cityTag)
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = Color(0xB3FFFFFF), style = MaterialTheme.typography.labelSmall)
        Text(text = value, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GameOverCard(
    score: Int,
    best: Int,
    rank: Int,
    leaderboard: RemoteLeaderboard,
    onRestart: () -> Unit,
    onHome: () -> Unit,
    onShareX: () -> Unit,
    onShareFb: () -> Unit,
    onSaveImage: () -> Unit,
    saveMessage: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.background(Color(0xDB180F14), shape = RoundedCornerShape(24.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("¡Fin del viaje!", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Text("Puntos: $score", color = Color(0xFFFFE29A))
        Text("Mejor distancia: $best m", color = Color(0xFFFFE29A))
        Text("Tu lugar: #$rank", color = Color(0xFF9AE6B4), fontWeight = FontWeight.Bold)

        val topPlayers = (listOf(leaderboard.best) + leaderboard.history)
            .sortedByDescending { it.distanceM }
            .take(5)

        LazyColumn(modifier = Modifier.fillMaxWidth().background(Color(0x33222A35), RoundedCornerShape(12.dp)).padding(8.dp)) {
            items(topPlayers.withIndex().toList()) { indexed ->
                val place = indexed.index + 1
                val item = indexed.value
                Text("#$place ${item.player} — ${item.distanceM} m (${item.date})", color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRestart, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF476F))) { Text("Reintentar") }
            OutlinedButton(onClick = onHome) { Text("Inicio") }
            OutlinedButton(onClick = onSaveImage) { Text("Guardar imagen") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onShareFb) { Text("Share FB") }
            OutlinedButton(onClick = onShareX) { Text("Share X") }
        }
        saveMessage?.let { Text(it, color = Color(0xFFBEE3F8)) }
    }
}
