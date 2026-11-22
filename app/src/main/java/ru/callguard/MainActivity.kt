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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Screen(dark)
                }
            }
        }
    }
}

@Composable
private fun Screen(dark: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
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
    val history = remember(tick) { AppLog.snapshot().take(4) }

    var query by remember { mutableStateOf("") }
    var queryBusy by remember { mutableStateOf(false) }
    var queryResult by remember { mutableStateOf<CheckResult?>(null) }

    val currentVersion = remember { UpdateChecker.currentVersion(ctx) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var checking by remember { mutableStateOf(false) }
    var updateNote by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }

    val checkUpdates: () -> Unit = {
        if (!checking) {
            checking = true
            updateNote = null
            scope.launch {
                val info = withContext(Dispatchers.IO) {
                    runCatching { UpdateChecker.latest() }.getOrNull()
                }
                update = info?.takeIf { UpdateChecker.isNewer(it.version, currentVersion) }
                if (info == null) updateNote = "Не удалось проверить"
                else if (update == null) updateNote = "Актуальная версия"
                checking = false
            }
        }
    }

    LaunchedEffect(Unit) { checkUpdates() }

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

            Text("CallGuard", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Спам — без звука",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            StatusCard(
                enabled = enabled,
                active = enabled && roleGranted,
                onToggle = {
                    Prefs.setEnabled(ctx, it)
                    tick++
                }
            )

            SetupCard(
                roleGranted = roleGranted,
                contactsGranted = contactsGranted,
                silence = silence,
                onRole = {
                    val rm = roleManager
                    if (rm != null && !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                        roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                    }
                },
                onContacts = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) },
                onSilence = {
                    silence = it
                    Prefs.setSilence(ctx, it)
                }
            )

            CheckCard(
                query = query,
                busy = queryBusy,
                result = queryResult,
                onQuery = { query = it },
                onCheck = {
                    val number = query
                    if (number.filter(Char::isDigit).length >= 10) {
                        queryBusy = true
                        queryResult = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { YandexChecker.check(number, 8000) }
                                    .getOrElse { CheckResult(Verdict.UNKNOWN, "ошибка сети") }
                            }
                            AppLog.add(number, result.verdict, result.reason)
                            queryResult = result
                            queryBusy = false
                            tick++
                        }
                    }
                }
            )

            if (history.isNotEmpty()) HistoryCard(history, dark)

            UpdatesCard(
                version = currentVersion,
                update = update,
                checking = checking,
                downloading = downloading,
                note = updateNote,
                onCheck = checkUpdates,
                onInstall = {
                    update?.let { info ->
                        downloading = true
                        updateNote = null
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                runCatching { UpdateChecker.download(ctx, info.url) }.getOrNull()
                            }
                            downloading = false
                            if (file == null) updateNote = "Ошибка скачивания"
                            else UpdateChecker.install(ctx, file)
                        }
                    }
                }
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusCard(enabled: Boolean, active: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(if (active) CleanGreen else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = when {
                    !enabled -> "Выключена"
                    !active -> "Нужна роль"
                    else -> "Включена"
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun SetupCard(
    roleGranted: Boolean,
    contactsGranted: Boolean,
    silence: Boolean,
    onRole: () -> Unit,
    onContacts: () -> Unit,
    onSilence: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PermissionRow("Роль определителя", roleGranted, onRole)
            PermissionRow("Контакты", contactsGranted, onContacts)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Заглушать спам",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = silence, onCheckedChange = onSilence)
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (granted) {
            Text("Готово", fontSize = 13.sp, color = CleanGreen)
        } else {
            Button(onClick = onAction, shape = RoundedCornerShape(14.dp)) {
                Text("Выдать", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun CheckCard(
    query: String,
    busy: Boolean,
    result: CheckResult?,
    onQuery: (String) -> Unit,
    onCheck: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Проверка номера", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                label = { Text("Номер") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )

            Button(
                onClick = onCheck,
                enabled = !busy && query.any(Char::isDigit),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (busy) "Проверяю…" else "Проверить", fontSize = 14.sp)
            }

            result?.let {
                Text(
                    text = verdictLabel(it.verdict) + " · " + it.reason,
                    fontSize = 13.sp,
                    color = verdictColor(it.verdict)
                )
            }
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
            Text("История", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            history.forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.number, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            entry.detail,
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

@Composable
private fun UpdatesCard(
    version: String,
    update: UpdateInfo?,
    checking: Boolean,
    downloading: Boolean,
    note: String?,
    onCheck: () -> Unit,
    onInstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Версия $version", fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onCheck, enabled = !checking) {
                    Text(if (checking) "Проверяю…" else "Проверить", fontSize = 13.sp)
                }
            }

            if (update != null) {
                Text(
                    "Доступно ${update.version}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Button(
                    onClick = onInstall,
                    enabled = !downloading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(if (downloading) "Скачиваю…" else "Обновить", fontSize = 14.sp)
                }
            }

            note?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun VerdictBadge(verdict: Verdict, dark: Boolean) {
    val tint = verdictColor(verdict)
    Box(
        Modifier
            .background(tint.copy(alpha = if (dark) 0.22f else 0.12f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(verdictLabel(verdict), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = tint)
    }
}

@Composable
private fun verdictColor(verdict: Verdict): Color = when (verdict) {
    Verdict.SPAM -> SpamRed
    Verdict.CLEAN -> CleanGreen
    Verdict.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun verdictLabel(verdict: Verdict): String = when (verdict) {
    Verdict.SPAM -> "Спам"
    Verdict.CLEAN -> "Обычный"
    Verdict.UNKNOWN -> "Нет данных"
}
