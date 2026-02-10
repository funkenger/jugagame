package com.jugaplatform.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.random.Random

private const val BASE_GAME_SPEED = 420f
private const val GRAVITY = 2300f
private const val JUMP_FORCE = 980f
private const val GROUND_HEIGHT_RATIO = 0.20f
private const val HERO_WIDTH_RATIO = 0.08f
private const val HERO_HEIGHT_RATIO = 0.13f
private const val HERO_X_RATIO = 0.18f

enum class ObstacleType {
    CACTUS,
    LLAMA,
    BARRICADE
}

data class Obstacle(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val type: ObstacleType,
    val tintSeed: Int
)

data class Cloud(
    val x: Float,
    val y: Float,
    val width: Float,
    val speedMultiplier: Float
)

data class HeroState(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val velocityY: Float,
    val isJumping: Boolean,
    val animationPhase: Float
)

data class GameUiState(
    val worldSize: IntSize = IntSize.Zero,
    val score: Int = 0,
    val bestScore: Int = 0,
    val distanceMeters: Int = 0,
    val isRunning: Boolean = false,
    val gameOver: Boolean = false,
    val heroName: String = "JUGADOR-MAN",
    val cityTag: String = "Ruta Andina",
    val hero: HeroState = HeroState(0f, 0f, 0f, 0f, 0f, false, 0f),
    val obstacles: List<Obstacle> = emptyList(),
    val clouds: List<Cloud> = emptyList(),
    val speed: Float = BASE_GAME_SPEED,
    val timestampMs: Long = 0L
)

class GameViewModel : ViewModel() {

    private var loopJob: Job? = null
    private val random = Random(System.currentTimeMillis())

    var uiState by mutableStateOf(GameUiState())
        private set

    fun onWorldReady(size: IntSize) {
        if (size.width == 0 || size.height == 0) return
        if (uiState.worldSize == size) return
        uiState = initialState(size, uiState.bestScore)
    }

    fun startGame() {
        if (uiState.worldSize == IntSize.Zero) return
        if (uiState.isRunning) return
        uiState = initialState(uiState.worldSize, uiState.bestScore).copy(isRunning = true)
        runLoop()
    }

    fun restartGame() {
        loopJob?.cancel()
        uiState = initialState(uiState.worldSize, uiState.bestScore).copy(isRunning = true)
        runLoop()
    }

    fun jump() {
        if (!uiState.isRunning) {
            startGame()
            return
        }
        val hero = uiState.hero
        if (!hero.isJumping) {
            uiState = uiState.copy(hero = hero.copy(velocityY = -JUMP_FORCE, isJumping = true))
        }
    }

    private fun runLoop() {
        loopJob?.cancel()
        loopJob = viewModelScope.launch {
            var previousTime = System.currentTimeMillis()
            while (isActive && uiState.isRunning) {
                val now = System.currentTimeMillis()
                val delta = ((now - previousTime).coerceAtLeast(8L)) / 1000f
                previousTime = now
                tick(delta, now)
                delay(16)
            }
        }
    }

    private fun tick(delta: Float, now: Long) {
        val size = uiState.worldSize
        if (size == IntSize.Zero) return

        val groundY = size.height * (1f - GROUND_HEIGHT_RATIO)
        var hero = uiState.hero

        val nextVelocity = hero.velocityY + GRAVITY * delta
        val nextY = hero.y + nextVelocity * delta
        val heroBottom = nextY + hero.height

        hero = if (heroBottom >= groundY) {
            hero.copy(
                y = groundY - hero.height,
                velocityY = 0f,
                isJumping = false,
                animationPhase = hero.animationPhase + delta * 9f
            )
        } else {
            hero.copy(
                y = nextY,
                velocityY = nextVelocity,
                isJumping = true,
                animationPhase = hero.animationPhase + delta * 5f
            )
        }

        val speedGain = uiState.speed + delta * 7f
        val movedObstacles = uiState.obstacles
            .map { obstacle -> obstacle.copy(x = obstacle.x - speedGain * delta) }
            .filter { it.x + it.width > -8f }
            .toMutableList()

        val movedClouds = uiState.clouds.map { cloud ->
            val shiftedX = cloud.x - speedGain * cloud.speedMultiplier * delta
            if (shiftedX + cloud.width < 0f) {
                newCloud(size.width.toFloat(), size.height.toFloat())
            } else {
                cloud.copy(x = shiftedX)
            }
        }

        val shouldSpawnObstacle = movedObstacles.isEmpty() ||
            movedObstacles.last().x < size.width - random.nextInt(240, 510)

        if (shouldSpawnObstacle) {
            movedObstacles += newObstacle(size.width.toFloat(), groundY)
        }

        val heroRect = Rect(hero.x, hero.y, hero.x + hero.width, hero.y + hero.height)
        val collided = movedObstacles.any { it.toRect().overlaps(heroRect.shrink(0.18f)) }

        val nextScore = if (collided) uiState.score else uiState.score + (delta * 28f).toInt()
        val best = max(uiState.bestScore, nextScore)

        uiState = uiState.copy(
            score = nextScore,
            bestScore = best,
            distanceMeters = (nextScore * 1.75f).toInt(),
            hero = hero,
            obstacles = movedObstacles,
            clouds = movedClouds,
            speed = speedGain,
            isRunning = !collided,
            gameOver = collided,
            timestampMs = now
        )
    }

    private fun initialState(size: IntSize, bestScore: Int): GameUiState {
        val width = size.width.toFloat()
        val height = size.height.toFloat()
        val groundY = height * (1f - GROUND_HEIGHT_RATIO)
        val heroWidth = width * HERO_WIDTH_RATIO
        val heroHeight = height * HERO_HEIGHT_RATIO

        return GameUiState(
            worldSize = size,
            bestScore = bestScore,
            hero = HeroState(
                x = width * HERO_X_RATIO,
                y = groundY - heroHeight,
                width = heroWidth,
                height = heroHeight,
                velocityY = 0f,
                isJumping = false,
                animationPhase = 0f
            ),
            clouds = List(4) { idx ->
                newCloud((width / 4f) * idx + random.nextFloat() * 100f, height)
            },
            obstacles = listOf(newObstacle(width + 120f, groundY)),
            cityTag = listOf("Valle del Sol", "Ruta Andina", "Costa Alegre", "Ciudad Jaguar").random(random)
        )
    }

    private fun newObstacle(startX: Float, groundY: Float): Obstacle {
        return when (random.nextInt(0, 3)) {
            0 -> {
                val width = random.nextInt(34, 52).toFloat()
                val height = random.nextInt(72, 118).toFloat()
                Obstacle(startX, groundY - height, width, height, ObstacleType.CACTUS, random.nextInt())
            }

            1 -> {
                val width = random.nextInt(62, 90).toFloat()
                val height = random.nextInt(58, 85).toFloat()
                Obstacle(startX, groundY - height, width, height, ObstacleType.LLAMA, random.nextInt())
            }

            else -> {
                val width = random.nextInt(76, 130).toFloat()
                val height = random.nextInt(40, 68).toFloat()
                Obstacle(startX, groundY - height, width, height, ObstacleType.BARRICADE, random.nextInt())
            }
        }
    }

    private fun newCloud(startX: Float, worldHeight: Float): Cloud {
        val width = random.nextInt(70, 140).toFloat()
        return Cloud(
            x = startX,
            y = random.nextInt((worldHeight * 0.05f).toInt(), (worldHeight * 0.35f).toInt()).toFloat(),
            width = width,
            speedMultiplier = random.nextFloat() * 0.35f + 0.15f
        )
    }
}

private fun Obstacle.toRect(): Rect = Rect(x, y, x + width, y + height)

private fun Rect.shrink(amountRatio: Float): Rect {
    val dx = width * amountRatio
    val dy = height * amountRatio
    return Rect(
        offset = Offset(left + dx, top + dy),
        size = androidx.compose.ui.geometry.Size(width - dx * 2f, height - dy * 2f)
    )
}
