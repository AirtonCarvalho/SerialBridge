package com.example.serialbridge

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SerialBridgeScreen()
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun SerialBridgeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { SerialConnectionManager(context, scope) }
    val listState = rememberLazyListState()

    var usbDropdownExpanded by remember { mutableStateOf(false) }
    var baudDropdownExpanded by remember { mutableStateOf(false) }
    var targetDropdownExpanded by remember { mutableStateOf(false) }
    var btDropdownExpanded by remember { mutableStateOf(false) }
    var activeTab by remember { mutableIntStateOf(0) } // 0 = Terminal, 1 = Plotter, 2 = G-code / HEX
    var sliderValue by remember { mutableFloatStateOf(0f) }

    // Histórico estilo Linux
    var commandField by remember { mutableStateOf(TextFieldValue("")) }
    val commandHistory = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableIntStateOf(-1) }
    var draftBeforeHistory by remember { mutableStateOf("") }

    // Auto-scroll inteligente: acompanha o fim se o usuário estiver na base do console
    val isAtBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total == 0 || lastVisible >= total - 3
        }
    }

    fun navigateHistoryUp() {
        if (commandHistory.isEmpty()) return
        if (historyIndex == -1) {
            draftBeforeHistory = commandField.text
            historyIndex = commandHistory.lastIndex
        } else if (historyIndex > 0) {
            historyIndex--
        }
        val selected = commandHistory[historyIndex]
        commandField = TextFieldValue(text = selected, selection = TextRange(selected.length))
    }

    fun navigateHistoryDown() {
        if (historyIndex == -1) return
        if (historyIndex < commandHistory.lastIndex) {
            historyIndex++
            val selected = commandHistory[historyIndex]
            commandField = TextFieldValue(text = selected, selection = TextRange(selected.length))
        } else {
            historyIndex = -1
            commandField = TextFieldValue(
                text = draftBeforeHistory,
                selection = TextRange(draftBeforeHistory.length)
            )
        }
    }

    fun executeCommand() {
        val raw = commandField.text.trim()
        if (raw.isEmpty()) return

        if (commandHistory.lastOrNull() != raw) {
            commandHistory.add(raw)
        }
        historyIndex = -1
        draftBeforeHistory = ""
        commandField = TextFieldValue("")

        when (raw.lowercase()) {
            "clear" -> manager.logs.clear()
            "history" -> {
                manager.appendLog("SYS", "--- Histórico de Comandos ---")
                commandHistory.forEachIndexed { idx, item ->
                    manager.appendLog("SYS", "${idx + 1}  $item")
                }
            }
            else -> manager.sendCommand(raw)
        }
    }

    // Launchers de Arquivo (Firmware, G-code e Exportar Log)
    val firmwarePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) manager.flashFirmwareFromUri(uri)
    }

    val scriptPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) manager.startScriptFromUri(uri)
    }

    val exportLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) manager.exportLogsToUri(uri)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        manager.hasBtPermission = perms.values.all { it }
        if (manager.hasBtPermission) manager.refreshPairedDevices()
    }

    DisposableEffect(manager) {
        manager.onLogAdded = {
            scope.launch {
                if (manager.logs.isNotEmpty() && isAtBottom) {
                    listState.animateScrollToItem(manager.logs.size - 1)
                }
            }
        }
        manager.registerUsbReceiver()
        onDispose {
            manager.unregisterUsbReceiver()
            manager.disconnect()
        }
    }

    LaunchedEffect(Unit) {
        manager.refreshUsbDevices()
        if (!manager.hasBtPermission) {
            val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            permissionLauncher.launch(required)
        } else {
            manager.refreshPairedDevices()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 1. Cabeçalho e Status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("SerialBridge", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Surface(
                color = when {
                    manager.isFlashing || manager.isRunningScript -> Color(0xFFE65100)
                    manager.isConnected -> Color(0xFF1B5E20)
                    else -> Color(0xFF424242)
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = manager.statusText,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    fontSize = 11.sp,
                    color = Color.White
                )
            }
        }

        // 2. Seletor de Protocolo Físico
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = manager.mode == ConnectionMode.USB,
                onClick = {
                    if (!manager.isConnected) {
                        manager.mode = ConnectionMode.USB
                        manager.refreshUsbDevices()
                    }
                },
                label = { Text("USB (OTG)", fontSize = 12.sp) }
            )
            FilterChip(
                selected = manager.mode == ConnectionMode.BLUETOOTH,
                onClick = { if (!manager.isConnected) manager.mode = ConnectionMode.BLUETOOTH },
                label = { Text("Bluetooth", fontSize = 12.sp) }
            )
            FilterChip(
                selected = manager.mode == ConnectionMode.WIFI,
                onClick = { if (!manager.isConnected) manager.mode = ConnectionMode.WIFI },
                label = { Text("Wi-Fi", fontSize = 12.sp) }
            )
        }

        // 3. Painel de Conexão + Gravador .hex/.bin
        when (manager.mode) {
            ConnectionMode.USB -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1.4f)) {
                            OutlinedButton(
                                onClick = {
                                    manager.refreshUsbDevices()
                                    usbDropdownExpanded = true
                                },
                                enabled = !manager.isConnected,
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                val devLabel = manager.selectedUsbDriver?.let {
                                    it.device.productName ?: it.javaClass.simpleName.replace("SerialDriver", "")
                                } ?: "Nenhum USB"
                                Text(devLabel, maxLines = 1, fontSize = 12.sp)
                            }
                            DropdownMenu(
                                expanded = usbDropdownExpanded,
                                onDismissRequest = { usbDropdownExpanded = false }
                            ) {
                                if (manager.usbDrivers.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Nenhuma placa detectada") },
                                        onClick = { usbDropdownExpanded = false }
                                    )
                                } else {
                                    manager.usbDrivers.forEach { drv ->
                                        val name = drv.device.productName
                                            ?: drv.javaClass.simpleName.replace("SerialDriver", "")
                                        DropdownMenuItem(
                                            text = { Text("$name (VID:${drv.device.vendorId})") },
                                            onClick = {
                                                manager.selectedUsbDriver = drv
                                                usbDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Box(modifier = Modifier.weight(0.85f)) {
                            OutlinedButton(
                                onClick = { baudDropdownExpanded = true },
                                enabled = !manager.isConnected,
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 6.dp)
                            ) {
                                Text("${manager.selectedBaudRate}", fontSize = 12.sp)
                            }
                            DropdownMenu(
                                expanded = baudDropdownExpanded,
                                onDismissRequest = { baudDropdownExpanded = false }
                            ) {
                                manager.baudRates.forEach { rate ->
                                    DropdownMenuItem(
                                        text = { Text("$rate bps") },
                                        onClick = {
                                            manager.selectedBaudRate = rate
                                            baudDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = { if (manager.isConnected) manager.disconnect() else manager.connect() },
                            enabled = !manager.isFlashing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (manager.isConnected) Color(0xFFB71C1C) else MaterialTheme.colorScheme.primary
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text(if (manager.isConnected) "Sair" else "Conectar", fontSize = 12.sp)
                        }
                    }

                    Surface(
                        color = Color(0xFF1E1E1E),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(1.4f)) {
                                    OutlinedButton(
                                        onClick = { targetDropdownExpanded = true },
                                        enabled = !manager.isFlashing,
                                        modifier = Modifier.fillMaxWidth(),
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) {
                                        Text(
                                            text = manager.selectedFlashTarget.label,
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = targetDropdownExpanded,
                                        onDismissRequest = { targetDropdownExpanded = false }
                                    ) {
                                        FlashTarget.entries.forEach { target ->
                                            DropdownMenuItem(
                                                text = { Text(target.label) },
                                                onClick = {
                                                    manager.selectedFlashTarget = target
                                                    targetDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                Button(
                                    onClick = { firmwarePickerLauncher.launch(arrayOf("*/*")) },
                                    enabled = manager.isConnected && !manager.isFlashing,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("Gravar .hex/.bin", fontSize = 11.sp, maxLines = 1)
                                }
                            }

                            if (manager.isFlashing) {
                                LinearProgressIndicator(
                                    progress = { manager.flashProgress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            ConnectionMode.BLUETOOTH -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = {
                                manager.refreshPairedDevices()
                                btDropdownExpanded = true
                            },
                            enabled = !manager.isConnected,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(manager.selectedBtDevice?.name ?: "Selecionar Dispositivo", fontSize = 12.sp)
                        }
                        DropdownMenu(
                            expanded = btDropdownExpanded,
                            onDismissRequest = { btDropdownExpanded = false }
                        ) {
                            manager.pairedDevices.forEach { dev ->
                                DropdownMenuItem(
                                    text = { Text("${dev.name} (${dev.address})") },
                                    onClick = {
                                        manager.selectedBtDevice = dev
                                        btDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Button(
                        onClick = { if (manager.isConnected) manager.disconnect() else manager.connect() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (manager.isConnected) Color(0xFFB71C1C) else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(if (manager.isConnected) "Sair" else "Conectar", fontSize = 12.sp)
                    }
                }
            }

            ConnectionMode.WIFI -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manager.ipAddress,
                        onValueChange = { manager.ipAddress = it },
                        label = { Text("IP") },
                        enabled = !manager.isConnected,
                        singleLine = true,
                        modifier = Modifier.weight(1.4f)
                    )
                    OutlinedTextField(
                        value = manager.portText,
                        onValueChange = { manager.portText = it },
                        label = { Text("Porta") },
                        enabled = !manager.isConnected,
                        singleLine = true,
                        modifier = Modifier.weight(0.8f)
                    )
                    Button(
                        onClick = { if (manager.isConnected) manager.disconnect() else manager.connect() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (manager.isConnected) Color(0xFFB71C1C) else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(if (manager.isConnected) "Sair" else "Conectar", fontSize = 12.sp)
                    }
                }
            }
        }

        // 4. Abas de Ferramentas (Terminal | Plotter D-Pad | G-code & HEX)
        TabRow(selectedTabIndex = activeTab, modifier = Modifier.fillMaxWidth()) {
            Tab(
                selected = activeTab == 0,
                onClick = { activeTab = 0 },
                text = { Text("Terminal", fontSize = 12.sp) }
            )
            Tab(
                selected = activeTab == 1,
                onClick = { activeTab = 1 },
                text = { Text("Plotter", fontSize = 12.sp) }
            )
            Tab(
                selected = activeTab == 2,
                onClick = { activeTab = 2 },
                text = { Text("G-code / HEX", fontSize = 12.sp) }
            )
        }

        when (activeTab) {
            // ABA 0: CONTROLES RÁPIDOS + PWM
            0 -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("LED_ON", "LED_OFF", "STATUS", "STOP").forEach { cmd ->
                            FilledTonalButton(
                                onClick = { manager.sendCommand(cmd) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text(cmd, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("PWM: ${sliderValue.toInt()}", fontSize = 12.sp)
                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            valueRange = 0f..255f,
                            modifier = Modifier.weight(1f),
                            onValueChangeFinished = {
                                manager.sendCommand("PWM:${sliderValue.toInt()}")
                            }
                        )
                    }
                }
            }

            // ABA 1: D-PAD CONTÍNUO (PRESS & HOLD) PARA CODBOX PLOTTER
            1 -> {
                Surface(
                    color = Color(0xFF1A1A1A),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Cruz Direcional X / Y (envia enquanto segura, manda STOP ao soltar)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            HoldActionButton(label = "Y+ (Papel)", command = "Y+", manager = manager)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                HoldActionButton(label = "X- (Esq)", command = "X-", manager = manager)
                                Button(
                                    onClick = { manager.sendCommand("STOP") },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text("STOP", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                HoldActionButton(label = "X+ (Dir)", command = "X+", manager = manager)
                            }
                            HoldActionButton(label = "Y- (Recua)", command = "Y-", manager = manager)
                        }

                        // Ações de Caneta e Home
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            FilledTonalButton(
                                onClick = { manager.sendCommand("PEN_UP") },
                                contentPadding = PaddingValues(horizontal = 10.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("CANETA ↑", fontSize = 11.sp)
                            }
                            FilledTonalButton(
                                onClick = { manager.sendCommand("PEN_DOWN") },
                                contentPadding = PaddingValues(horizontal = 10.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("CANETA ↓", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { manager.sendCommand("HOME") },
                                contentPadding = PaddingValues(horizontal = 10.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("HOME (0,0)", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // ABA 2: EXECUTOR DE G-CODE + MODO HEX + TERMINADOR DE LINHA
            2 -> {
                Surface(
                    color = Color(0xFF1A1A1A),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Linha 1: Carregar G-code / Pausar / Abortar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { scriptPickerLauncher.launch(arrayOf("*/*")) },
                                enabled = manager.isConnected && !manager.isRunningScript,
                                modifier = Modifier.weight(1.3f),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text("Rodar G-code / .txt", fontSize = 11.sp)
                            }

                            if (manager.isRunningScript) {
                                FilledTonalButton(
                                    onClick = { manager.isScriptPaused = !manager.isScriptPaused },
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text(if (manager.isScriptPaused) "Retomar" else "Pausar", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = { manager.stopScript() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("Parar", fontSize = 11.sp)
                                }
                            }
                        }

                        if (manager.isRunningScript) {
                            Text(manager.scriptStatusLine, fontSize = 11.sp, color = Color(0xFFFFCC80))
                            LinearProgressIndicator(
                                progress = { manager.scriptProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Linha 2: Terminador (\n, \r\n, Nenhum) + Chaves HEX
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                LineEnding.entries.forEach { ending ->
                                    FilterChip(
                                        selected = manager.lineEnding == ending,
                                        onClick = { manager.lineEnding = ending },
                                        label = { Text(ending.label, fontSize = 10.sp) }
                                    )
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilterChip(
                                    selected = manager.hexViewMode,
                                    onClick = { manager.hexViewMode = !manager.hexViewMode },
                                    label = { Text("Ver HEX", fontSize = 10.sp) }
                                )
                                FilterChip(
                                    selected = manager.sendAsHexBytes,
                                    onClick = { manager.sendAsHexBytes = !manager.sendAsHexBytes },
                                    label = { Text("TX HEX", fontSize = 10.sp) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 5. Barra Superior do Console (Contador + Exportar Log + Limpar)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Console (${manager.logs.size})" + if (!isAtBottom) " [Scroll pausado]" else "",
                fontSize = 11.sp,
                color = if (!isAtBottom) Color(0xFFFFCC80) else Color(0xFF90A4AE)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        exportLogLauncher.launch("SerialBridge_$stamp.txt")
                    },
                    enabled = manager.logs.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text("Salvar Log", fontSize = 11.sp)
                }
                TextButton(
                    onClick = { manager.logs.clear() },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text("Limpar", fontSize = 11.sp)
                }
            }
        }

        // 6. Console Serial (RX / TX / FLASH)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF121212), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(manager.logs) { log ->
                    val color = when {
                        log.contains("TX") -> Color(0xFF81D4FA)
                        log.contains("RX") -> Color(0xFFA5D6A7)
                        log.contains("FLASH:") -> Color(0xFFFFCC80)
                        log.contains("ERR:") -> Color(0xFFEF9A9A)
                        else -> Color(0xFFB0BEC5)
                    }
                    Text(
                        text = log,
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // 7. Campo de Entrada Manual com Histórico Linux (↑/↓)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = commandField,
                onValueChange = {
                    commandField = it
                    historyIndex = -1
                },
                placeholder = {
                    Text(
                        if (manager.sendAsHexBytes) "Bytes HEX (ex: 1B 40 0D)..." else "Comando (clear, history...)",
                        fontSize = 12.sp
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { executeCommand() }),
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                Key.DirectionUp -> {
                                    navigateHistoryUp()
                                    true
                                }
                                Key.DirectionDown -> {
                                    navigateHistoryDown()
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    executeCommand()
                                    true
                                }
                                else -> false
                            }
                        } else {
                            false
                        }
                    }
            )

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FilledTonalButton(
                    onClick = { navigateHistoryUp() },
                    enabled = commandHistory.isNotEmpty(),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(width = 36.dp, height = 26.dp)
                ) {
                    Text("↑", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                FilledTonalButton(
                    onClick = { navigateHistoryDown() },
                    enabled = historyIndex != -1,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(width = 36.dp, height = 26.dp)
                ) {
                    Text("↓", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            Button(onClick = { executeCommand() }) {
                Text("Enviar")
            }
        }
    }
}

@Composable
fun HoldActionButton(
    label: String,
    command: String,
    manager: SerialConnectionManager
) {
    var isPressed by remember { mutableStateOf(false) }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            manager.sendCommand(command, silent = false)
            while (isPressed) {
                delay(140)
                manager.sendCommand(command, silent = true)
            }
            manager.sendCommand("STOP", silent = false)
        }
    }

    Surface(
        color = if (isPressed) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2C),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .height(36.dp)
            .pointerInput(command) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    }
                )
            }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (isPressed) Color.Black else Color.White
            )
        }
    }
}
