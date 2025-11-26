package com.nihal.malayalam

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object AudioHelper {

    private const val TAG = "AudioHelper"
    private const val TARGET_SAMPLE_RATE = 16000
    private const val TARGET_CHANNELS = 1

    fun copyUriToCache(context: Context, uri: Uri): File? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val tempFile = File(context.cacheDir, "input_audio_${UUID.randomUUID()}")
            tempFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy file", e)
            null
        }
    }

    /**
     * Decodes any audio file to PCM using MediaCodec, resamples to 16kHz Mono,
     * and saves as WAV.
     */
    fun convertTo16BitWav(inputFile: File, context: Context): File? {
        val outputFile = File(context.cacheDir, "converted_${UUID.randomUUID()}.wav")
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(inputFile.absolutePath)

            // 1. Find Audio Track
            var trackIndex = -1
            var mimeType = ""
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    trackIndex = i
                    mimeType = mime
                    extractor.selectTrack(trackIndex)
                    break
                }
            }

            if (trackIndex < 0) {
                Log.e(TAG, "No audio track found")
                return null
            }

            // 2. Configure Codec
            val format = extractor.getTrackFormat(trackIndex)
            codec = MediaCodec.createDecoderByType(mimeType)
            codec.configure(format, null, null, 0)
            codec.start()

            // 3. Decode & Resample Loop
            val info = MediaCodec.BufferInfo()
            val pcmData = mutableListOf<Short>()
            var inputDone = false
            var outputDone = false

            // Get source sample rate/channels for resampling logic
            val srcRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 48000
            val srcChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(5000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex)
                        val sampleSize = extractor.readSampleData(buffer!!, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, 5000)
                if (outputIndex >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }

                    val outBuffer = codec.getOutputBuffer(outputIndex)
                    if (outBuffer != null && info.size > 0) {
                        outBuffer.position(info.offset)
                        outBuffer.limit(info.offset + info.size)

                        val chunk = ShortArray(info.size / 2) // 16-bit samples
                        outBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(chunk)

                        // Process this chunk: Downmix and Resample
                        // (Simple implementation: average channels, drop samples for resampling)
                        // Note: A production app might use Oboe/Resampler for higher quality,
                        // but this is sufficient for ASR.
                        processRawChunk(chunk, srcChannels, srcRate, pcmData)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }

            // 4. Write WAV File
            writeWavFile(outputFile, pcmData.toShortArray())
            Log.d(TAG, "Conversion complete: ${outputFile.length()} bytes")
            return outputFile

        } catch (e: Exception) {
            Log.e(TAG, "Conversion Error", e)
            return null
        } finally {
            try {
                codec?.stop()
                codec?.release()
                extractor.release()
            } catch (e: Exception) {}
        }
    }

    private fun processRawChunk(
        chunk: ShortArray,
        channels: Int,
        srcRate: Int,
        outputList: MutableList<Short>
    ) {
        // 1. Downmix to Mono
        val monoData = if (channels > 1) {
            val mono = ShortArray(chunk.size / channels)
            for (i in mono.indices) {
                var sum = 0
                for (c in 0 until channels) {
                    sum += chunk[i * channels + c]
                }
                mono[i] = (sum / channels).toShort()
            }
            mono
        } else {
            chunk
        }

        // 2. Resample to 16000Hz (Simple decimation/interpolation)
        if (srcRate == TARGET_SAMPLE_RATE) {
            for (s in monoData) outputList.add(s)
        } else {
            val ratio = srcRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
            var index = 0.0
            while (index < monoData.size) {
                outputList.add(monoData[index.toInt()])
                index += ratio
            }
        }
    }

    private fun writeWavFile(file: File, pcmData: ShortArray) {
        val byteRate = 16000 * 16 * 1 / 8
        val totalDataLen = pcmData.size * 2
        val totalLength = totalDataLen + 36

        val header = ByteArray(44)
        val longSampleRate = 16000L
        val channels = 1
        val byteRateHeader = 16000 * 1 * 16 / 8

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalLength and 0xff).toByte()
        header[5] = ((totalLength shr 8) and 0xff).toByte()
        header[6] = ((totalLength shr 16) and 0xff).toByte()
        header[7] = ((totalLength shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = ((longSampleRate shr 8) and 0xff).toByte()
        header[26] = ((longSampleRate shr 16) and 0xff).toByte()
        header[27] = ((longSampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRateHeader and 0xff).toByte()
        header[29] = ((byteRateHeader shr 8) and 0xff).toByte()
        header[30] = ((byteRateHeader shr 16) and 0xff).toByte()
        header[31] = ((byteRateHeader shr 24) and 0xff).toByte()
        header[32] = (1 * 16 / 8).toByte()
        header[33] = 0
        header[34] = 16
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalDataLen and 0xff).toByte()
        header[41] = ((totalDataLen shr 8) and 0xff).toByte()
        header[42] = ((totalDataLen shr 16) and 0xff).toByte()
        header[43] = ((totalDataLen shr 24) and 0xff).toByte()

        BufferedOutputStream(FileOutputStream(file)).use { out ->
            out.write(header)
            val byteBuffer = ByteBuffer.allocate(pcmData.size * 2)
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcmData) byteBuffer.putShort(s)
            out.write(byteBuffer.array())
        }
    }
}