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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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
    var commandInput by remember { mutableStateOf("") }
    var sliderValue by remember { mutableFloatStateOf(0f) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            manager.flashFirmwareFromUri(uri)
        }
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
                if (manager.logs.isNotEmpty()) {
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
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Cabeçalho e Status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("SerialBridge", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Surface(
                color = when {
                    manager.isFlashing -> Color(0xFFE65100)
                    manager.isConnected -> Color(0xFF1B5E20)
                    else -> Color(0xFF424242)
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = manager.statusText,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    fontSize = 12.sp,
                    color = Color.White
                )
            }
        }

        // Seletor de Protocolo
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = manager.mode == ConnectionMode.USB,
                onClick = {
                    if (!manager.isConnected) {
                        manager.mode = ConnectionMode.USB
                        manager.refreshUsbDevices()
                    }
                },
                label = { Text("USB (OTG)") }
            )
            FilterChip(
                selected = manager.mode == ConnectionMode.BLUETOOTH,
                onClick = { if (!manager.isConnected) manager.mode = ConnectionMode.BLUETOOTH },
                label = { Text("Bluetooth") }
            )
            FilterChip(
                selected = manager.mode == ConnectionMode.WIFI,
                onClick = { if (!manager.isConnected) manager.mode = ConnectionMode.WIFI },
                label = { Text("Wi-Fi") }
            )
        }

        // Painel de Configuração da Conexão
        when (manager.mode) {
            ConnectionMode.USB -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1.4f)) {
                            OutlinedButton(
                                onClick = {
                                    manager.refreshUsbDevices()
                                    usbDropdownExpanded = true
                                },
                                enabled = !manager.isConnected,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val devLabel = manager.selectedUsbDriver?.let {
                                    it.device.productName ?: it.javaClass.simpleName.replace("SerialDriver", "")
                                } ?: "Nenhum USB"
                                Text(devLabel, maxLines = 1)
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

                        Box(modifier = Modifier.weight(0.9f)) {
                            OutlinedButton(
                                onClick = { baudDropdownExpanded = true },
                                enabled = !manager.isConnected,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("${manager.selectedBaudRate}")
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
                            )
                        ) {
                            Text(if (manager.isConnected) "Sair" else "Conectar")
                        }
                    }

                    // Painel de Gravação Nativa (.hex / .bin)
                    Surface(
                        color = Color(0xFF1E1E1E),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                                    onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
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
                            Text(manager.selectedBtDevice?.name ?: "Selecionar Dispositivo")
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
                        Text(if (manager.isConnected) "Sair" else "Conectar")
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
                        Text(if (manager.isConnected) "Sair" else "Conectar")
                    }
                }
            }
        }

        // Botões Rápidos de Comando
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
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

        // Controle Analógico (PWM)
        Column {
            Text("Controle Analógico (PWM): ${sliderValue.toInt()}", fontSize = 12.sp)
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                valueRange = 0f..255f,
                onValueChangeFinished = {
                    manager.sendCommand("PWM:${sliderValue.toInt()}")
                }
            )
        }

        // Console Serial (RX / TX / FLASH)
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
                        log.contains("TX:") -> Color(0xFF81D4FA)
                        log.contains("RX:") -> Color(0xFFA5D6A7)
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

        // Envio Manual
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = commandInput,
                onValueChange = { commandInput = it },
                placeholder = { Text("Enviar comando...") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                if (commandInput.isNotBlank()) {
                    manager.sendCommand(commandInput)
                    commandInput = ""
                }
            }) {
                Text("Enviar")
            }
        }
    }
}
