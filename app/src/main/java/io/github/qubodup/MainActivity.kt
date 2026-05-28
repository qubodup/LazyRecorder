package io.github.qubodup

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import com.google.android.gms.location.LocationServices
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class MainActivity : ComponentActivity() {

    private val permissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestPermissionsIfNeeded()

        setContent {
            MaterialTheme {
                RecorderScreen(
                    hasPermissions = { hasAllPermissions() },
                    requestPermissions = { requestPermissionsIfNeeded() }
                )
            }
        }
    }

    private fun hasAllPermissions(): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissionsIfNeeded() {
        if (!hasAllPermissions()) {
            permissionLauncher.launch(permissions)
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun RecorderScreen(
    hasPermissions: () -> Boolean,
    requestPermissions: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isRecording by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<WavRecorder?>(null) }

    val uiScope = remember { MainScope() }

    val leftChannelIsTop = false

    val fusedLocationClient = remember {
        LocationServices.getFusedLocationProviderClient(context)
    }

    val topWaveform = remember {
        mutableStateListOf<Float>().apply {
            repeat(16) { add(0f) }
        }
    }

    val bottomWaveform = remember {
        mutableStateListOf<Float>().apply {
            repeat(16) { add(0f) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF333333)),
        contentAlignment = Alignment.Center
    ) {

        if (isRecording) {

            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {

                val widthStep = size.width / 16f
                val centerY = size.height / 2f

                topWaveform.forEachIndexed { index, topAmp ->

                    val bottomAmp = bottomWaveform[index]

                    val x = widthStep * index + widthStep / 2f

                    val topHeight = topAmp * size.height * 0.30f
                    val bottomHeight = bottomAmp * size.height * 0.30f

                    val topAlpha =
                        if (topAmp >= bottomAmp) 1.0f else 0.5f

                    val bottomAlpha =
                        if (bottomAmp >= topAmp) 1.0f else 0.5f

                    drawLine(
                        color = Color(0xFF666666).copy(alpha = topAlpha),
                        start = Offset(x, centerY),
                        end = Offset(x, centerY - topHeight),
                        strokeWidth = widthStep * 0.6f
                    )

                    drawLine(
                        color = Color(0xFF666666).copy(alpha = bottomAlpha),
                        start = Offset(x, centerY),
                        end = Offset(x, centerY + bottomHeight),
                        strokeWidth = widthStep * 0.6f
                    )
                }
            }
        }

        Button(
            enabled = !isBusy,
            onClick = {

                if (!hasPermissions()) {
                    requestPermissions()
                    return@Button
                }

                if (!isRecording) {

                    val recordingsDir = File(
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_RECORDINGS
                        ),
                        "LazyRecorder"
                    )

                    recordingsDir.mkdirs()

                    val tempFile = File.createTempFile(
                        "rec_",
                        ".wav",
                        recordingsDir
                    )

                    try {

                        repeat(topWaveform.size) { i ->
                            topWaveform[i] = 0f
                            bottomWaveform[i] = 0f
                        }

                        val wavRecorder = WavRecorder(
                            tempFile,
                            onAmplitude = { leftAmp, rightAmp ->

                                uiScope.launch {

                                    val topAmp =
                                        if (leftChannelIsTop)
                                            leftAmp
                                        else
                                            rightAmp

                                    val bottomAmp =
                                        if (leftChannelIsTop)
                                            rightAmp
                                        else
                                            leftAmp

                                    topWaveform.removeAt(0)
                                    topWaveform.add(topAmp)

                                    bottomWaveform.removeAt(0)
                                    bottomWaveform.add(bottomAmp)
                                }
                            }
                        )

                        wavRecorder.start()

                        recorder = wavRecorder
                        isRecording = true

                    } catch (e: Exception) {

                        Toast.makeText(
                            context,
                            "Recorder failed: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                } else {

                    isRecording = false
                    isBusy = true

                    scope.launch {

                        val stopLocation = try {
                            fusedLocationClient.lastLocation.await()
                        } catch (e: Exception) {
                            null
                        }

                        val lat = stopLocation?.latitude ?: 0.0
                        val lon = stopLocation?.longitude ?: 0.0

                        val finalFile = buildFinalFile(lat, lon)

                        val success = withContext(Dispatchers.IO) {
                            try {
                                recorder?.stop(finalFile)
                                true
                            } catch (e: Exception) {
                                false
                            }
                        }

                        if (success) {

                            Toast.makeText(
                                context,
                                "Internal storage/Recordings/LazyRecorder/${finalFile.name}",
                                Toast.LENGTH_LONG
                            ).show()
                            isBusy = false

                        } else {

                            Toast.makeText(
                                context,
                                "Warning: failed to save recording",
                                Toast.LENGTH_LONG
                            ).show()
                            isBusy = false
                        }
                    }
                }
            },

            modifier = Modifier.size(220.dp),

            shape = CircleShape,

            colors = ButtonDefaults.buttonColors(
                containerColor =
                if (isRecording)
                    Color(0xDD00AA00)
                else
                    Color(0xDDFF0000)
            )
        ) {

            Canvas(
                modifier = Modifier.size(90.dp)
            ) {

                if (isRecording) {

                    drawRect(
                        color = Color.White,
                        topLeft = Offset(
                            size.width * 0.25f,
                            size.height * 0.25f
                        ),
                        size = androidx.compose.ui.geometry.Size(
                            size.width * 0.5f,
                            size.height * 0.5f
                        )
                    )

                } else {

                    drawCircle(
                        color = Color.White,
                        radius = size.minDimension * 0.3f
                    )
                }
            }
        }
    }
}

private fun buildFinalFile(lat: Double, lon: Double): File {

    val recordingsDir = File(
        Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_RECORDINGS
        ),
        "LazyRecorder"
    )

    recordingsDir.mkdirs()

    val timestamp = SimpleDateFormat(
        "yyyyMMdd_HHmmss",
        Locale.US
    ).format(Date())

    val fileName = String.format(
        Locale.US,
        "Rec_%s_GPS_%.4f_%.4f.wav",
        timestamp,
        lat,
        lon
    )

    return File(recordingsDir, fileName)
}

class WavRecorder(
    private val tempFile: File,
    private val onAmplitude: (Float, Float) -> Unit
) {

    private val sampleRate = 48000
    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        channelConfig,
        audioFormat
    )

    @SuppressLint("MissingPermission")
    private val audioRecord = AudioRecord.Builder()
        .setAudioSource(MediaRecorder.AudioSource.MIC)
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(audioFormat)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build()
        )
        .setBufferSizeInBytes(bufferSize)
        .build()

    @Volatile
    private var isRecording = false

    private lateinit var recordingThread: Thread

    private var headerUpdateCounter = 0

    private val maxWavDataBytes =
        Int.MAX_VALUE.toLong() - 44

    fun start() {

        isRecording = true

        audioRecord.startRecording()

        recordingThread = Thread {
            writeAudioData()
        }

        recordingThread.start()
    }

    private fun writeAudioData() {

        val buffer = ByteArray(bufferSize)

        FileOutputStream(tempFile).use { output ->

            writeWavHeader(output)

            while (isRecording) {
                val read = audioRecord.read(buffer, 0, buffer.size)

                if (read > 0) {

                    output.write(buffer, 0, read)

                    headerUpdateCounter++

                    if (headerUpdateCounter >= 10) {

                        output.flush()
                        finalizeWavHeader(tempFile)

                        headerUpdateCounter = 0
                    }

                    if (tempFile.length() - 44 >= maxWavDataBytes) {

                        isRecording = false
                        break
                    }

                    var leftMax = 0
                    var rightMax = 0

                    for (i in 0 until read - 3 step 4) {

                        val left =
                            ((buffer[i + 1].toInt() shl 8)
                                    or (buffer[i].toInt() and 0xff))

                        val right =
                            ((buffer[i + 3].toInt() shl 8)
                                    or (buffer[i + 2].toInt() and 0xff))

                        leftMax = maxOf(leftMax, abs(left))
                        rightMax = maxOf(rightMax, abs(right))
                    }

                    val leftNormalized = leftMax / 32767f
                    val rightNormalized = rightMax / 32767f

                    val leftLog =
                        kotlin.math.log10(1f + leftNormalized * 9f)

                    val rightLog =
                        kotlin.math.log10(1f + rightNormalized * 9f)

                    onAmplitude(
                        leftLog,
                        rightLog
                    )
                }
            }
        }
    }

    fun stop(finalFile: File) {

        isRecording = false

        recordingThread.join()

        audioRecord.stop()
        audioRecord.release()

        finalizeWavHeader(tempFile)

        tempFile.copyTo(finalFile, overwrite = true)

        tempFile.delete()
    }

    private fun writeWavHeader(output: FileOutputStream) {

        val header = ByteArray(44)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()

        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()

        header[16] = 16
        header[20] = 1
        header[22] = 2

        writeInt(header, 24, sampleRate)
        writeInt(header, 28, sampleRate * 4)

        header[32] = 4
        header[34] = 16

        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()

        output.write(header, 0, 44)
    }

    private fun finalizeWavHeader(file: File) {

        val totalAudioLen = file.length() - 44
        val totalDataLen = totalAudioLen + 36

        val header = ByteArray(44)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()

        writeInt(header, 4, totalDataLen.toInt())

        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()

        header[16] = 16
        header[20] = 1
        header[22] = 2

        writeInt(header, 24, sampleRate)
        writeInt(header, 28, sampleRate * 4)

        header[32] = 4
        header[34] = 16

        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()

        writeInt(header, 40, totalAudioLen.toInt())

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header)
        }
    }

    private fun writeInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xff).toByte()
        data[offset + 1] = (value shr 8 and 0xff).toByte()
        data[offset + 2] = (value shr 16 and 0xff).toByte()
        data[offset + 3] = (value shr 24 and 0xff).toByte()
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xff).toByte(),
            (value shr 8 and 0xff).toByte(),
            (value shr 16 and 0xff).toByte(),
            (value shr 24 and 0xff).toByte()
        )
    }
}