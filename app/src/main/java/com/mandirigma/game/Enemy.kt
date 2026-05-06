package com.mandirigma.game

data class Enemy(
    var x: Float,
    var y: Float,
    val radius: Float,
    val speed: Float,
    val label: String,
    val color: Int
)
