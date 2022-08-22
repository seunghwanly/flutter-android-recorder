package com.example.flutter_android_recorder

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.NonNull

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

private class WaveRecorder() {
        companion object {
            const val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
            const val SAMPLE_RATE = 16_000
            const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT   // 2
            const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO  // 16 (1:monophony)
        }

        val BUFFER_SIZE: Int =
            AudioRecord.getMinBufferSize(
                WaveRecorder.SAMPLE_RATE,
                WaveRecorder.CHANNEL_MASK,
                WaveRecorder.AUDIO_FORMAT,
            ) * 2

        var audioRecord: AudioRecord? = null
        var audioOutStream: FileOutputStream? = null
        var isRecording: Boolean = false;

        var mediaRecorder: MediaRecorder? = null

        fun onRecord(file: File) {
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_WB) // 16kHZ
                setAudioSamplingRate(16000)
            }

            try {
                mediaRecorder?.prepare()
            } catch (e: IOException) {
                Log.e("record", "prepare() failed")
            }

            mediaRecorder?.start()
        }

        fun onStop() {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
        }

        fun stopRecord() {
            if (audioRecord != null) {
                audioRecord!!.stop()
                audioRecord = null
                Log.d("stopping", "stop recorder")
            }
        }

        @SuppressLint("MissingPermission")
        fun startRecord(file: File) {
            var startTime: Long = 0
            var endTime: Long = 0

            var readData: Int = 0
            var totalData: Long = 0

            try {
                /// open record stream and file output stream
                audioRecord = AudioRecord(
                    WaveRecorder.AUDIO_SOURCE,
                    WaveRecorder.SAMPLE_RATE,
                    WaveRecorder.CHANNEL_MASK,
                    WaveRecorder.AUDIO_FORMAT,
                    BUFFER_SIZE,
                )

                Log.d("startRecord state ----> ", audioRecord?.state.toString())
                this.audioOutStream = FileOutputStream(file)

                assert(audioRecord != null && audioOutStream != null)

                /// wrap up with header
                writeWaveHeader(
                    audioOutStream!!,
                    1,                      // WaveRecorder.CHANNEL_MASK
                    WaveRecorder.SAMPLE_RATE,
                    16,                     // WaveRecorder.ENCODING
                )

                /// avoid loop allocations
                val buffer: ByteBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE)


                /// record start
                audioRecord!!.startRecording()
                Log.d("after calling 'startRecording' startRecord state ----> ", audioRecord?.state.toString())

//                while (audioRecord!!.state == AudioRecord.RECORDSTATE_RECORDING) {
                while (audioRecord != null) {
                    try {
                        readData = audioRecord!!.read(buffer, BUFFER_SIZE)
                        Log.d("readData size", readData.toString())
                        /// TODO restraint the size of the written data like up to 4GB
                        /// https://gist.github.com/kmark/d8b1b01fb0d2febf5770

                        audioOutStream!!.write(buffer.array(), 0, BUFFER_SIZE)
                        buffer.clear()

                        totalData += readData

                        Log.d("recording", "writing to file")
                    } catch (e: IOException) {
                        Log.e("write to file", e.toString())
                        e.printStackTrace()
                    }
                }
            } catch (exception: IOException) {
                throw exception
            } finally {
                /// check audio record
                if (audioRecord != null) {
                    if (audioRecord!!.state == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord!!.stop()
                    } else if (audioRecord!!.state == AudioRecord.STATE_INITIALIZED) {
                        audioRecord!!.release()
                    }
                }
                /// check output stream file
                if (audioOutStream != null) {
                    audioOutStream!!.flush()
                    audioOutStream!!.close()
                }

                /// run after the file output stream is closed
                updateWaveHeader(file, totalData)
                return
                Log.d("recording", "header update")
            }
        }


        /**
         * Writes the proper 44-byte RIFF/WAVE header to/for the given stream
         * Two size fields are left empty/null since we do not yet know the final stream size
         *
         * @param out        The stream to write the header to
         * @param channels   The number of channels
         * @param sampleRate The sample rate in hertz
         * @param bitDepth   The bit depth
         * @throws IOException
         */
        private fun writeWaveHeader(
            out: OutputStream,
            channels: Short,
            sampleRate: Int,
            bitDepth: Short,
        ) {
            val headerByte: ByteArray =
                ByteBuffer.allocate(14)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .putShort(channels)
                    .putInt(sampleRate)
                    .putInt(sampleRate * channels * (bitDepth / 8))
                    .putShort((channels * (bitDepth / 8)).toShort())
                    .putShort(bitDepth)
                    .array()

            fun byteArrayOfElements(vararg elements: Any): ByteArray = ByteArray(elements.size) {

                when (elements[it]) {
                    is Char -> (elements[it] as Char).code.toByte()
                    is Int -> (elements[it] as Int).toByte()
                    is Byte -> elements[it] as Byte
                    else -> throw Exception("Neither Char or Int")
                }
            }

            val byteArr = byteArrayOfElements(
                /// RIFF header
                'R', 'I', 'F', 'F',             // Chunk ID
                0, 0, 0, 0,                     // Chunk Size, must be updated later
                'W', 'A', 'V', 'E',             // Format
                /// fmt subchunk
                'f', 'm', 't', ' ',             // Sub chunk ID
                16, 0, 0, 0,                    // L
                1, 0,                       // WAVE_FORMAT_PCM(0x0001) : H
                headerByte[0], headerByte[1],   // Num of channels
                headerByte[2], headerByte[3], headerByte[4], headerByte[5], // Sample rate
                headerByte[6], headerByte[7], headerByte[8], headerByte[9], // Byte rate
                headerByte[10], headerByte[11],                             // Block align
                headerByte[12], headerByte[13],                             // Bits per sample
                /// data sub chunk
                'd', 'a', 't', 'a',    // Sub chunk 2 ID
                0, 0, 0, 0,            // Sub chunk 2 size, must be updated later
            )

            /// write dowm header
            out.write(byteArr)
        }

        private fun updateWaveHeader(wavFile: File, totalDataLength: Long) {

            val nFrames = (totalDataLength / 2).toInt()
            val dataLength = nFrames * 2

            val sizes: ByteArray = ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(36 + dataLength)
                .putInt(dataLength)
                .array()
            var accessFile: RandomAccessFile? = null

            try {
                accessFile = RandomAccessFile(wavFile, "rw")
                // update chunk size
                accessFile.seek(4)
                accessFile.write(sizes, 0, 4)

                // update sub chunk size
                accessFile.seek(44)
                accessFile.write(sizes, 4, 4)
                Log.d("byte array", sizes.toString())
                Log.d("wav save succeed?", "succeed")
                Log.d("savd in", wavFile.absolutePath.toString())

                Log.d("byte header", sizes.joinToString { eachByte -> "%02x".format(eachByte) })
                Log.d("after saving", wavFile.readBytes().toString())
            } catch (exception: IOException) {
                throw exception
            } finally {
                if (accessFile != null) {
                    accessFile.close()
                }
            }
        }
    }