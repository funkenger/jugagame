package com.jugaplatform.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlin.math.sin

private val SkyTop = Color(0xFF032B5D)
private val SkyMid = Color(0xFF0A4B8C)
private val SkyBottom = Color(0xFFF6A04D)
private val GroundColor = Color(0xFF5A3623)
private val GroundStripe = Color(0xFF8B4F2C)
private val SunColor = Color(0xFFFFD27F)

@Composable
fun JugaPlatformApp(gameViewModel: GameViewModel = viewModel()) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val ui = gameViewModel.uiState
            val scope = rememberCoroutineScope()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(ui.isRunning, ui.gameOver) {
                        detectTapGestures(
                            onTap = {
                                scope.launch { gameViewModel.jump() }
                            }
                        )
                    }
                    .onSizeChanged { gameViewModel.onWorldReady(it) }
            ) {
                GameScene(ui)

                ScoreHud(
                    score = ui.score,
                    best = ui.bestScore,
                    distance = ui.distanceMeters,
                    cityTag = ui.cityTag,
                    bonusCount = ui.bonusCount,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 26.dp)
                )

                if (!ui.isRunning && !ui.gameOver) {
                    IntroCard(
                        heroName = ui.heroName,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                if (ui.gameOver) {
                    GameOverCard(
                        score = ui.score,
                        best = ui.bestScore,
                        onRestart = gameViewModel::restartGame,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

@Composable
private fun GameScene(ui: GameUiState) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val groundY = height * 0.80f

        drawRect(
            brush = Brush.verticalGradient(listOf(SkyTop, SkyMid, SkyBottom)),
            size = size
        )

        drawCircle(
            color = SunColor.copy(alpha = 0.8f),
            radius = width * 0.13f,
            center = Offset(width * 0.84f, height * 0.18f)
        )

        drawMountains(width, height, ui.timestampMs)

        ui.clouds.forEach { cloud ->
            drawCloud(cloud)
        }

        drawRect(
            color = GroundColor,
            topLeft = Offset(0f, groundY),
            size = Size(width, height - groundY)
        )

        repeat(35) { idx ->
            val stripeX = ((idx * 140f) - (ui.speed * 0.12f + ui.timestampMs * 0.3f) % 140f)
            drawRoundRect(
                color = GroundStripe,
                topLeft = Offset(stripeX, groundY + (idx % 5) * 12f),
                size = Size(100f, 10f),
                cornerRadius = CornerRadius(5f)
            )
        }

        drawHero(ui.hero)
        ui.obstacles.forEach { obstacle -> drawObstacle(obstacle) }
        ui.bonuses.forEach { bonus -> drawBonus(bonus) }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMountains(
    width: Float,
    height: Float,
    timestampMs: Long
) {
    val wave = sin(timestampMs / 9000f) * 22f
    val baseY = height * 0.80f

    val back = Path().apply {
        moveTo(0f, baseY)
        lineTo(width * 0.16f, height * 0.52f + wave)
        lineTo(width * 0.34f, height * 0.64f)
        lineTo(width * 0.55f, height * 0.48f - wave)
        lineTo(width * 0.78f, height * 0.63f)
        lineTo(width, height * 0.54f + wave)
        lineTo(width, baseY)
        close()
    }

    val front = Path().apply {
        moveTo(0f, baseY)
        lineTo(width * 0.10f, height * 0.60f)
        lineTo(width * 0.23f, height * 0.73f)
        lineTo(width * 0.46f, height * 0.57f)
        lineTo(width * 0.64f, height * 0.72f)
        lineTo(width * 0.86f, height * 0.56f)
        lineTo(width, height * 0.70f)
        lineTo(width, baseY)
        close()
    }

    drawPath(back, color = Color(0x5B3D6EA9))
    drawPath(front, color = Color(0x9A233F6A))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCloud(cloud: Cloud) {
    val color = Color.White.copy(alpha = 0.32f)
    val h = cloud.width * 0.38f
    drawOval(color = color, topLeft = Offset(cloud.x, cloud.y), size = Size(cloud.width * 0.62f, h))
    drawOval(
        color = color,
        topLeft = Offset(cloud.x + cloud.width * 0.26f, cloud.y - h * 0.3f),
        size = Size(cloud.width * 0.54f, h * 1.1f)
    )
    drawOval(
        color = color,
        topLeft = Offset(cloud.x + cloud.width * 0.48f, cloud.y),
        size = Size(cloud.width * 0.52f, h)
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHero(hero: HeroState) {
    val bodyColor = Color(0xFFF4D35E)
    val capeColor = Color(0xFFEF476F)
    val maskColor = Color(0xFF073B4C)

    drawRoundRect(
        color = capeColor,
        topLeft = Offset(hero.x - hero.width * 0.12f, hero.y + hero.height * 0.28f),
        size = Size(hero.width * 0.4f, hero.height * 0.58f),
        cornerRadius = CornerRadius(20f)
    )

    drawRoundRect(
        color = bodyColor,
        topLeft = Offset(hero.x, hero.y),
        size = Size(hero.width, hero.height),
        cornerRadius = CornerRadius(18f)
    )

    val faceHeight = hero.height * 0.27f
    drawRoundRect(
        color = maskColor,
        topLeft = Offset(hero.x + hero.width * 0.08f, hero.y + hero.height * 0.18f),
        size = Size(hero.width * 0.84f, faceHeight),
        cornerRadius = CornerRadius(14f)
    )

    drawCircle(
        color = Color.White,
        radius = hero.width * 0.07f,
        center = Offset(hero.x + hero.width * 0.34f, hero.y + hero.height * 0.31f)
    )
    drawCircle(
        color = Color.White,
        radius = hero.width * 0.07f,
        center = Offset(hero.x + hero.width * 0.66f, hero.y + hero.height * 0.31f)
    )

    val legLift = if (hero.isJumping) hero.height * 0.02f else sin(hero.animationPhase) * hero.height * 0.06f
    drawRoundRect(
        color = Color(0xFF1D3557),
        topLeft = Offset(hero.x + hero.width * 0.15f, hero.y + hero.height * 0.72f + legLift),
        size = Size(hero.width * 0.25f, hero.height * 0.30f),
        cornerRadius = CornerRadius(8f)
    )
    drawRoundRect(
        color = Color(0xFF1D3557),
        topLeft = Offset(hero.x + hero.width * 0.60f, hero.y + hero.height * 0.72f - legLift),
        size = Size(hero.width * 0.25f, hero.height * 0.30f),
        cornerRadius = CornerRadius(8f)
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawObstacle(obstacle: Obstacle) {
    when (obstacle.type) {
        ObstacleType.CACTUS -> {
            drawRoundRect(
                color = Color(0xFF2D6A4F),
                topLeft = Offset(obstacle.x, obstacle.y),
                size = Size(obstacle.width, obstacle.height),
                cornerRadius = CornerRadius(14f)
            )
            drawRoundRect(
                color = Color(0xFF40916C),
                topLeft = Offset(obstacle.x - obstacle.width * 0.26f, obstacle.y + obstacle.height * 0.35f),
                size = Size(obstacle.width * 0.32f, obstacle.height * 0.24f),
                cornerRadius = CornerRadius(14f)
            )
        }

        ObstacleType.BOX -> {
            drawRoundRect(
                color = Color(0xFF9C6644),
                topLeft = Offset(obstacle.x, obstacle.y),
                size = Size(obstacle.width, obstacle.height),
                cornerRadius = CornerRadius(9f)
            )
            drawRoundRect(
                color = Color(0xFF7F5539),
                topLeft = Offset(obstacle.x + obstacle.width * 0.08f, obstacle.y + obstacle.height * 0.36f),
                size = Size(obstacle.width * 0.84f, obstacle.height * 0.18f),
                cornerRadius = CornerRadius(6f)
            )
        }
    }
}



private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBonus(bonus: Bonus) {
    drawCircle(
        color = Color(0xFFFFD166),
        radius = bonus.size * 0.5f,
        center = Offset(bonus.x + bonus.size * 0.5f, bonus.y + bonus.size * 0.5f)
    )
    drawCircle(
        color = Color(0xFFFFF1B0),
        radius = bonus.size * 0.22f,
        center = Offset(bonus.x + bonus.size * 0.5f, bonus.y + bonus.size * 0.5f)
    )
}

@Composable
private fun ScoreHud(score: Int, best: Int, distance: Int, cityTag: String, bonusCount: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color(0x4D101418), shape = RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Stat("Puntos", score.toString())
        Stat("Record", best.toString())
        Stat("Distancia", "$distance m")
        Stat("Estrellas", bonusCount.toString())
        Stat("Zona", cityTag)
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
private fun IntroCard(heroName: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xCC091018), shape = RoundedCornerShape(24.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text = "JugaPlatform", color = Color(0xFFF4D35E), style = MaterialTheme.typography.headlineSmall)
        Text(text = "Héroe: $heroName", color = Color.White)
        Text(text = "Tap para empezar y saltar.", color = Color(0xFFD9E6F2))
        Text(text = "Evita obstáculos y recoge estrellas.", color = Color(0xFFD9E6F2))
    }
}

@Composable
private fun GameOverCard(
    score: Int,
    best: Int,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xDB180F14), shape = RoundedCornerShape(24.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "¡Fin del viaje!", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Text(text = "Puntos: $score", color = Color(0xFFFFE29A))
        Text(text = "Mejor marca: $best", color = Color(0xFFFFE29A))
        Button(
            onClick = onRestart,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF476F))
        ) {
            Text("Reintentar")
        }
    }
}
