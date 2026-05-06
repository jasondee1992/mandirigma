package com.mandirigma.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private val targetScore = 10
    private val cups = mutableListOf<TagayCup>()
    private val enemies = mutableListOf<Enemy>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
    }

    @Volatile
    private var running = false
    private var gameThread: Thread? = null
    private var lastFrameTimeNanos = 0L
    private var state = GameState.CATCH_TAGAY
    private var score = 0
    private var lasingMeter = 0
    private var spawnTimer = 0f
    private var moveDirection = 0
    private var levelInitialized = false

    private var playerX = 0f
    private var playerY = 0f
    private var playerRadius = 44f

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        resetLevel()
        resume()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        resetLevel()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        pause()
    }

    fun resume() {
        if (running || !holder.surface.isValid) return
        running = true
        lastFrameTimeNanos = System.nanoTime()
        gameThread = Thread(this, "MandirigmaGameLoop").also { it.start() }
    }

    fun pause() {
        running = false
        try {
            gameThread?.join()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        gameThread = null
    }

    override fun run() {
        while (running) {
            val now = System.nanoTime()
            val deltaSeconds = min((now - lastFrameTimeNanos) / 1_000_000_000f, 0.033f)
            lastFrameTimeNanos = now

            update(deltaSeconds)
            drawFrame()

            val frameTimeMillis = (System.nanoTime() - now) / 1_000_000L
            val sleepMillis = max(2L, 16L - frameTimeMillis)
            try {
                Thread.sleep(sleepMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> {
                if (state == GameState.GAME_OVER || state == GameState.LEVEL_PASSED) {
                    resetLevel()
                    return true
                }
                moveDirection = if (event.x < width / 2f) -1 else 1
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                moveDirection = 0
            }
        }
        return true
    }

    private fun resetLevel() {
        if (width <= 0 || height <= 0) return

        val scale = min(width, height) / 720f
        playerRadius = 44f * scale.coerceAtLeast(0.85f)
        playerX = width * 0.18f
        playerY = height - playerRadius - 44f
        score = 0
        lasingMeter = 0
        spawnTimer = 0f
        moveDirection = 0
        cups.clear()
        enemies.clear()
        state = GameState.CATCH_TAGAY
        levelInitialized = true
    }

    private fun update(deltaSeconds: Float) {
        if (!levelInitialized || width <= 0 || height <= 0) return

        when (state) {
            GameState.CATCH_TAGAY -> updateCatchPhase(deltaSeconds)
            GameState.ESCAPE -> updateEscapePhase(deltaSeconds)
            GameState.GAME_OVER, GameState.LEVEL_PASSED -> Unit
        }
    }

    private fun updateCatchPhase(deltaSeconds: Float) {
        updatePlayer(deltaSeconds)

        spawnTimer -= deltaSeconds
        if (spawnTimer <= 0f && score < targetScore) {
            spawnCup()
            spawnTimer = 0.75f
        }

        val iterator = cups.iterator()
        while (iterator.hasNext()) {
            val cup = iterator.next()
            cup.y += cup.speed * deltaSeconds

            if (circlesOverlap(playerX, playerY, playerRadius, cup.x, cup.y, cup.radius)) {
                iterator.remove()
                score += 1
                lasingMeter = min(100, lasingMeter + 10)
                if (score >= targetScore) {
                    enterEscapePhase()
                    return
                }
            } else if (cup.y - cup.radius > height) {
                iterator.remove()
            }
        }
    }

    private fun updateEscapePhase(deltaSeconds: Float) {
        updatePlayer(deltaSeconds)
        updateEnemies(deltaSeconds)

        for (enemy in enemies) {
            if (circlesOverlap(playerX, playerY, playerRadius, enemy.x, enemy.y, enemy.radius)) {
                state = GameState.GAME_OVER
                moveDirection = 0
                return
            }
        }

        if (playerX + playerRadius >= width - 4f) {
            state = GameState.LEVEL_PASSED
            moveDirection = 0
        }
    }

    private fun updatePlayer(deltaSeconds: Float) {
        val baseSpeed = width * 0.58f
        val speed = baseSpeed * lasingSpeedMultiplier()
        playerX += moveDirection * speed * deltaSeconds
        playerX = playerX.coerceIn(playerRadius, width - playerRadius)
    }

    private fun lasingSpeedMultiplier(): Float {
        return when (lasingMeter) {
            in 0..24 -> 1f
            in 25..49 -> 0.82f
            in 50..69 -> 0.62f
            in 70..89 -> 0.44f
            else -> 0.28f
        }
    }

    private fun spawnCup() {
        val radius = playerRadius * 0.42f
        val x = Random.nextFloat() * (width - radius * 2f) + radius
        val speed = height * Random.nextFloat().let { 0.32f + it * 0.18f }
        cups.add(TagayCup(x, -radius, radius, speed))
    }

    private fun enterEscapePhase() {
        cups.clear()
        state = GameState.ESCAPE
        enemies.clear()
        enemies.add(
            Enemy(
                x = width * 0.45f,
                y = height - playerRadius * 1.4f,
                radius = playerRadius * 0.92f,
                speed = width * 0.20f,
                label = "Pulis",
                color = Color.rgb(33, 117, 222)
            )
        )
        enemies.add(
            Enemy(
                x = width * 0.78f,
                y = height * 0.55f,
                radius = playerRadius * 0.88f,
                speed = width * 0.16f,
                label = "Tanod",
                color = Color.rgb(38, 166, 91)
            )
        )
    }

    private fun updateEnemies(deltaSeconds: Float) {
        enemies.forEach { enemy ->
            if (enemy.label == "Pulis") {
                moveToward(enemy, playerX, playerY, deltaSeconds)
            } else {
                val targetX = width * 0.82f
                enemy.x += (targetX - enemy.x).coerceIn(-enemy.speed, enemy.speed) * deltaSeconds
                enemy.y += if (playerY > enemy.y) enemy.speed * deltaSeconds else -enemy.speed * deltaSeconds
                enemy.y = enemy.y.coerceIn(enemy.radius + 90f, height - enemy.radius)
            }
        }
    }

    private fun moveToward(enemy: Enemy, targetX: Float, targetY: Float, deltaSeconds: Float) {
        val dx = targetX - enemy.x
        val dy = targetY - enemy.y
        val distance = sqrt(dx * dx + dy * dy)
        if (distance <= 1f) return

        enemy.x += dx / distance * enemy.speed * deltaSeconds
        enemy.y += dy / distance * enemy.speed * deltaSeconds
    }

    private fun drawFrame() {
        val canvas = holder.lockCanvas() ?: return
        try {
            drawGame(canvas)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawGame(canvas: Canvas) {
        canvas.drawColor(Color.rgb(12, 15, 22))
        drawHud(canvas)
        drawWorld(canvas)
        drawBottomText(canvas)
    }

    private fun drawHud(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Tagay: $score/$targetScore", 28f, 46f, textPaint)
        canvas.drawText("Lasing: $lasingMeter%", 28f, 88f, textPaint)

        smallTextPaint.textAlign = Paint.Align.RIGHT
        val objective = when (state) {
            GameState.CATCH_TAGAY -> "Objective: catch 10 cups"
            GameState.ESCAPE -> "Objective: avoid enemies and reach the right side"
            GameState.GAME_OVER -> "Game Over"
            GameState.LEVEL_PASSED -> "Level 1 Passed"
        }
        canvas.drawText(objective, width - 28f, 50f, smallTextPaint)
    }

    private fun drawWorld(canvas: Canvas) {
        drawExitLine(canvas)
        drawCups(canvas)
        drawEnemies(canvas)
        drawPlayer(canvas)
    }

    private fun drawExitLine(canvas: Canvas) {
        if (state != GameState.ESCAPE) return
        paint.color = Color.rgb(255, 213, 79)
        paint.strokeWidth = 8f
        canvas.drawLine(width - 10f, 110f, width - 10f, height - 80f, paint)
    }

    private fun drawCups(canvas: Canvas) {
        paint.color = Color.CYAN
        cups.forEach { cup ->
            val rect = RectF(cup.x - cup.radius, cup.y - cup.radius, cup.x + cup.radius, cup.y + cup.radius)
            canvas.drawRoundRect(rect, 8f, 8f, paint)
        }
    }

    private fun drawEnemies(canvas: Canvas) {
        enemies.forEach { enemy ->
            paint.color = enemy.color
            canvas.drawCircle(enemy.x, enemy.y, enemy.radius, paint)

            smallTextPaint.color = Color.WHITE
            smallTextPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(enemy.label, enemy.x, enemy.y + 8f, smallTextPaint)
        }
    }

    private fun drawPlayer(canvas: Canvas) {
        paint.color = Color.rgb(255, 213, 79)
        canvas.drawCircle(playerX, playerY, playerRadius, paint)

        textPaint.color = Color.rgb(36, 28, 6)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("M", playerX, playerY + 13f, textPaint)
        textPaint.color = Color.WHITE
    }

    private fun drawBottomText(canvas: Canvas) {
        smallTextPaint.textAlign = Paint.Align.CENTER
        smallTextPaint.color = Color.WHITE

        val message = when (state) {
            GameState.CATCH_TAGAY -> "Fictional adult comedy. Play responsibly. Touch left/right half to move."
            GameState.ESCAPE -> "Escape phase: reach the glowing right edge."
            GameState.GAME_OVER -> "Nahuli ka! Tap anywhere to restart Level 1."
            GameState.LEVEL_PASSED -> "Level 1 Passed! Tap anywhere to play again."
        }
        canvas.drawText(message, width / 2f, height - 28f, smallTextPaint)
    }

    private fun circlesOverlap(ax: Float, ay: Float, ar: Float, bx: Float, by: Float, br: Float): Boolean {
        val dx = ax - bx
        val dy = ay - by
        val radiusSum = ar + br
        return dx * dx + dy * dy <= radiusSum * radiusSum
    }
}
