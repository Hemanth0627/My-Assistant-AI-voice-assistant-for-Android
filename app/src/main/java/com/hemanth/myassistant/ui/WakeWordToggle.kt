package com.hemanth.myassistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hemanth.myassistant.wakeword.WakeWordBus
import com.hemanth.myassistant.wakeword.WakeWordService

/** The on/off switch for the "Hey Brain" wake word. */
@Composable
fun WakeWordToggle() {
    val context = LocalContext.current
    val running by WakeWordBus.serviceRunning.collectAsState()
    val error by WakeWordBus.lastError.collectAsState()
    var hint by remember { mutableStateOf<String?>(null) }

    fun startService() {
        ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
    }

    // Asks for microphone (and notification) permission, then starts the service.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) startService()
        else hint = "Microphone permission is needed for the wake word."
    }

    fun turnOn() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) startService() else permissionLauncher.launch(needed.toTypedArray())
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Wake word \"Hey Brain\"",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = running,
                onCheckedChange = { on ->
                    hint = null
                    WakeWordBus.lastError.value = null
                    if (on) turnOn()
                    else context.stopService(Intent(context, WakeWordService::class.java))
                }
            )
        }

        // Needed so the assistant can pop up over other apps when it hears you.
        if (running && !Settings.canDrawOverlays(context)) {
            TextButton(onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) {
                Text("Allow \"Display over other apps\" so Brain can pop up")
            }
        }

        (error ?: hint)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
