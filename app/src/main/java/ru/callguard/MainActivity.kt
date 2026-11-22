package ru.callguard

import android.Manifest
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Accent = Color(0xFF2F6BFF)
private val SpamRed = Color(0xFFE5484D)
private val CleanGreen = Color(0xFF2FA36B)
private val MutedLight = Color(0xFF6B7280)
private val MutedDark = Color(0xFF9AA4B2)

private val LightScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFFF6F7FB),
    surface = Color.White,
    onSurface = Color(0xFF101318),
    surfaceVariant = Color(0xFFEEF1F7),
    onSurfaceVariant = MutedLight
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7DA2FF),
    onPrimary = Color(0xFF06122E),
    background = Color(0xFF0B0E14),
    surface = Color(0xFF141922),
    onSurface = Color(0xFFF2F4F8),
    surfaceVariant = Color(0xFF1D2330),
    onSurfaceVariant = MutedDark
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Screen(dark)
                }
            }
        }
    }
}

@Composable
private fun Screen(dark: Boolean) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }

    val roleManager = remember { ctx.getSystemService(RoleManager::class.java) }
    val roleGranted = remember(tick) {
        roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
    }
    val contactsGranted = remember(tick) {
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
    val enabled = remember(tick) { Prefs.isEnabled(ctx) }
    var silence by remember(tick) { mutableStateOf(Prefs.isSilence(ctx)) }
    val history = remember(tick) { AppLog.snapshot().take(5) }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { tick++ }

    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { tick++ }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            Text(
                text = "CallGuard",
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Тишина для спам-звонков, обычный звонок для остальных",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            StatusCard(
                enabled = enabled,
                roleGranted = roleGranted,
                onToggle = {
                    Prefs.setEnabled(ctx, it)
                    tick++
                }
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(
                        text = "Настройка",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    PermissionRow(
                        title = "Роль определителя номера",
                        granted = roleGranted,
                        grantedText = "Выдана",
                        actionText = "Выдать",
                        onAction = {
                            val rm = roleManager
                            if (rm != null && !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                                roleLauncher.launch(
                                    rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                                )
                            }
                        }
                    )

                    PermissionRow(
                        title = "Доступ к контактам",
                        granted = contactsGranted,
                        grantedText = "Разрешён",
                        actionText = "Разрешить",
                        onAction = {
                            contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
                        }
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Заглушать спам",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Звонок не блокируется, только без звука",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = silence,
                            onCheckedChange = {
                                silence = it
                                Prefs.setSilence(ctx, it)
                            }
                        )
                    }
                }
            }

            HistoryCard(history, dark)

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusCard(enabled: Boolean, roleGranted: Boolean, onToggle: (Boolean) -> Unit) {
    val active = enabled && roleGranted
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        roleGranted -> CleanGreen
        else -> Color(0xFFE0A100)
    }
    val title = when {
        !enabled -> "Защита выключена"
        !roleGranted -> "Нужна роль определителя"
        else -> "Защита включена"
    }
    val subtitle = when {
        !enabled -> "Звонки не проверяются"
        !roleGranted -> "Без роли Android не отдаёт номера"
        else -> "Незнакомые номера проверяются по данным Яндекса"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    grantedText: String,
    actionText: String,
    onAction: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (granted) grantedText else "Не выдано",
                fontSize = 12.sp,
                color = if (granted) CleanGreen else Color(0xFFE0A100)
            )
        }
        Button(
            onClick = onAction,
            enabled = !granted,
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(if (granted) "Готово" else actionText, fontSize = 14.sp)
        }
    }
}

@Composable
private fun HistoryCard(history: List<AppLog.Entry>, dark: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Последние проверки",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (history.isEmpty()) {
                Text(
                    text = "Пока пусто. Позвоните с незнакомого номера.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                history.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = entry.number,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = entry.detail,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        VerdictBadge(entry.verdict, dark)
                    }
                }
            }
        }
    }
}

@Composable
private fun VerdictBadge(verdict: Verdict, dark: Boolean) {
    val (label, tint) = when (verdict) {
        Verdict.SPAM -> "Спам" to SpamRed
        Verdict.CLEAN -> "Обычный" to CleanGreen
        Verdict.UNKNOWN -> "Нет данных" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .background(tint.copy(alpha = if (dark) 0.22f else 0.12f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = tint)
    }
}
