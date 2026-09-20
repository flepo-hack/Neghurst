package com.example.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.example.model.GameAppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class InstalledAppsRepository(private val context: Context) {

    suspend fun getInstalledGamesAndApps(): List<GameAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

        val appList = mutableListOf<GameAppInfo>()
        val myPackage = context.packageName

        for (info in resolveInfos) {
            val pkg = info.activityInfo.packageName
            if (pkg == myPackage) continue // Exclude Rendera itself from target list

            val appName = info.loadLabel(pm).toString()
            val icon = try {
                info.loadIcon(pm)
            } catch (e: Exception) {
                null
            }

            var isGame = false
            try {
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(pkg, 0)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    isGame = (appInfo.category == ApplicationInfo.CATEGORY_GAME)
                }
            } catch (e: Exception) {
                // Ignore
            }

            // Keyword heuristics for popular gaming terms if manifest category is missing
            val lowerName = appName.lowercase()
            val lowerPkg = pkg.lowercase()
            if (!isGame && (
                    lowerName.contains("game") || lowerName.contains("brawl") ||
                    lowerName.contains("survivor") || lowerName.contains("arena") ||
                    lowerName.contains("strike") || lowerName.contains("clash") ||
                    lowerName.contains("rpg") || lowerName.contains("shooter") ||
                    lowerName.contains("craft") || lowerPkg.contains("game") ||
                    lowerPkg.contains("unity") || lowerPkg.contains("unreal")
                )) {
                isGame = true
            }

            appList.add(
                GameAppInfo(
                    appName = appName,
                    packageName = pkg,
                    icon = icon,
                    isGameCategory = isGame
                )
            )
        }

        // Sort: Games first, then alphabetically
        appList.sortedWith(
            compareByDescending<GameAppInfo> { it.isGameCategory }
                .thenBy { it.appName.lowercase() }
        )
    }

    fun launchApp(packageName: String): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
