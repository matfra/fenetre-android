package cam.fenetre.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

class FenetreTimelapse(private val storage: FenetreStorage) {
    private val frequentExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fenetre-hls")
    }
    private val dailyExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fenetre-daily-mp4")
    }
    private val running = AtomicBoolean(false)
    private val dailyRunning = AtomicBoolean(false)

    fun scheduleFrequent() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        frequentExecutor.execute {
            try {
                val dayDir = storage.currentDayDir()
                Log.i(TAG, "Starting frequent timelapse for ${dayDir.name}")
                createIncrementalHls(dayDir)
            } catch (exception: Exception) {
                Log.e(TAG, "Frequent timelapse generation failed", exception)
            } finally {
                running.set(false)
            }
        }
    }

    fun scheduleDailyForCompletedDays() {
        if (!dailyRunning.compareAndSet(false, true)) {
            return
        }
        dailyExecutor.execute {
            try {
                val currentDay = storage.currentDayDir().name
                val dayDir = storage.dayDirs()
                    .filter { it.name < currentDay }
                    .lastOrNull { !File(it, "${it.name}.mp4").exists() }
                if (dayDir != null) {
                    Log.i(TAG, "Starting completed-day timelapse for ${dayDir.name}")
                    createDailyMp4(dayDir, overwrite = false)
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Daily timelapse generation failed", exception)
            } finally {
                dailyRunning.set(false)
            }
        }
    }

    fun scheduleDailyForCurrentDay() {
        if (!dailyRunning.compareAndSet(false, true)) {
            return
        }
        dailyExecutor.execute {
            try {
                val dayDir = storage.currentDayDir()
                Log.i(TAG, "Starting current-day timelapse for ${dayDir.name}")
                createDailyMp4(dayDir, overwrite = true)
            } catch (exception: Exception) {
                Log.e(TAG, "Daily timelapse generation failed", exception)
            } finally {
                dailyRunning.set(false)
            }
        }
    }

    fun stop() {
        frequentExecutor.shutdownNow()
        dailyExecutor.shutdownNow()
    }

    private fun createIncrementalHls(dayDir: File): Boolean {
        if (!dayDir.isDirectory) {
            return false
        }
        val images = dayDir.listFiles { file ->
            file.isFile && file.extension.lowercase(Locale.US) == "jpg"
        }?.sortedBy { it.name }.orEmpty()
        if (images.size < MIN_IMAGES) {
            return false
        }
        Log.i(TAG, "Frequent timelapse ${dayDir.name}: ${images.size} source images")

        val baseName = dayDir.name
        val manifestFile = File(dayDir, ".$baseName.hls-manifest.json")
        val manifest = HlsManifest.read(manifestFile)
        val resetManifest = manifest.lastImage != null && images.none { it.name == manifest.lastImage }
        val existingSegments = if (resetManifest) emptyList() else manifest.segments
        val newImages = when {
            manifest.lastImage == null -> images.takeLast(BOOTSTRAP_IMAGE_LIMIT)
            images.any { it.name == manifest.lastImage } -> {
                val lastIndex = images.indexOfFirst { it.name == manifest.lastImage }
                images.drop(lastIndex + 1)
            }
            else -> {
                cleanupSegments(dayDir)
                images
            }
        }
        if (newImages.isEmpty()) {
            writePlaylist(dayDir, baseName, existingSegments)
            return true
        }

        val completeFrameCount = (newImages.size / HLS_SEGMENT_FRAME_COUNT) * HLS_SEGMENT_FRAME_COUNT
        if (completeFrameCount == 0) {
            writePlaylist(dayDir, baseName, existingSegments)
            return true
        }

        val segments = existingSegments.toMutableList()
        var startPtsUs = (segments.sumOf { it.durationSeconds } * 1_000_000.0).toLong()
        val encodedImages = newImages.take(completeFrameCount)
        encodedImages.chunked(HLS_SEGMENT_FRAME_COUNT).forEach { segmentImages ->
            val segmentIndex = segments.size
            val segmentFile = File(dayDir, "segment-${segmentIndex.toString().padStart(6, '0')}.ts")
            val durationSeconds = encodeSegment(segmentImages, segmentFile, startPtsUs)
            segments += HlsSegment(segmentFile.name, durationSeconds)
            startPtsUs += (durationSeconds * 1_000_000.0).toLong()
        }
        HlsManifest(encodedImages.last().name, segments).write(manifestFile)
        writePlaylist(dayDir, baseName, segments)
        storage.writeTimelapseMetadata(dayDir, "${baseName}.m3u8", segments.size)
        Log.i(TAG, "Frequent timelapse ${dayDir.name}: wrote ${segments.size} HLS segments")
        return true
    }

    private fun cleanupSegments(dayDir: File) {
        dayDir.listFiles { file -> file.name.startsWith("segment-") && file.extension == "ts" }
            ?.forEach { it.delete() }
    }

    private fun encodeSegment(images: List<File>, outputFile: File, startPtsUs: Long): Double {
        val frameDurationUs = 1_000_000L / FRAME_RATE
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, HLS_VIDEO_WIDTH, HLS_VIDEO_HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, HLS_BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val info = MediaCodec.BufferInfo()
        var codecConfig = ByteArray(0)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            H264TsWriter(outputFile).use { writer ->
                writer.writeHeaders()
                images.forEachIndexed { index, imageFile ->
                    val inputIndex = awaitInputBuffer(codec, "HLS frame $index")
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    val frame = decodeFrameNv12(imageFile, HLS_VIDEO_WIDTH, HLS_VIDEO_HEIGHT)
                    inputBuffer?.clear()
                    inputBuffer?.put(frame)
                    codec.queueInputBuffer(inputIndex, 0, frame.size, startPtsUs + index * frameDurationUs, 0)
                    drainEncoder(codec, info, writer, getCodecConfig = { codecConfig }) { codecConfig = it }
                }

                val inputIndex = awaitInputBuffer(codec, "HLS end of stream")
                codec.queueInputBuffer(inputIndex, 0, 0, startPtsUs + images.size * frameDurationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drainEncoder(codec, info, writer, endOfStream = true, getCodecConfig = { codecConfig }) { codecConfig = it }
            }
        } finally {
            codec.stop()
            codec.release()
        }
        return images.size.toDouble() / FRAME_RATE.toDouble()
    }

    private fun createDailyMp4(dayDir: File, overwrite: Boolean): Boolean {
        if (!dayDir.isDirectory) {
            return false
        }
        val images = dayDir.listFiles { file ->
            file.isFile && file.extension.lowercase(Locale.US) == "jpg" && file.length() > 0
        }?.sortedBy { it.name }.orEmpty()
        if (images.size < MIN_IMAGES) {
            return false
        }
        Log.i(TAG, "Daily timelapse ${dayDir.name}: ${images.size} source images")
        val outputFile = File(dayDir, "${dayDir.name}.mp4")
        if (outputFile.exists() && !overwrite) {
            return true
        }
        val tmpFile = File(dayDir, ".${dayDir.name}.tmp.mp4")
        if (tmpFile.exists()) {
            tmpFile.delete()
        }
        encodeMp4(images, tmpFile)
        if (outputFile.exists()) {
            outputFile.delete()
        }
        tmpFile.renameTo(outputFile)
        storage.writeDailyTimelapseMetadata(dayDir, outputFile.name, images.size)
        Log.i(TAG, "Daily timelapse ${dayDir.name}: wrote ${outputFile.name}")
        return true
    }

    private fun encodeMp4(images: List<File>, outputFile: File) {
        val frameDurationUs = 1_000_000L / FRAME_RATE
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, DAILY_VIDEO_WIDTH, DAILY_VIDEO_HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, DAILY_BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var muxerStarted = false
        var videoTrack = -1
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            images.forEachIndexed { index, imageFile ->
                val inputIndex = awaitInputBuffer(codec, "MP4 frame $index")
                val inputBuffer = codec.getInputBuffer(inputIndex)
                val frame = decodeFrameNv12(imageFile, DAILY_VIDEO_WIDTH, DAILY_VIDEO_HEIGHT)
                inputBuffer?.clear()
                inputBuffer?.put(frame)
                codec.queueInputBuffer(inputIndex, 0, frame.size, index * frameDurationUs, 0)
                drainMp4Encoder(codec, info, muxer, { trackFormat ->
                    videoTrack = muxer.addTrack(trackFormat)
                    muxer.start()
                    muxerStarted = true
                }, { videoTrack }, { muxerStarted })
            }
            val inputIndex = awaitInputBuffer(codec, "MP4 end of stream")
            codec.queueInputBuffer(inputIndex, 0, 0, images.size * frameDurationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drainMp4Encoder(codec, info, muxer, { trackFormat ->
                videoTrack = muxer.addTrack(trackFormat)
                muxer.start()
                muxerStarted = true
            }, { videoTrack }, { muxerStarted }, endOfStream = true)
        } finally {
            codec.stop()
            codec.release()
            if (muxerStarted) {
                muxer.stop()
            }
            muxer.release()
        }
    }

    private fun drainMp4Encoder(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        onFormatChanged: (MediaFormat) -> Unit,
        videoTrack: () -> Int,
        muxerStarted: () -> Boolean,
        endOfStream: Boolean = false,
    ) {
        val deadlineNs = System.nanoTime() + CODEC_STALL_TIMEOUT_NS
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) {
                        return
                    }
                    if (System.nanoTime() > deadlineNs) {
                        throw IllegalStateException("MP4 encoder timed out waiting for end of stream")
                    }
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormatChanged(codec.outputFormat)
                else -> if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && info.size > 0 && muxerStarted()) {
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            muxer.writeSampleData(videoTrack(), outputBuffer, info)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        return
                    }
                }
            }
        }
    }

    private fun awaitInputBuffer(codec: MediaCodec, label: String): Int {
        val deadlineNs = System.nanoTime() + CODEC_STALL_TIMEOUT_NS
        while (true) {
            if (Thread.currentThread().isInterrupted) {
                throw IllegalStateException("$label interrupted while waiting for encoder input")
            }
            val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (index >= 0) {
                return index
            }
            if (System.nanoTime() > deadlineNs) {
                throw IllegalStateException("$label timed out waiting for encoder input")
            }
        }
    }

    private fun drainEncoder(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        writer: H264TsWriter,
        endOfStream: Boolean = false,
        getCodecConfig: () -> ByteArray,
        onCodecConfig: (ByteArray) -> Unit,
    ) {
        val deadlineNs = System.nanoTime() + CODEC_STALL_TIMEOUT_NS
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) {
                        return
                    }
                    if (System.nanoTime() > deadlineNs) {
                        throw IllegalStateException("HLS encoder timed out waiting for end of stream")
                    }
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    onCodecConfig(codec.outputFormat.codecConfigAnnexB())
                }
                else -> if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && info.size > 0) {
                        val data = outputBuffer.toByteArray(info.offset, info.size)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            onCodecConfig(toAnnexB(data))
                        } else {
                            val keyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            val annexB = toAnnexB(data)
                            val accessUnit = if (keyFrame) {
                                getCodecConfig() + annexB
                            } else {
                                annexB
                            }
                            writer.writeAccessUnit(info.presentationTimeUs, accessUnit, keyFrame)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        return
                    }
                }
            }
        }
    }

    private fun decodeFrameNv12(imageFile: File, width: Int, height: Int): ByteArray {
        val bitmap = decodeFrameBitmap(imageFile, width, height)
        val frame = ByteArray(width * height * 3 / 2)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var yOffset = 0
        var uvOffset = width * height
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val pixel = pixels[rowOffset + x]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val yValue = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                frame[yOffset++] = yValue.coerceIn(0, 255).toByte()
                if (x % 2 == 0 && y % 2 == 0) {
                    val uValue = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val vValue = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    frame[uvOffset++] = uValue.coerceIn(0, 255).toByte()
                    frame[uvOffset++] = vValue.coerceIn(0, 255).toByte()
                }
            }
        }
        bitmap.recycle()
        return frame
    }

    private fun decodeFrameBitmap(imageFile: File, outputWidth: Int, outputHeight: Int): Bitmap {
        val source = BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val scale = minOf(outputWidth.toFloat() / source.width.toFloat(), outputHeight.toFloat() / source.height.toFloat())
        val width = (source.width * scale).toInt()
        val height = (source.height * scale).toInt()
        val left = (outputWidth - width) / 2
        val top = (outputHeight - height) / 2
        canvas.drawBitmap(source, null, Rect(left, top, left + width, top + height), Paint(Paint.FILTER_BITMAP_FLAG))
        source.recycle()
        return output
    }

    private fun writePlaylist(dayDir: File, baseName: String, segments: List<HlsSegment>) {
        val targetDuration = maxOf(1, ceil(segments.maxOfOrNull { it.durationSeconds } ?: 1.0).toInt())
        File(dayDir, "$baseName.m3u8").writeText(
            buildString {
                appendLine("#EXTM3U")
                appendLine("#EXT-X-VERSION:3")
                appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
                appendLine("#EXT-X-TARGETDURATION:$targetDuration")
                appendLine("#EXT-X-MEDIA-SEQUENCE:0")
                segments.forEach { segment ->
                    appendLine("#EXTINF:${String.format(Locale.US, "%.6f", segment.durationSeconds)},")
                    appendLine(segment.path)
                }
                appendLine("#EXT-X-ENDLIST")
            }
        )
    }

    private fun MediaFormat.codecConfigAnnexB(): ByteArray {
        val sps = getByteBuffer("csd-0")?.toByteArray() ?: ByteArray(0)
        val pps = getByteBuffer("csd-1")?.toByteArray() ?: ByteArray(0)
        return toAnnexB(sps) + toAnnexB(pps)
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val duplicate = duplicate()
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return bytes
    }

    private fun ByteBuffer.toByteArray(offset: Int, size: Int): ByteArray {
        val duplicate = duplicate()
        duplicate.position(offset)
        duplicate.limit(offset + size)
        val bytes = ByteArray(size)
        duplicate.get(bytes)
        return bytes
    }

    private fun toAnnexB(data: ByteArray): ByteArray {
        if (data.size < 4 || data.hasStartCode()) {
            return data
        }
        val output = mutableListOf<Byte>()
        var offset = 0
        while (offset + 4 <= data.size) {
            val length = ((data[offset].toInt() and 0xff) shl 24) or
                ((data[offset + 1].toInt() and 0xff) shl 16) or
                ((data[offset + 2].toInt() and 0xff) shl 8) or
                (data[offset + 3].toInt() and 0xff)
            offset += 4
            if (length <= 0 || offset + length > data.size) {
                return data
            }
            output += listOf(0x00, 0x00, 0x00, 0x01).map { it.toByte() }
            output += data.sliceArray(offset until offset + length).toList()
            offset += length
        }
        return output.toByteArray()
    }

    private fun ByteArray.hasStartCode(): Boolean {
        return size >= 4 && this[0] == 0.toByte() && this[1] == 0.toByte() &&
            ((this[2] == 1.toByte()) || (this[2] == 0.toByte() && this[3] == 1.toByte()))
    }

    companion object {
        private const val TAG = "FenetreTimelapse"
        private const val HLS_VIDEO_WIDTH = 1280
        private const val HLS_VIDEO_HEIGHT = 720
        private const val DAILY_VIDEO_WIDTH = 1920
        private const val DAILY_VIDEO_HEIGHT = 1080
        private const val FRAME_RATE = 30
        private const val HLS_BIT_RATE = 3_000_000
        private const val DAILY_BIT_RATE = 12_000_000
        private const val I_FRAME_INTERVAL_SECONDS = 2
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val CODEC_STALL_TIMEOUT_NS = 60_000_000_000L
        private const val MIN_IMAGES = 2
        private const val HLS_SEGMENT_FRAME_COUNT = FRAME_RATE
        private const val BOOTSTRAP_IMAGE_LIMIT = 120
    }
}

data class HlsSegment(
    val path: String,
    val durationSeconds: Double,
)

data class HlsManifest(
    val lastImage: String?,
    val segments: List<HlsSegment>,
) {
    fun nextSegmentIndex(): Int = segments.size

    fun write(file: File) {
        file.writeText(
            buildString {
                appendLine("{")
                appendLine("  \"last_image\": ${lastImage?.let { "\"$it\"" } ?: "null"},")
                appendLine("  \"segments\": [")
                segments.forEachIndexed { index, segment ->
                    append("    {\"path\": \"${segment.path}\", \"duration\": ${segment.durationSeconds}}")
                    if (index < segments.lastIndex) {
                        append(",")
                    }
                    appendLine()
                }
                appendLine("  ]")
                appendLine("}")
            }
        )
    }

    companion object {
        fun read(file: File): HlsManifest {
            if (!file.exists()) {
                return HlsManifest(null, emptyList())
            }
            val text = file.readText()
            val lastImage = Regex(""""last_image"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
            val segments = Regex("""\{"path"\s*:\s*"([^"]+)",\s*"duration"\s*:\s*([0-9.]+)\}""")
                .findAll(text)
                .map { HlsSegment(it.groupValues[1], it.groupValues[2].toDouble()) }
                .toList()
            return HlsManifest(lastImage, segments)
        }
    }
}
