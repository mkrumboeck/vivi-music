package com.music.vivi.vivimusic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.music.vivi.R
import com.music.vivi.playback.MusicService

@Composable
internal fun SonosDeviceDiscovery(service: MusicService?) {
    val handler = service?.sonosConnectionHandler ?: return
    val context = LocalContext.current
    fun hasPermission() = Build.VERSION.SDK_INT < 37 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) handler.discover()
    }
    LaunchedEffect(handler) {
        if (hasPermission()) handler.discover()
        else permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

}

@Composable
internal fun SonosDeviceSection(service: MusicService?, onSelected: () -> Unit) {
    val handler = service?.sonosConnectionHandler ?: return
    val state by handler.state.collectAsState()
    val preparing by handler.preparing.collectAsState()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.devices.forEach { device ->
            key(device.id) {
                Surface(
                    onClick = { handler.connect(device); onSelected() },
                    enabled = !preparing,
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.speaker_apple),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
