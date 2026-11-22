package com.example.serialbridge

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

enum class FlashTarget(val label: String) {
    ARDUINO_115200("Arduino Uno/Nano (115200)"),
    ARDUINO_57600("Arduino Nano Old (57600)"),
    ESP32_APP("ESP32 App .bin (0x10000)"),
    ESP32_MERGED("ESP32 Full .bin (0x0)")
}

object FirmwareFlasher {

    // =========================================================================
    // 1. GRAVADOR ARDUINO (STK500v1 / Optiboot - Arquivos Intel HEX)
    // =========================================================================
    fun flashArduinoHex(
        port: UsbSerialPort,
        hexBytes: ByteArray,
        baudRate: Int,
        onLog: (String) -> Unit,
        onProgress: (Float) -> Unit
    ) {
        onLog("Analisando arquivo Intel HEX...")
        val firmware = parseIntelHex(hexBytes)
        if (firmware.isEmpty()) throw Exception("Arquivo .hex vazio ou inválido")
        onLog("Firmware decodificado: ${firmware.size} bytes")

        port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

        // Pulso de Reset no ATmega328P via DTR/RTS
        onLog("Reiniciando Arduino no bootloader...")
        port.dtr = false
        port.rts = false
        Thread.sleep(50)
        port.dtr = true
        port.rts = true
        Thread.sleep(250)
        drainInput(port)

        // Sincronização STK_GET_SYNC (0x30 0x20 -> espera 0x14 0x10)
        var synced = false
        for (attempt in 1..15) {
            port.write(byteArrayOf(0x30, 0x20), 200)
            val resp = readExact(port, 2, 150)
            if (resp.size == 2 && resp[0] == 0x14.toByte() && resp[1] == 0x10.toByte()) {
                synced = true
                break
            }
            drainInput(port)
            Thread.sleep(50)
        }
        if (!synced) throw Exception("Falha de sincronismo STK500. Verifique o Baud Rate (115200 vs 57600).")
        onLog("Bootloader sincronizado!")

        // Entra no modo de programação (STK_ENTER_PROGMODE: 0x50 0x20)
        stkCommand(port, byteArrayOf(0x50, 0x20))

        val pageSize = 128 // ATmega328P usa páginas de 128 bytes (64 words)
        val totalPages = (firmware.size + pageSize - 1) / pageSize

        for (pageIdx in 0 until totalPages) {
            val byteAddr = pageIdx * pageSize
            val wordAddr = byteAddr / 2
            val end = minOf(byteAddr + pageSize, firmware.size)

            val pageData = ByteArray(pageSize) { 0xFF.toByte() }
            System.arraycopy(firmware, byteAddr, pageData, 0, end - byteAddr)

            // STK_LOAD_ADDRESS (0x55 low high 0x20)
            val addrCmd = byteArrayOf(
                0x55,
                (wordAddr and 0xFF).toByte(),
                ((wordAddr shr 8) and 0xFF).toByte(),
                0x20
            )
            stkCommand(port, addrCmd)

            // STK_PROG_PAGE (0x64 0x00 0x80 'F' [128 bytes] 0x20)
            val progCmd = ByteArray(4 + pageSize + 1)
            progCmd[0] = 0x64
            progCmd[1] = 0x00
            progCmd[2] = pageSize.toByte()
            progCmd[3] = 0x46 // 'F' = Flash
            System.arraycopy(pageData, 0, progCmd, 4, pageSize)
            progCmd[4 + pageSize] = 0x20

            stkCommand(port, progCmd, timeoutMs = 1000)
            onProgress((pageIdx + 1).toFloat() / totalPages.toFloat())
        }

        // Sai do modo de programação e reinicia o sketch (STK_LEAVE_PROGMODE: 0x51 0x20)
        stkCommand(port, byteArrayOf(0x51, 0x20))
        onLog("Gravação no Arduino concluída com sucesso!")
    }

    private fun stkCommand(port: UsbSerialPort, cmd: ByteArray, timeoutMs: Int = 400) {
        port.write(cmd, timeoutMs)
        val resp = readExact(port, 2, timeoutMs)
        if (resp.size != 2 || resp[0] != 0x14.toByte() || resp[1] != 0x10.toByte()) {
            throw Exception("Erro de resposta STK500 (recebido: ${resp.joinToString { "%02X".format(it) }})")
        }
    }

    private fun parseIntelHex(hexBytes: ByteArray): ByteArray {
        val text = String(hexBytes, Charsets.UTF_8)
        val mem = sortedMapOf<Int, Byte>()
        var baseAddr = 0

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (!line.startsWith(":") || line.length < 11) continue

            val byteCount = line.substring(1, 3).toInt(16)
            val address = line.substring(3, 7).toInt(16)
            val recordType = line.substring(7, 9).toInt(16)

            when (recordType) {
                0x00 -> { // Dados
                    for (i in 0 until byteCount) {
                        val b = line.substring(9 + i * 2, 11 + i * 2).toInt(16).toByte()
                        mem[baseAddr + address + i] = b
                    }
                }
                0x01 -> break // Fim de arquivo (EOF)
                0x02 -> baseAddr = line.substring(9, 13).toInt(16) shl 4
                0x04 -> baseAddr = line.substring(9, 13).toInt(16) shl 16
            }
        }

        if (mem.isEmpty()) return ByteArray(0)
        val maxAddr = mem.lastKey()
        val out = ByteArray(maxAddr + 1) { 0xFF.toByte() }
        for ((addr, value) in mem) {
            out[addr] = value
        }
        return out
    }

    // =========================================================================
    // 2. GRAVADOR ESP32 (ROM Bootloader SLIP + Zlib Deflate - Arquivos .bin)
    // =========================================================================
    fun flashEsp32Bin(
        port: UsbSerialPort,
        binBytes: ByteArray,
        flashOffset: Int,
        onLog: (String) -> Unit,
        onProgress: (Float) -> Unit
    ) {
        if (binBytes.isEmpty()) throw Exception("Arquivo .bin vazio")
        port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

        // Sequência automática de entrada em Download Mode (DTR/RTS)
        onLog("Colocando ESP32 em modo Bootloader...")
        port.dtr = false
        port.rts = true  // EN = LOW (Reset)
        Thread.sleep(100)
        port.dtr = true  // IO0 = LOW (Boot)
        port.rts = false // EN = HIGH (Libera reset)
        Thread.sleep(50)
        port.dtr = false
        Thread.sleep(100)
        drainInput(port)

        // Comando SYNC (0x08) com payload padrão de 36 bytes
        val syncPayload = ByteArray(36) { 0x55 }
        syncPayload[0] = 0x07
        syncPayload[1] = 0x07
        syncPayload[2] = 0x12
        syncPayload[3] = 0x20

        var synced = false
        for (attempt in 1..15) {
            try {
                espSendSlipPacket(port, 0x08, syncPayload)
                val resp = espReadSlipResponse(port, 0x08, 150)
                if (resp != null) {
                    synced = true
                    break
                }
            } catch (_: Exception) {}
            drainInput(port)
            Thread.sleep(50)
        }
        if (!synced) throw Exception("ESP32 não entrou em Download Mode. Segure o botão BOOT na placa e tente novamente.")
        onLog("ROM Bootloader ESP32 sincronizado!")

        // SPI_ATTACH (0x0D) - inicializa o chip de memória Flash SPI
        espCommand(port, 0x0D, ByteArray(8))

        // Comprime o firmware com Zlib para gravação rápida e segura
        onLog("Comprimindo ${binBytes.size} bytes (Zlib)...")
        val compressed = zlibCompress(binBytes)
        val blockSize = 0x400 // 1024 bytes por bloco
        val numBlocks = (compressed.size + blockSize - 1) / blockSize

        // FLASH_DEFL_BEGIN (0x10): [uncompressedSize, numBlocks, blockSize, offset]
        val beginBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(binBytes.size)
            .putInt(numBlocks)
            .putInt(blockSize)
            .putInt(flashOffset)
            .array()
        onLog("Apagando setor Flash em 0x${flashOffset.toString(16)}...")
        espCommand(port, 0x10, beginBuf, timeoutMs = 15000)

        // FLASH_DEFL_DATA (0x11): [dataSize, seq, 0, 0, data...]
        for (seq in 0 until numBlocks) {
            val start = seq * blockSize
            val end = minOf(start + blockSize, compressed.size)
            val chunkLen = end - start

            val pktBuf = ByteBuffer.allocate(16 + chunkLen).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(chunkLen)
                .putInt(seq)
                .putInt(0)
                .putInt(0)
            pktBuf.put(compressed, start, chunkLen)

            val checksum = espChecksum(compressed, start, chunkLen)
            espCommand(port, 0x11, pktBuf.array(), checksum = checksum, timeoutMs = 3000)
            onProgress((seq + 1).toFloat() / numBlocks.toFloat())
        }

        // FLASH_DEFL_END (0x12): [rebootFlag = 0 (não reinicia antes do reset físico)]
        val endBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array()
        espCommand(port, 0x12, endBuf, timeoutMs = 3000)

        // Pulsa EN (RTS) para reiniciar o ESP32 no novo firmware
        port.dtr = false
        port.rts = true
        Thread.sleep(100)
        port.rts = false
        onLog("Firmware gravado no ESP32 e placa reiniciada!")
    }

    private fun espCommand(
        port: UsbSerialPort,
        op: Int,
        data: ByteArray,
        checksum: Int = 0,
        timeoutMs: Int = 1000
    ) {
        espSendSlipPacket(port, op, data, checksum)
        val resp = espReadSlipResponse(port, op, timeoutMs)
            ?: throw Exception("Timeout no comando ESP 0x${op.toString(16)}")
        if (resp.size >= 2 && resp[0] != 0.toByte()) {
            throw Exception("Erro interno ESP32 (código ${resp[1]}) no op 0x${op.toString(16)}")
        }
    }

    private fun espSendSlipPacket(port: UsbSerialPort, op: Int, data: ByteArray, checksum: Int = 0) {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x00) // Direção: Request
            .put(op.toByte())
            .putShort(data.size.toShort())
            .putInt(checksum)
            .array()

        val slip = ByteArrayOutputStream()
        slip.write(0xC0)
        for (b in header) writeSlipByte(slip, b)
        for (b in data) writeSlipByte(slip, b)
        slip.write(0xC0)

        port.write(slip.toByteArray(), 1000)
    }

    private fun writeSlipByte(out: ByteArrayOutputStream, b: Byte) {
        when (b) {
            0xC0.toByte() -> { out.write(0xDB); out.write(0xDC) }
            0xDB.toByte() -> { out.write(0xDB); out.write(0xDD) }
            else -> out.write(b.toInt())
        }
    }

    private fun espReadSlipResponse(port: UsbSerialPort, expectedOp: Int, timeoutMs: Int): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val packet = ByteArrayOutputStream()
        var inFrame = false
        var escape = false
        val buf = ByteArray(256)

        while (System.currentTimeMillis() < deadline) {
            val len = port.read(buf, 50)
            for (i in 0 until len) {
                val b = buf[i]
                if (!inFrame) {
                    if (b == 0xC0.toByte()) {
                        inFrame = true
                        packet.reset()
                    }
                } else {
                    if (b == 0xC0.toByte()) {
                        val raw = packet.toByteArray()
                        inFrame = false
                        if (raw.size >= 8 && raw[0] == 0x01.toByte() && (raw[1].toInt() and 0xFF) == expectedOp) {
                            return raw.copyOfRange(8, raw.size)
                        }
                        packet.reset()
                    } else if (escape) {
                        escape = false
                        if (b == 0xDC.toByte()) packet.write(0xC0)
                        else if (b == 0xDD.toByte()) packet.write(0xDB)
                    } else if (b == 0xDB.toByte()) {
                        escape = true
                    } else {
                        packet.write(b.toInt())
                    }
                }
            }
        }
        return null
    }

    private fun espChecksum(data: ByteArray, offset: Int, len: Int): Int {
        var cs = 0xEF
        for (i in offset until offset + len) {
            cs = cs xor (data[i].toInt() and 0xFF)
        }
        return cs
    }

    private fun zlibCompress(input: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(input)
        deflater.finish()
        val out = ByteArrayOutputStream(input.size)
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            out.write(buf, 0, count)
        }
        deflater.end()
        return out.toByteArray()
    }

    // =========================================================================
    // UTILITÁRIOS DE BUFFER SERIAL
    // =========================================================================
    private fun drainInput(port: UsbSerialPort) {
        val dump = ByteArray(512)
        while (port.read(dump, 20) > 0) { /* descarta lixo residual */ }
    }

    private fun readExact(port: UsbSerialPort, count: Int, timeoutMs: Int): ByteArray {
        val out = ByteArray(count)
        var total = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        val temp = ByteArray(64)
        while (total < count && System.currentTimeMillis() < deadline) {
            val n = port.read(temp, 30)
            if (n > 0) {
                val copy = minOf(n, count - total)
                System.arraycopy(temp, 0, out, total, copy)
                total += copy
            }
        }
        return out.copyOf(total)
    }
}
