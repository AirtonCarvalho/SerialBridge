package com.example.serialbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ConnectionMode { USB, BLUETOOTH, WIFI }

enum class LineEnding(val label: String, val suffix: String) {
    LF("LF (\\n)", "\n"),
    CRLF("CRLF (\\r\\n)", "\r\n"),
    NONE("Nenhum", "")
}

private const val ACTION_USB_PERMISSION = "com.example.serialbridge.USB_PERMISSION"

@SuppressLint("MissingPermission")
class SerialConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    // Estados gerais
    var mode by mutableStateOf(ConnectionMode.USB)
    var isConnected by mutableStateOf(false)
    var statusText by mutableStateOf("Desconectado")
    var hasBtPermission by mutableStateOf(checkBtPermissions())

    // Configurações de Protocolo / Engenharia Reversa
    var lineEnding by mutableStateOf(LineEnding.LF)
    var hexViewMode by mutableStateOf(false)
    var sendAsHexBytes by mutableStateOf(false)

    // Estados de Gravação (Firmware Flasher)
    var selectedFlashTarget by mutableStateOf(FlashTarget.ARDUINO_115200)
    var isFlashing by mutableStateOf(false)
    var flashProgress by mutableFloatStateOf(0f)

    // Estados do Executor de G-code / Script em Lote
    var isRunningScript by mutableStateOf(false)
    var isScriptPaused by mutableStateOf(false)
    var scriptProgress by mutableFloatStateOf(0f)
    var scriptStatusLine by mutableStateOf("")
    private var scriptJob: Job? = null
    private var ackSignal: CompletableDeferred<Boolean>? = null

    // Estados Bluetooth
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val btAdapter = btManager.adapter
    var pairedDevices by mutableStateOf<List<BluetoothDevice>>(emptyList())
    var selectedBtDevice by mutableStateOf<BluetoothDevice?>(null)
    private var btSocket: BluetoothSocket? = null

    // Estados Wi-Fi TCP
    var ipAddress by mutableStateOf("192.168.4.1")
    var portText by mutableStateOf("8080")
    private var tcpSocket: Socket? = null

    // Estados USB OTG
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    var usbDrivers by mutableStateOf<List<UsbSerialDriver>>(emptyList())
    var selectedUsbDriver by mutableStateOf<UsbSerialDriver?>(null)
    val baudRates = listOf(9600, 19200, 38400, 57600, 115200)
    var selectedBaudRate by mutableIntStateOf(115200)
    private var usbPort: UsbSerialPort? = null

    // Streams, Jobs e Terminal
    private var outputStream: OutputStream? = null
    private var readerJob: Job? = null
    val logs = mutableStateListOf<String>()
    var onLogAdded: (() -> Unit)? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (granted && device != null) {
                    val driver = usbDrivers.find { it.device.deviceId == device.deviceId }
                    if (driver != null) openUsbDriver(driver)
                } else {
                    statusText = "Permissão USB negada"
                    appendLog("ERR", "Permissão de acesso USB negada pelo usuário")
                }
            }
        }
    }

    fun registerUsbReceiver() {
        ContextCompat.registerReceiver(
            context,
            usbPermissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun unregisterUsbReceiver() {
        try {
            context.unregisterReceiver(usbPermissionReceiver)
        } catch (_: Exception) {}
    }

    fun checkBtPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun appendLog(prefix: String, msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add("[$time] $prefix: $msg")
        if (logs.size > 1500) {
            logs.removeAt(0)
        }
        onLogAdded?.invoke()
    }

    fun exportLogsToUri(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                val snapshot = logs.toList().joinToString("\n")
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(snapshot.toByteArray(Charsets.UTF_8))
                }
                withContext(Dispatchers.Main) {
                    appendLog("SYS", "Log exportado com sucesso (${logs.size} linhas)")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("ERR", "Falha ao exportar log: ${e.message}")
                }
            }
        }
    }

    fun refreshPairedDevices() {
        if (hasBtPermission && btAdapter != null) {
            pairedDevices = btAdapter.bondedDevices?.toList() ?: emptyList()
            if (selectedBtDevice == null && pairedDevices.isNotEmpty()) {
                selectedBtDevice = pairedDevices.first()
            }
        }
    }

    fun refreshUsbDevices() {
        val available = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        usbDrivers = available
        if (selectedUsbDriver == null || available.none { it.device.deviceId == selectedUsbDriver?.device?.deviceId }) {
            selectedUsbDriver = available.firstOrNull()
        }
    }

    // =========================================================================
    // EXECUTOR DE SCRIPT / G-CODE LINHA A LINHA COM CONTROLE DE FLUXO (OK)
    // =========================================================================
    fun startScriptFromUri(uri: Uri) {
        if (!isConnected) {
            appendLog("ERR", "Conecte a uma placa antes de rodar um script.")
            return
        }
        if (isFlashing || isRunningScript) return

        scriptJob = scope.launch(Dispatchers.IO) {
            try {
                val rawText = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw Exception("Não foi possível ler o arquivo")

                val commands = rawText.lines()
                    .map { line ->
                        // Remove comentários G-code (; ou entre parênteses)
                        line.substringBefore(";").replace(Regex("\\(.*?\\)"), "").trim()
                    }
                    .filter { it.isNotEmpty() }

                if (commands.isEmpty()) throw Exception("Nenhum comando válido encontrado no arquivo")

                withContext(Dispatchers.Main) {
                    isRunningScript = true
                    isScriptPaused = false
                    scriptProgress = 0f
                    appendLog("SYS", "Iniciando script: ${commands.size} comandos")
                }

                for ((index, cmd) in commands.withIndex()) {
                    while (isScriptPaused && isActive) {
                        kotlinx.coroutines.delay(100)
                    }
                    if (!isActive || !isConnected) break

                    withContext(Dispatchers.Main) {
                        scriptStatusLine = "[${index + 1}/${commands.size}] $cmd"
                    }

                    val ack = CompletableDeferred<Boolean>()
                    ackSignal = ack

                    writeRawString("$cmd\n", logLabel = "TX")

                    // Aguarda "OK" da placa (timeout de 5s para movimentos longos)
                    withTimeoutOrNull(5000) { ack.await() }
                    ackSignal = null

                    withContext(Dispatchers.Main) {
                        scriptProgress = (index + 1).toFloat() / commands.size.toFloat()
                    }
                }

                withContext(Dispatchers.Main) {
                    appendLog("SYS", "Execução do script finalizada.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("ERR", "Erro no script: ${e.message}")
                }
            } finally {
                ackSignal = null
                withContext(Dispatchers.Main) {
                    isRunningScript = false
                    isScriptPaused = false
                    scriptStatusLine = ""
                }
            }
        }
    }

    fun stopScript() {
        scriptJob?.cancel()
        ackSignal?.cancel()
        isRunningScript = false
        isScriptPaused = false
        scriptStatusLine = ""
        sendCommand("STOP", silent = false)
        appendLog("SYS", "Script abortado pelo usuário.")
    }

    // =========================================================================
    // GRAVADOR DE FIRMWARE (.HEX / .BIN)
    // =========================================================================
    fun flashFirmwareFromUri(uri: Uri) {
        val port = usbPort
        if (!isConnected || mode != ConnectionMode.USB || port == null) {
            appendLog("ERR", "Conecte a placa via USB (OTG) antes de gravar o firmware.")
            return
        }
        if (isFlashing) return

        scope.launch(Dispatchers.IO) {
            readerJob?.cancel()
            readerJob?.join()

            withContext(Dispatchers.Main) {
                isFlashing = true
                flashProgress = 0f
                statusText = "Gravando Firmware..."
            }

            try {
                val fileBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Exception("Não foi possível ler o arquivo selecionado")

                val logCallback: (String) -> Unit = { msg ->
                    scope.launch(Dispatchers.Main) { appendLog("FLASH", msg) }
                }
                val progressCallback: (Float) -> Unit = { p ->
                    scope.launch(Dispatchers.Main) { flashProgress = p }
                }

                when (selectedFlashTarget) {
                    FlashTarget.ARDUINO_115200 -> FirmwareFlasher.flashArduinoHex(port, fileBytes, 115200, logCallback, progressCallback)
                    FlashTarget.ARDUINO_57600 -> FirmwareFlasher.flashArduinoHex(port, fileBytes, 57600, logCallback, progressCallback)
                    FlashTarget.ESP32_APP -> FirmwareFlasher.flashEsp32Bin(port, fileBytes, 0x10000, logCallback, progressCallback)
                    FlashTarget.ESP32_MERGED -> FirmwareFlasher.flashEsp32Bin(port, fileBytes, 0x0, logCallback, progressCallback)
                }

                port.setParameters(selectedBaudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                withContext(Dispatchers.Main) {
                    statusText = "USB: $selectedBaudRate bps"
                }
                startUsbReader(port)

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("ERR", "Falha na gravação: ${e.message}")
                    statusText = "Erro na gravação"
                }
                try {
                    port.setParameters(selectedBaudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    startUsbReader(port)
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) { disconnect() }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isFlashing = false
                }
            }
        }
    }

    fun connect() {
        when (mode) {
            ConnectionMode.USB -> connectUsb()
            ConnectionMode.BLUETOOTH -> connectBluetooth()
            ConnectionMode.WIFI -> connectWifi()
        }
    }

    fun disconnect() {
        scriptJob?.cancel()
        readerJob?.cancel()
        try {
            outputStream?.close()
            btSocket?.close()
            tcpSocket?.close()
            usbPort?.close()
        } catch (_: Exception) {}
        outputStream = null
        btSocket = null
        tcpSocket = null
        usbPort = null
        isConnected = false
        isFlashing = false
        isRunningScript = false
        statusText = "Desconectado"
        appendLog("SYS", "Conexão encerrada")
    }

    fun sendCommand(cmd: String, silent: Boolean = false) {
        if (isFlashing) {
            if (!silent) appendLog("WARN", "Aguarde a gravação terminar")
            return
        }
        if (!isConnected) {
            if (!silent) appendLog("WARN", "Não conectado")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                if (sendAsHexBytes) {
                    val bytes = parseHexString(cmd)
                    writeBytes(bytes)
                    if (!silent) {
                        val hexFormatted = bytes.joinToString(" ") { "%02X".format(it) }
                        withContext(Dispatchers.Main) { appendLog("TX [HEX]", hexFormatted) }
                    }
                } else {
                    val payload = cmd + lineEnding.suffix
                    writeRawString(payload, logLabel = if (silent) null else "TX")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("ERR", "Falha ao enviar: ${e.message}")
                    if (e.message?.contains("HEX") != true) disconnect()
                }
            }
        }
    }

    private suspend fun writeRawString(payload: String, logLabel: String?) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        writeBytes(bytes)
        if (logLabel != null) {
            withContext(Dispatchers.Main) {
                appendLog(logLabel, payload.trim())
            }
        }
    }

    private fun writeBytes(bytes: ByteArray) {
        if (mode == ConnectionMode.USB) {
            usbPort?.write(bytes, 1000) ?: throw Exception("Porta USB fechada")
        } else {
            val out = outputStream ?: throw Exception("Stream fechada")
            out.write(bytes)
            out.flush()
        }
    }

    private fun parseHexString(input: String): ByteArray {
        val clean = input.replace("0x", "", ignoreCase = true)
            .replace(Regex("[^0-9A-Fa-f]"), "")
        if (clean.isEmpty() || clean.length % 2 != 0) {
            throw Exception("Sequência HEX inválida (use pares como: 1B 40 0D)")
        }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun connectUsb() {
        refreshUsbDevices()
        val driver = selectedUsbDriver
        if (driver == null) {
            appendLog("ERR", "Nenhuma placa USB detectada no cabo OTG")
            return
        }
        statusText = "Conectando USB..."
        if (usbManager.hasPermission(driver.device)) {
            openUsbDriver(driver)
        } else {
            val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
            usbManager.requestPermission(driver.device, pendingIntent)
        }
    }

    private fun openUsbDriver(driver: UsbSerialDriver) {
        scope.launch(Dispatchers.IO) {
            try {
                val connection = usbManager.openDevice(driver.device)
                    ?: throw Exception("Não foi possível abrir o dispositivo USB")
                val port = driver.ports[0]
                port.open(connection)
                port.setParameters(selectedBaudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                port.dtr = true
                port.rts = true

                usbPort = port
                val label = driver.device.productName ?: driver.javaClass.simpleName.replace("SerialDriver", "")
                withContext(Dispatchers.Main) {
                    isConnected = true
                    statusText = "USB: $selectedBaudRate bps"
                    appendLog("SYS", "Conectado via USB ($label @ $selectedBaudRate)")
                }
                startUsbReader(port)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText = "Falha USB"
                    appendLog("ERR", e.message ?: "Erro ao abrir porta serial")
                    disconnect()
                }
            }
        }
    }

    private fun connectBluetooth() {
        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { statusText = "Conectando BT..." }
                val device = selectedBtDevice ?: throw Exception("Nenhum dispositivo selecionado")
                val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                val socket = device.createRfcommSocketToServiceRecord(sppUuid)
                btAdapter?.cancelDiscovery()
                socket.connect()

                btSocket = socket
                outputStream = socket.outputStream

                withContext(Dispatchers.Main) {
                    isConnected = true
                    statusText = "BT: ${device.name}"
                    appendLog("SYS", "Conectado a ${device.name}")
                }
                startInputStreamReader(socket.inputStream)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText = "Falha BT"
                    appendLog("ERR", e.message ?: "Erro Bluetooth")
                    disconnect()
                }
            }
        }
    }

    private fun connectWifi() {
        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { statusText = "Conectando TCP..." }
                val port = portText.toIntOrNull() ?: 8080
                val socket = Socket()
                socket.connect(InetSocketAddress(ipAddress.trim(), port), 4000)

                tcpSocket = socket
                outputStream = socket.getOutputStream()

                withContext(Dispatchers.Main) {
                    isConnected = true
                    statusText = "TCP: $ipAddress:$port"
                    appendLog("SYS", "Conectado via Wi-Fi ($ipAddress:$port)")
                }
                startInputStreamReader(socket.getInputStream())
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText = "Falha TCP"
                    appendLog("ERR", e.message ?: "Erro Wi-Fi")
                    disconnect()
                }
            }
        }
    }

    private fun handleIncomingBytes(buffer: ByteArray, len: Int, lineBuilder: StringBuilder) {
        if (hexViewMode) {
            val hexStr = (0 until len).joinToString(" ") { "%02X".format(buffer[it]) }
            scope.launch(Dispatchers.Main) { appendLog("RX [HEX]", hexStr) }
            return
        }

        val chunk = String(buffer, 0, len, Charsets.UTF_8)
        for (ch in chunk) {
            if (ch == '\n') {
                val completeLine = lineBuilder.toString().trimEnd('\r')
                lineBuilder.clear()
                if (completeLine.isNotEmpty()) {
                    if (completeLine.startsWith("OK", ignoreCase = true)) {
                        ackSignal?.complete(true)
                    }
                    scope.launch(Dispatchers.Main) { appendLog("RX", completeLine) }
                }
            } else {
                lineBuilder.append(ch)
            }
        }
    }

    private fun startInputStreamReader(input: java.io.InputStream) {
        readerJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(1024)
            val lineBuilder = StringBuilder()
            try {
                while (isActive) {
                    val len = input.read(buffer)
                    if (len <= 0) break
                    handleIncomingBytes(buffer, len, lineBuilder)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isConnected) {
                        appendLog("ERR", "Perda de conexão: ${e.message}")
                        disconnect()
                    }
                }
            }
        }
    }

    private fun startUsbReader(port: UsbSerialPort) {
        readerJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(1024)
            val lineBuilder = StringBuilder()
            try {
                while (isActive) {
                    val len = port.read(buffer, 200)
                    if (len > 0) {
                        handleIncomingBytes(buffer, len, lineBuilder)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isConnected && !isFlashing) {
                        appendLog("ERR", "Cabo USB desconectado: ${e.message}")
                        disconnect()
                    }
                }
            }
        }
    }
}
