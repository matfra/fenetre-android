package cam.fenetre.android

import java.io.File
import java.io.FileOutputStream

class H264TsWriter(outputFile: File) : AutoCloseable {
    private val output = FileOutputStream(outputFile)
    private var patContinuityCounter = 0
    private var pmtContinuityCounter = 0
    private var videoContinuityCounter = 0

    fun writeHeaders() {
        writePat()
        writePmt()
    }

    fun writeAccessUnit(ptsUs: Long, accessUnit: ByteArray, keyFrame: Boolean) {
        val payload = AUD_NAL + accessUnit
        val pes = pesPacket(ptsUs, payload)
        var offset = 0
        var firstPacket = true
        while (offset < pes.size) {
            val packet = ByteArray(TS_PACKET_SIZE) { 0xff.toByte() }
            packet[0] = 0x47
            packet[1] = ((if (firstPacket) 0x40 else 0x00) or ((VIDEO_PID shr 8) and 0x1f)).toByte()
            packet[2] = (VIDEO_PID and 0xff).toByte()

            val includePcr = firstPacket && keyFrame
            val remaining = pes.size - offset
            val maxPayload = if (includePcr) TS_PAYLOAD_SIZE - PCR_ADAPTATION_BYTES else TS_PAYLOAD_SIZE
            val size = minOf(maxPayload, remaining)
            val needsStuffing = includePcr || size < TS_PAYLOAD_SIZE
            val payloadOffset = if (needsStuffing) {
                val adaptationLength = TS_PAYLOAD_SIZE - size - 1
                packet[3] = (0x30 or (videoContinuityCounter and 0x0f)).toByte()
                packet[4] = adaptationLength.toByte()
                if (adaptationLength > 0) {
                    packet[5] = if (includePcr) 0x10 else 0x00
                    if (includePcr) {
                        writePcr(packet, 6, ptsUs)
                    }
                }
                5 + adaptationLength
            } else {
                packet[3] = (0x10 or (videoContinuityCounter and 0x0f)).toByte()
                4
            }
            videoContinuityCounter = (videoContinuityCounter + 1) and 0x0f

            System.arraycopy(pes, offset, packet, payloadOffset, size)
            output.write(packet)
            offset += size
            firstPacket = false
        }
    }

    override fun close() {
        output.close()
    }

    private fun writePat() {
        val section = byteArrayOf(
            0x00,
            0xb0.toByte(), 0x0d,
            0x00, 0x01,
            0xc1.toByte(),
            0x00, 0x00,
            0x00, 0x01,
            (0xe0 or ((PMT_PID shr 8) and 0x1f)).toByte(), (PMT_PID and 0xff).toByte(),
        )
        writePsiPacket(0, section + crc32Mpeg(section), patContinuityCounter)
        patContinuityCounter = (patContinuityCounter + 1) and 0x0f
    }

    private fun writePmt() {
        val section = byteArrayOf(
            0x02,
            0xb0.toByte(), 0x12,
            0x00, 0x01,
            0xc1.toByte(),
            0x00, 0x00,
            (0xe0 or ((VIDEO_PID shr 8) and 0x1f)).toByte(), (VIDEO_PID and 0xff).toByte(),
            0xf0.toByte(), 0x00,
            0x1b,
            (0xe0 or ((VIDEO_PID shr 8) and 0x1f)).toByte(), (VIDEO_PID and 0xff).toByte(),
            0xf0.toByte(), 0x00,
        )
        writePsiPacket(PMT_PID, section + crc32Mpeg(section), pmtContinuityCounter)
        pmtContinuityCounter = (pmtContinuityCounter + 1) and 0x0f
    }

    private fun writePsiPacket(pid: Int, section: ByteArray, continuityCounter: Int) {
        val packet = ByteArray(TS_PACKET_SIZE) { 0xff.toByte() }
        packet[0] = 0x47
        packet[1] = (0x40 or ((pid shr 8) and 0x1f)).toByte()
        packet[2] = (pid and 0xff).toByte()
        packet[3] = (0x10 or (continuityCounter and 0x0f)).toByte()
        packet[4] = 0x00
        System.arraycopy(section, 0, packet, 5, section.size)
        output.write(packet)
    }

    private fun pesPacket(ptsUs: Long, payload: ByteArray): ByteArray {
        val packetLength = if (payload.size + 8 <= 0xffff) payload.size + 8 else 0
        val header = ByteArray(14)
        header[0] = 0x00
        header[1] = 0x00
        header[2] = 0x01
        header[3] = 0xe0.toByte()
        header[4] = ((packetLength shr 8) and 0xff).toByte()
        header[5] = (packetLength and 0xff).toByte()
        header[6] = 0x80.toByte()
        header[7] = 0x80.toByte()
        header[8] = 0x05
        writePts(header, 9, ptsUs)
        return header + payload
    }

    private fun writePts(target: ByteArray, offset: Int, ptsUs: Long) {
        val pts = ((ptsUs * 90L) / 1_000L) and 0x1ffffffffL
        target[offset] = (0x20L or (((pts shr 30) and 0x07) shl 1) or 0x01L).toByte()
        target[offset + 1] = ((pts shr 22) and 0xff).toByte()
        target[offset + 2] = ((((pts shr 15) and 0x7f) shl 1) or 0x01).toByte()
        target[offset + 3] = ((pts shr 7) and 0xff).toByte()
        target[offset + 4] = (((pts and 0x7f) shl 1) or 0x01).toByte()
    }

    private fun writePcr(target: ByteArray, offset: Int, ptsUs: Long) {
        val pcrBase = (ptsUs * 90L) / 1_000L
        target[offset] = ((pcrBase shr 25) and 0xff).toByte()
        target[offset + 1] = ((pcrBase shr 17) and 0xff).toByte()
        target[offset + 2] = ((pcrBase shr 9) and 0xff).toByte()
        target[offset + 3] = ((pcrBase shr 1) and 0xff).toByte()
        target[offset + 4] = ((((pcrBase and 0x01) shl 7) or 0x7e) and 0xff).toByte()
        target[offset + 5] = 0x00
    }

    private fun crc32Mpeg(data: ByteArray): ByteArray {
        var crc = 0xffffffff.toInt()
        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xff) shl 24)
            repeat(8) {
                crc = if ((crc and 0x80000000.toInt()) != 0) {
                    (crc shl 1) xor 0x04c11db7
                } else {
                    crc shl 1
                }
            }
        }
        return byteArrayOf(
            ((crc ushr 24) and 0xff).toByte(),
            ((crc ushr 16) and 0xff).toByte(),
            ((crc ushr 8) and 0xff).toByte(),
            (crc and 0xff).toByte(),
        )
    }

    companion object {
        private const val TS_PACKET_SIZE = 188
        private const val TS_PAYLOAD_SIZE = 184
        private const val PCR_ADAPTATION_BYTES = 8
        private const val PMT_PID = 0x1000
        private const val VIDEO_PID = 0x0100
        private val AUD_NAL = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x09, 0xf0.toByte())
    }
}
