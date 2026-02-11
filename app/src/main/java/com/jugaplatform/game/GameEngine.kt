package com.jugaplatform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.random.Random

private const val BASE_GAME_SPEED = 250f
private const val GRAVITY = 1700f
private const val JUMP_FORCE = 760f
private const val GROUND_HEIGHT_RATIO = 0.20f
private const val HERO_WIDTH_RATIO = 0.09f
private const val HERO_HEIGHT_RATIO = 0.14f
private const val HERO_X_RATIO = 0.17f
private const val EXTRA_LIVES_PER_RUN = 3

private const val SCORE_RATE = 22f
private const val DISTANCE_RATE = 14f

enum class ObstacleType {
    CACTUS,
    BOX
}

data class Obstacle(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val type: ObstacleType
)

data class Bonus(
    val x: Float,
    val y: Float,
    val size: Float
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
    val bonusCount: Int = 0,
    val livesLeft: Int = EXTRA_LIVES_PER_RUN,
    val isRunning: Boolean = false,
    val gameOver: Boolean = false,
    val heroName: String = "JUGADOR-MAN",
    val cityTag: String = "Ruta Alegre",
    val hero: HeroState = HeroState(0f, 0f, 0f, 0f, 0f, false, 0f),
    val obstacles: List<Obstacle> = emptyList(),
    val bonuses: List<Bonus> = emptyList(),
    val clouds: List<Cloud> = emptyList(),
    val speed: Float = BASE_GAME_SPEED,
    val timestampMs: Long = 0L
)

class GameViewModel : ViewModel() {

    private var loopJob: Job? = null
    private val random = Random(System.currentTimeMillis())

    private var scoreFloat = 0f
    private var distanceFloat = 0f

    var uiState by mutableStateOf(GameUiState())
        private set

    fun onWorldReady(size: IntSize) {
        if (size.width == 0 || size.height == 0) return
        if (uiState.worldSize == size) return
        uiState = initialState(size, uiState.bestScore)
    }

    fun startGame() {
        if (uiState.worldSize == IntSize.Zero || uiState.isRunning) return
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
                val delta = ((now - previousTime).coerceAtLeast(8L)).coerceAtMost(40L) / 1000f
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
        hero = if (nextY + hero.height >= groundY) {
            hero.copy(
                y = groundY - hero.height,
                velocityY = 0f,
                isJumping = false,
                animationPhase = hero.animationPhase + delta * 7f
            )
        } else {
            hero.copy(
                y = nextY,
                velocityY = nextVelocity,
                isJumping = true,
                animationPhase = hero.animationPhase + delta * 4f
            )
        }

        val movingSpeed = (uiState.speed + delta * 4f).coerceAtMost(390f)

        val movedObstacles = uiState.obstacles
            .map { it.copy(x = it.x - movingSpeed * delta) }
            .filter { it.x + it.width > 0f }
            .toMutableList()

        val movedBonuses = uiState.bonuses
            .map { it.copy(x = it.x - movingSpeed * delta) }
            .filter { it.x + it.size > 0f }
            .toMutableList()

        val clouds = uiState.clouds.map { cloud ->
            val shiftedX = cloud.x - movingSpeed * cloud.speedMultiplier * delta
            if (shiftedX + cloud.width < 0f) {
                newCloud(size.width.toFloat(), size.height.toFloat())
            } else {
                cloud.copy(x = shiftedX)
            }
        }

        if (movedObstacles.isEmpty() || movedObstacles.last().x < size.width - random.nextInt(360, 560)) {
            movedObstacles += newObstacle(size.width.toFloat(), groundY)
        }

        if (movedBonuses.isEmpty() || movedBonuses.last().x < size.width - random.nextInt(520, 760)) {
            movedBonuses += newBonus(size.width.toFloat(), groundY)
        }

        val heroRect = Rect(hero.x, hero.y, hero.x + hero.width, hero.y + hero.height)
        val collided = movedObstacles.any { it.toRect().overlaps(heroRect.shrink(0.2f)) }

        var collected = 0
        val bonusesAfterCollect = movedBonuses.filterNot { bonus ->
            val hit = bonus.toRect().overlaps(heroRect.shrink(0.08f))
            if (hit) collected += 1
            hit
        }

        var livesLeft = uiState.livesLeft
        var gameOver = false
        var obstaclesAfterCollision = movedObstacles
        var bonusesAfterCollision = bonusesAfterCollect
        var speedAfterCollision = movingSpeed

        if (collided) {
            if (livesLeft > 0) {
                livesLeft -= 1
                hero = hero.copy(
                    y = groundY - hero.height,
                    velocityY = 0f,
                    isJumping = false,
                    animationPhase = 0f
                )
                obstaclesAfterCollision = mutableListOf(newObstacle(size.width.toFloat() + 180f, groundY))
                bonusesAfterCollision = mutableListOf(newBonus(size.width.toFloat() + 340f, groundY))
                speedAfterCollision = BASE_GAME_SPEED
            } else {
                gameOver = true
            }
        }

        if (!collided) {
            scoreFloat += delta * SCORE_RATE + collected * 7f
            distanceFloat += delta * DISTANCE_RATE
        }

        val nextScore = scoreFloat.toInt()
        val nextDistance = distanceFloat.toInt()
        val totalBonuses = uiState.bonusCount + collected
        val bestScore = max(uiState.bestScore, nextScore)

        uiState = uiState.copy(
            score = nextScore,
            bestScore = bestScore,
            distanceMeters = nextDistance,
            bonusCount = totalBonuses,
            livesLeft = livesLeft,
            hero = hero,
            obstacles = obstaclesAfterCollision,
            bonuses = bonusesAfterCollision,
            clouds = clouds,
            speed = speedAfterCollision,
            isRunning = !gameOver,
            gameOver = gameOver,
            timestampMs = now
        )
    }

    private fun initialState(size: IntSize, bestScore: Int): GameUiState {
        val width = size.width.toFloat()
        val height = size.height.toFloat()
        val groundY = height * (1f - GROUND_HEIGHT_RATIO)
        val heroWidth = width * HERO_WIDTH_RATIO
        val heroHeight = height * HERO_HEIGHT_RATIO

        scoreFloat = 0f
        distanceFloat = 0f

        return GameUiState(
            worldSize = size,
            bestScore = bestScore,
            livesLeft = EXTRA_LIVES_PER_RUN,
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
            obstacles = listOf(newObstacle(width + 140f, groundY)),
            bonuses = listOf(newBonus(width + 280f, groundY)),
            cityTag = listOf("Ruta Alegre", "Campo Feliz", "Costa Suave", "Valle Tranquilo").random(random)
        )
    }

    private fun newObstacle(startX: Float, groundY: Float): Obstacle {
        return if (random.nextBoolean()) {
            val width = random.nextInt(28, 42).toFloat()
            val height = random.nextInt(54, 82).toFloat()
            Obstacle(startX, groundY - height, width, height, ObstacleType.CACTUS)
        } else {
            val width = random.nextInt(44, 66).toFloat()
            val height = random.nextInt(30, 44).toFloat()
            Obstacle(startX, groundY - height, width, height, ObstacleType.BOX)
        }
    }

    private fun newBonus(startX: Float, groundY: Float): Bonus {
        val size = random.nextInt(20, 30).toFloat()
        val minY = groundY - 150f
        val maxY = groundY - 70f
        return Bonus(startX, random.nextFloat() * (maxY - minY) + minY, size)
    }

    private fun newCloud(startX: Float, worldHeight: Float): Cloud {
        val width = random.nextInt(70, 130).toFloat()
        val minY = (worldHeight * 0.08f).toInt().coerceAtLeast(0)
        val maxYExclusive = ((worldHeight * 0.32f).toInt() + 1).coerceAtLeast(minY + 1)
        return Cloud(
            x = startX,
            y = random.nextInt(minY, maxYExclusive).toFloat(),
            width = width,
            speedMultiplier = random.nextFloat() * 0.22f + 0.12f
        )
    }
}

private fun Obstacle.toRect(): Rect = Rect(x, y, x + width, y + height)

private fun Bonus.toRect(): Rect = Rect(x, y, x + size, y + size)

private fun Rect.shrink(amountRatio: Float): Rect {
    val dx = width * amountRatio
    val dy = height * amountRatio
    return Rect(
        offset = Offset(left + dx, top + dy),
        size = Size(width - dx * 2f, height - dy * 2f)
    )
}
