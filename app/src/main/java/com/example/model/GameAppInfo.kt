package com.example.model

import android.graphics.drawable.Drawable

data class GameAppInfo(
    val appName: String,
    val packageName: String,
    val icon: Drawable? = null,
    val isGameCategory: Boolean = false,
    val isFavorite: Boolean = false
)
