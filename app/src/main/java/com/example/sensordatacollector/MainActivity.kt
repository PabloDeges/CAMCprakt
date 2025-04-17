package com.example.sensordatacollector // Ersetze mit deinem Package-Namen

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.sensordatacollector.databinding.ActivityMainBinding // View Binding aktivieren!
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding // View Binding
    private lateinit var sensorManager: SensorManager
    private var gyroscope: Sensor? = null
    private var accelerometer: Sensor? = null

    private var isCollecting = false
    private var fileOutputStream: FileOutputStream? = null
    private val dataBuffer = StringBuilder()
    private var lastWriteTime = 0L
    private val WRITE_BUFFER_INTERVAL_MS = 1000L // Daten alle 1 Sekunde schreiben

    // Für das Schreiben im Hintergrund-Thread
    private var sensorHandlerThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    // Für Live-Daten-Throttling
    private var lastGyroUpdateTime = 0L
    private var lastAccelUpdateTime = 0L
    private val LIVE_UPDATE_INTERVAL_MS = 200 // UI nur alle 200ms aktualisieren

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        setupSpinner()
        setupToggleButton()

        // Überprüfen, ob Sensoren vorhanden sind
        binding.checkboxGyroscope.isEnabled = (gyroscope != null)
        binding.checkboxAccelerometer.isEnabled = (accelerometer != null)
        if (gyroscope == null) binding.checkboxGyroscope.text = "Gyroscope (Not available)"
        if (accelerometer == null) binding.checkboxAccelerometer.text = "Accelerometer (Not available)"
    }

    private fun setupSpinner() {
        val frequencies = mapOf(
            "Fastest" to SensorManager.SENSOR_DELAY_FASTEST, // 0 ms
            "Game" to SensorManager.SENSOR_DELAY_GAME,       // 20 ms
            "UI" to SensorManager.SENSOR_DELAY_UI,         // 60 ms
            "Normal" to SensorManager.SENSOR_DELAY_NORMAL    // 200 ms
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, frequencies.keys.toList())
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerFrequency.adapter = adapter
        binding.spinnerFrequency.setSelection(3) // Default to Normal
    }

    private fun setupToggleButton() {
        binding.toggleButtonCollect.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startCollecting()
            } else {
                stopCollecting()
            }
            // UI-Konfiguration während der Sammlung sperren/entsperren
            binding.checkboxGyroscope.isEnabled = !isChecked && (gyroscope != null)
            binding.checkboxAccelerometer.isEnabled = !isChecked && (accelerometer != null)
            binding.spinnerFrequency.isEnabled = !isChecked
        }
    }

    private fun getSelectedFrequency(): Int {
        return when (binding.spinnerFrequency.selectedItem.toString()) {
            "Fastest" -> SensorManager.SENSOR_DELAY_FASTEST
            "Game" -> SensorManager.SENSOR_DELAY_GAME
            "UI" -> SensorManager.SENSOR_DELAY_UI
            else -> SensorManager.SENSOR_DELAY_NORMAL
        }
    }

    private fun startCollecting() {
        if (isCollecting) return // Schon gestartet

        // Erstelle Hintergrund-Thread für Sensor-Events und Datei-IO
        sensorHandlerThread = HandlerThread("SensorThread").apply { start() }
        sensorHandler = Handler(sensorHandlerThread!!.looper)

        val selectedFrequency = getSelectedFrequency()
        isCollecting = true
        binding.tvStatus.text = "Status: Collecting..."
        dataBuffer.clear()
        lastWriteTime = System.currentTimeMillis()

        // Datei für die Speicherung vorbereiten
        if (!prepareFileOutputStream()) {
            stopCollecting() // Stoppen, wenn Datei nicht erstellt werden kann
            return
        }
        writeHeaderToFile() // CSV Header schreiben

        // Listener registrieren
        sensorHandler?.post { // Registriere Listener auf dem Hintergrund-Thread
            if (binding.checkboxGyroscope.isChecked && gyroscope != null) {
                sensorManager.registerListener(this, gyroscope, selectedFrequency, sensorHandler)
            }
            if (binding.checkboxAccelerometer.isChecked && accelerometer != null) {
                sensorManager.registerListener(this, accelerometer, selectedFrequency, sensorHandler)
            }
        }
        Log.i("SensorCollector", "Started collecting.")
    }

    private fun stopCollecting() {
        if (!isCollecting) return // Schon gestoppt

        // Listener deregistrieren
        sensorManager.unregisterListener(this) // Deregistriert alle Listener für dieses Objekt

        // Hintergrund-Thread sicher beenden
        sensorHandlerThread?.quitSafely()
        try {
            sensorHandlerThread?.join() // Warte auf Beendigung
            sensorHandlerThread = null
            sensorHandler = null
        } catch (e: InterruptedException) {
            Log.e("SensorCollector", "Error joining sensor thread", e)
        }

        isCollecting = false
        binding.tvStatus.text = "Status: Stopped"

        // Verbleibende Daten im Buffer schreiben und Datei schließen
        flushBufferToFile()
        closeFileOutputStream()
        Log.i("SensorCollector", "Stopped collecting.")

        // UI wieder aktivieren
        binding.checkboxGyroscope.isEnabled = (gyroscope != null)
        binding.checkboxAccelerometer.isEnabled = (accelerometer != null)
        binding.spinnerFrequency.isEnabled = true
    }

    private fun prepareFileOutputStream(): Boolean {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "sensor_data_$timestamp.csv"
            // Speicherort: App-spezifisches Verzeichnis im internen Speicher
            // -> /data/data/com.example.sensordatacollector/files/
            val file = File(filesDir, fileName)
            fileOutputStream = FileOutputStream(file)
            Log.i("SensorCollector", "Saving data to: ${file.absolutePath}")
            Toast.makeText(this, "Saving to: ${file.name}", Toast.LENGTH_SHORT).show()
            return true
        } catch (e: IOException) {
            Log.e("SensorCollector", "Error creating file", e)
            Toast.makeText(this, "Error creating file: ${e.message}", Toast.LENGTH_LONG).show()
            fileOutputStream = null
            return false
        }
    }

    private fun writeHeaderToFile() {
        val header = "Timestamp_ms,SystemTime_ns,Sensor,X,Y,Z\n"
        try {
            fileOutputStream?.write(header.toByteArray())
        } catch (e: IOException) {
            Log.e("SensorCollector", "Error writing header to file", e)
        }
    }


    private fun writeToFile(data: String) {
        if (fileOutputStream == null) return // Nicht schreiben, wenn Datei nicht offen ist

        try {
            fileOutputStream?.write(data.toByteArray())
        } catch (e: IOException) {
            Log.e("SensorCollector", "Error writing to file", e)
            // Optional: Sammlung stoppen oder Fehler anzeigen
            // stopCollecting()
            // runOnUiThread { Toast.makeText(this, "Error writing data!", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun flushBufferToFile() {
        if (dataBuffer.isNotEmpty()) {
            writeToFile(dataBuffer.toString())
            dataBuffer.clear() // Buffer leeren nach dem Schreiben
        }
    }

    private fun closeFileOutputStream() {
        try {
            fileOutputStream?.flush()
            fileOutputStream?.close()
            fileOutputStream = null
        } catch (e: IOException) {
            Log.e("SensorCollector", "Error closing file", e)
        }
    }

    // --- SensorEventListener Methoden ---

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isCollecting) return

        val currentTimeMs = System.currentTimeMillis()
        // Android SensorEvent Timestamps sind seit Boot in Nanosekunden
        // val eventTimeNanos = event.timestamp
        // Besser: Verwende SystemClock.elapsedRealtimeNanos() oder System.currentTimeMillis() für Konsistenz
        val eventTimeNanos = SystemClock.elapsedRealtimeNanos() // Zeit seit Boot in ns
        val timestampMs = TimeUnit.NANOSECONDS.toMillis(eventTimeNanos) // Umrechnen in ms

        val sensorType: String
        val values = event.values
        val x: Float
        val y: Float
        val z: Float

        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                sensorType = "GYRO"
                x = values[0]
                y = values[1]
                z = values[2]
                // Live-Daten (throttled)
                if (currentTimeMs - lastGyroUpdateTime > LIVE_UPDATE_INTERVAL_MS) {
                    lastGyroUpdateTime = currentTimeMs
                    runOnUiThread { // UI-Updates müssen im Main Thread erfolgen
                        binding.tvLiveData.text = updateLiveDataText("Gyro", x, y, z)
                    }
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                sensorType = "ACCEL"
                x = values[0]
                y = values[1]
                z = values[2]
                // Live-Daten (throttled)
                if (currentTimeMs - lastAccelUpdateTime > LIVE_UPDATE_INTERVAL_MS) {
                    lastAccelUpdateTime = currentTimeMs
                    runOnUiThread { // UI-Updates müssen im Main Thread erfolgen
                        binding.tvLiveData.text = updateLiveDataText("Accel", x, y, z)
                    }
                }
            }
            else -> return // Andere Sensoren ignorieren
        }

        // Daten zum Buffer hinzufügen (Format: CSV)
        // Timestamp_ms,SystemTime_ns,Sensor,X,Y,Z
        val dataLine = "$timestampMs,$eventTimeNanos,$sensorType,${"%.6f".format(x)},${"%.6f".format(y)},${"%.6f".format(z)}\n"
        dataBuffer.append(dataLine)

        // Buffer regelmäßig auf die Festplatte schreiben, um Speicher zu sparen
        if (currentTimeMs - lastWriteTime > WRITE_BUFFER_INTERVAL_MS) {
            flushBufferToFile()
            lastWriteTime = currentTimeMs
        }

        // Optional: Daten an Graphen senden (auch throttled!)
        // updateChart(sensorType, timestampMs, x, y, z)
    }

    // Hilfsfunktion für Live-Daten Anzeige
    private fun updateLiveDataText(sensor: String, x: Float, y: Float, z: Float): String {
        val currentText = binding.tvLiveData.text.toString().split("\n")
        var gyroLine = if (currentText.isNotEmpty()) currentText[0] else "Gyro: ..."
        var accelLine = if (currentText.size > 1) currentText[1] else "Accel: ..."

        val formattedData = "[X: %.2f, Y: %.2f, Z: %.2f]".format(x, y, z)
        if (sensor == "Gyro") {
            gyroLine = "Gyro: $formattedData"
        } else if (sensor == "Accel") {
            accelLine = "Accel: $formattedData"
        }
        // Nur die ersten beiden Zeilen behalten und neu zusammensetzen
        return "$gyroLine\n$accelLine"
    }


    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Wird aufgerufen, wenn sich die Genauigkeit des Sensors ändert.
        // Kann hier ignoriert oder geloggt werden.
        val accuracyStr = when(accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "UNRELIABLE"
            else -> "UNKNOWN"
        }
        Log.i("SensorCollector", "Accuracy changed for ${sensor?.name}: $accuracyStr")
    }

    // --- Activity Lifecycle Management ---

    override fun onPause() {
        super.onPause()
        // WICHTIG: Wenn die App pausiert wird UND die Sammlung über die Activity läuft
        // (nicht über einen Service), sollte die Sammlung hier gestoppt werden,
        // um Batterie zu sparen und unerwartetes Verhalten zu vermeiden.
        // Wenn ein Foreground Service verwendet wird, läuft die Sammlung weiter.
        // Für dieses Beispiel: Stoppen, wenn die Activity in den Hintergrund geht.
        if (isCollecting) {
            // Optional: Automatisch stoppen? Oder User informieren?
            // Für dieses Beispiel: Wir stoppen die Sammlung.
            binding.toggleButtonCollect.isChecked = false // Stoppt die Sammlung über den Listener
            Log.w("SensorCollector", "App paused, stopping collection.")
        }
    }

    override fun onDestroy() {
        // Sicherstellen, dass alles sauber beendet wird, wenn die Activity zerstört wird.
        if (isCollecting) {
            stopCollecting()
        }
        // Beende den Handler Thread explizit, falls er noch läuft
        sensorHandlerThread?.quitSafely()
        super.onDestroy()
    }
}