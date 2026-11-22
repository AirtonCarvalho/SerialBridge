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
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ConnectionMode { USB, BLUETOOTH, WIFI }

private const val ACTION_USB_PERMISSION = "com.example.serialbridge.USB_PERMISSION"

@SuppressLint("MissingPermission")
class SerialConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    var mode by mutableStateOf(ConnectionMode.USB)
    var isConnected by mutableStateOf(false)
    var statusText by mutableStateOf("Desconectado")
    var hasBtPermission by mutableStateOf(checkBtPermissions())

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val btAdapter = btManager.adapter
    var pairedDevices by mutableStateOf<List<BluetoothDevice>>(emptyList())
    var selectedBtDevice by mutableStateOf<BluetoothDevice?>(null)
    private var btSocket: BluetoothSocket? = null

    var ipAddress by mutableStateOf("192.168.4.1")
    var portText by mutableStateOf("8080")
    private var tcpSocket: Socket? = null

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    var usbDrivers by mutableStateOf<List<UsbSerialDriver>>(emptyList())
    var selectedUsbDriver by mutableStateOf<UsbSerialDriver?>(null)
    val baudRates = listOf(9600, 19200, 38400, 57600, 115200)
    var selectedBaudRate by mutableIntStateOf(115200)
    private var usbPort: UsbSerialPort? = null

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
        onLogAdded?.invoke()
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

    fun connect() {
        when (mode) {
            ConnectionMode.USB -> connectUsb()
            ConnectionMode.BLUETOOTH -> connectBluetooth()
            ConnectionMode.WIFI -> connectWifi()
        }
    }

    fun disconnect() {
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
        statusText = "Desconectado"
        appendLog("SYS", "Conexão encerrada")
    }

    fun sendCommand(cmd: String) {
        if (!isConnected) {
            appendLog("WARN", "Não conectado")
            return
        }
        val payload = if (cmd.endsWith("\n")) cmd else "$cmd\n"
        val bytes = payload.toByteArray(Charsets.UTF_8)

        scope.launch(Dispatchers.IO) {
            try {
                if (mode == ConnectionMode.USB) {
                    usbPort?.write(bytes, 1000) ?: throw Exception("Porta USB fechada")
                } else {
                    val out = outputStream ?: throw Exception("Stream fechada")
                    out.write(bytes)
                    out.flush()
                }
                withContext(Dispatchers.Main) {
                    appendLog("TX", cmd.trim())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("ERR", "Falha ao enviar: ${e.message}")
                    disconnect()
                }
            }
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
                val reader = BufferedReader(InputStreamReader(socket.inputStream))

                withContext(Dispatchers.Main) {
                    isConnected = true
                    statusText = "BT: ${device.name}"
                    appendLog("SYS", "Conectado a ${device.name}")
                }
                startStreamReader(reader)
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
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                withContext(Dispatchers.Main) {
                    isConnected = true
                    statusText = "TCP: $ipAddress:$port"
                    appendLog("SYS", "Conectado via Wi-Fi ($ipAddress:$port)")
                }
                startStreamReader(reader)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText = "Falha TCP"
                    appendLog("ERR", e.message ?: "Erro Wi-Fi")
                    disconnect()
                }
            }
        }
    }

    private fun startStreamReader(reader: BufferedReader) {
        readerJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val line = reader.readLine() ?: break
                    withContext(Dispatchers.Main) { appendLog("RX", line) }
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
                        val chunk = String(buffer, 0, len, Charsets.UTF_8)
                        for (ch in chunk) {
                            if (ch == '\n') {
                                val completeLine = lineBuilder.toString().trimEnd('\r')
                                lineBuilder.clear()
                                if (completeLine.isNotEmpty()) {
                                    withContext(Dispatchers.Main) { appendLog("RX", completeLine) }
                                }
                            } else {
                                lineBuilder.append(ch)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isConnected) {
                        appendLog("ERR", "Cabo USB desconectado: ${e.message}")
                        disconnect()
                    }
                }
            }
        }
    }
}
