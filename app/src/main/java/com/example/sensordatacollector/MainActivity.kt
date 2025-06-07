package com.example.sensordatacollector

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
import android.content.SharedPreferences
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.FileReader
import java.io.FileWriter


class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding // View Binding
    private lateinit var sensorManager: SensorManager

    private var gyroscope: Sensor? = null
    private var accelerometer: Sensor? = null
    private var gravitySensor: Sensor? = null
    private var magFieldSensor: Sensor? = null
    private var rotVecSensor: Sensor? = null
    private var linAccSensor: Sensor? = null

    private var lastAccelerometerValues: FloatArray? = null
    private var lastGyroscopeValues: FloatArray? = null

    private var isCollecting = false
    private var fileOutputStream: FileOutputStream? = null
    private val dataBuffer = StringBuilder()
    private val flushIntervalMillis: Long = 1000L // Write every 1 second
    private var lastWriteTime = 0L


    // Für das Schreiben im Hintergrund-Thread
    private var sensorHandlerThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    // Für Live-Daten-Throttling
    private var lastGyroUpdateTime = 100L
    private var lastAccelUpdateTime = 100L
    private var lastGravUpdateTime = 100L
    private var lastLinAccUpdateTime = 100L
    private var lastRotVecUpdateTime = 100L
    private var lastMagFieldUpdateTime = 100L
    private val LIVE_UPDATE_INTERVAL_MS = 100L // UI nur alle 100ms aktualisieren

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magFieldSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        rotVecSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        linAccSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        setupSpinner()
        setupToggleButton()

        prepareFileOutputStream()
        writeHeaderToFile()

        // Überprüfen, ob Sensoren vorhanden sind
        binding.checkboxGravitySensor.isEnabled = (gravitySensor != null)
        binding.checkboxGyroscope.isEnabled = (gyroscope != null)
        binding.checkboxAccelerometer.isEnabled = (accelerometer != null)
        binding.checkboxMagFieldSensor.isEnabled = (magFieldSensor != null)
        binding.checkboxRotVecSensor.isEnabled = (rotVecSensor != null)
        binding.checkboxLinAccSensor.isEnabled = (linAccSensor != null)

        if (gyroscope == null) binding.checkboxGyroscope.text = "Gyroscope (Not available)"
        if (accelerometer == null) binding.checkboxAccelerometer.text = "Accelerometer (Not available)"
        if (gravitySensor == null) binding.checkboxAccelerometer.text = "GravitySensor (Not available)"
        if (magFieldSensor == null) binding.checkboxMagFieldSensor.text = "magField (Not available)"
        if (rotVecSensor == null) binding.checkboxRotVecSensor.text = "RotVec (Not available)"
        if (linAccSensor == null) binding.checkboxLinAccSensor.text = "LinAcc (Not available)"
    }

    private fun setupSpinner() {
        val frequencies = mapOf(
            "Fastest (0ms)" to SensorManager.SENSOR_DELAY_FASTEST, // 0 ms
            "Game (20ms)" to SensorManager.SENSOR_DELAY_GAME,       // 20 ms
            "UI (60ms)" to SensorManager.SENSOR_DELAY_UI,         // 60 ms
            "Normal (200ms)" to SensorManager.SENSOR_DELAY_NORMAL    // 200 ms
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
            binding.checkboxGravitySensor.isEnabled = !isChecked && (gravitySensor != null)
            binding.checkboxAccelerometer.isEnabled = !isChecked && (accelerometer != null)
            binding.checkboxRotVecSensor.isEnabled = !isChecked && (rotVecSensor != null)
            binding.checkboxLinAccSensor.isEnabled = !isChecked && (linAccSensor != null)
            binding.checkboxMagFieldSensor.isEnabled = !isChecked && (magFieldSensor != null)
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
        if (isCollecting) return // Wenn schon gestartet

        // Erstelle Hintergrund-Thread für Sensor-Events
        sensorHandlerThread = HandlerThread("SensorThread").apply { start() }
        sensorHandler = Handler(sensorHandlerThread!!.looper)

        val selectedFrequency = getSelectedFrequency()
        isCollecting = true
        binding.tvStatus.text = "Status: Collecting..."
        dataBuffer.clear()
        lastWriteTime = System.currentTimeMillis()



        // Listener registrieren
        sensorHandler?.post { // Registriere Listener auf dem Hintergrund-Thread
            if (binding.checkboxGyroscope.isChecked && gyroscope != null) {
                sensorManager.registerListener(this, gyroscope, selectedFrequency, sensorHandler)
            }
            if (binding.checkboxAccelerometer.isChecked && accelerometer != null) {
                sensorManager.registerListener(this, accelerometer, selectedFrequency, sensorHandler)
            }
            if (binding.checkboxLinAccSensor.isChecked && linAccSensor != null) {
                sensorManager.registerListener(this, linAccSensor, selectedFrequency, sensorHandler)
            }
            if (binding.checkboxGravitySensor.isChecked && gravitySensor != null) {
                sensorManager.registerListener(this, gravitySensor, selectedFrequency, sensorHandler)
            }
            if (binding.checkboxRotVecSensor.isChecked && rotVecSensor != null) {
                sensorManager.registerListener(this, rotVecSensor, selectedFrequency, sensorHandler)
            }
            if (binding.checkboxMagFieldSensor.isChecked && magFieldSensor != null) {
                sensorManager.registerListener(this, magFieldSensor, selectedFrequency, sensorHandler)
            }
        }
        Log.i("SensorCollector", "Started collecting.")
    }

    private fun stopCollecting() {
        if (!isCollecting) return // Schon gestoppt

        // write collected data to file
        writeToFile(dataBuffer.toString())

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

        Log.i("SensorCollector", "Stopped collecting.")

        // UI wieder aktivieren
        binding.checkboxGyroscope.isEnabled = (gyroscope != null)
        binding.checkboxAccelerometer.isEnabled = (accelerometer != null)
        binding.checkboxMagFieldSensor.isEnabled = (magFieldSensor != null)
        binding.checkboxGravitySensor.isEnabled = (gravitySensor != null)
        binding.checkboxLinAccSensor.isEnabled = (linAccSensor != null)
        binding.checkboxRotVecSensor.isEnabled = (rotVecSensor != null)
        binding.spinnerFrequency.isEnabled = true
    }

    private fun combineAndExportSensorData() {
        // Ensure we have recent values from both sensors before combining
        val accelValues = lastAccelerometerValues
        val gyroValues = lastGyroscopeValues
        val gyroValuesDeg = gyroValues?.map { radians -> Math.toDegrees(radians.toDouble()).toFloat() }?.toFloatArray()


        var tracktype = when {
            binding.radioButtonG.isChecked -> "G"
            binding.radioButtonL.isChecked -> "L"
            binding.radioButtonR.isChecked -> "R"
            binding.radioButtonBlank.isChecked -> "NONE"
            else -> {
                "NONE"
            }

        }



        if (accelValues != null && gyroValues != null) {
            val timestamp = System.currentTimeMillis()
            val dataEntry = "$timestamp,${accelValues.joinToString(",")},${gyroValues.joinToString(",")},${gyroValuesDeg?.joinToString(",")},$tracktype\n"

            dataBuffer.append(dataEntry)

            // Reset the individual sensor values after combining to ensure fresh pairs

            lastAccelerometerValues = null
            lastGyroscopeValues = null
        }
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
            Toast.makeText(this, "Saving to: ${file.absolutePath}", Toast.LENGTH_SHORT).show()
            return true
        } catch (e: IOException) {
            Log.e("SensorCollector", "Error creating file", e)
            Toast.makeText(this, "Error creating file: ${e.message}", Toast.LENGTH_LONG).show()
            fileOutputStream = null
            return false
        }
    }

    private fun writeHeaderToFile() {
        val header = "did,AccelerationX,AccelerationY,AccelerationZ,GyroscopeX_Grad,GyroscopeY_Grad,GyroscopeZ_Grad,GyroscopeX_Radiant,GyroscopeY_Radiant,GyroscopeZ_Radiant,Streckentyp\n"
        try {
            fileOutputStream?.write(header.toByteArray())
        } catch (e: IOException) {
            Log.e("SensorCollector", "Error writing header to file", e)
        }
    }


    private fun writeToFile(data: String) {

        // Optional: Daten an Graphen senden (auch throttled!)
        // updateChart(sensorType, timestampMs, x, y, z)

        if (fileOutputStream == null) return // Nicht schreiben, wenn Datei nicht offen ist

        try {
            fileOutputStream?.write(data.toByteArray())
        } catch (e: IOException) {
            Log.e("SensorCollector", "Error writing to file", e)
            // Optional: Sammlung stoppen oder Fehler anzeigen
            // stopCollecting()
            runOnUiThread { Toast.makeText(this, "Error writing data!", Toast.LENGTH_SHORT).show() }
        }
    }

//    private fun flushBufferToFile() {
//        if (dataBuffer.isNotEmpty()) {
//            writeToFile(dataBuffer.toString())
//            dataBuffer.clear() // Buffer leeren nach dem Schreiben
//        }
//    }

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
        val eventTimeNanos = SystemClock.elapsedRealtimeNanos() // Zeit seit Boot in ns
        val timestampMs = TimeUnit.NANOSECONDS.toMillis(eventTimeNanos) // Umrechnen in ms

        val sensorType: String
        val values = event.values

        val x: Float
        val y: Float
        val z: Float

        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                lastGyroscopeValues = values.clone()
                sensorType = "GYRO"
                x = values[0]
                y = values[1]
                z = values[2]
                // Live-Daten (throttled)
                if (currentTimeMs - lastGyroUpdateTime > LIVE_UPDATE_INTERVAL_MS) {
                    lastGyroUpdateTime = currentTimeMs
                    combineAndExportSensorData()
                    runOnUiThread { // UI-Updates müssen im Main Thread erfolgen
                        binding.tvLiveData.text = updateLiveDataText("Gyro", x, y, z)
                    }
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccelerometerValues = values.clone()
                sensorType = "ACCEL"
                x = values[0]
                y = values[1]
                z = values[2]
                // Live-Daten (throttled)
                if (currentTimeMs - lastAccelUpdateTime > LIVE_UPDATE_INTERVAL_MS) {
                    combineAndExportSensorData()
                    lastAccelUpdateTime = currentTimeMs
                    runOnUiThread { // UI-Updates müssen im Main Thread erfolgen
                        binding.tvLiveData.text = updateLiveDataText("Accel", x, y, z)
                    }
                }
            }
            Sensor.TYPE_GRAVITY -> {
                sensorType = "GRAV"
                x = values[0]
                y = values[1]
                z = values[2]
                // Live-Daten (throttled)
                if (currentTimeMs - lastGravUpdateTime > LIVE_UPDATE_INTERVAL_MS) {
                    lastGravUpdateTime = currentTimeMs
                    runOnUiThread { // UI-Updates müssen im Main Thread erfolgen
                        binding.tvLiveData.text = updateLiveDataText("Grav", x, y, z)
                    }
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                sensorType = "LINACC"
                x = values[0]
                y = values[1]
                z = values[2]
                // Live-Daten (throttled)
                if (currentTimeMs - lastLinAccUpdateTime > LIVE_UPDATE_INTERVAL_MS) {
                    lastLinAccUpdateTime = currentTimeMs
                    runOnUiThread { // UI-Updates müssen im Main Thread erfolgen
                        binding.tvLiveData.text = updateLiveDataText("LinAcc", x, y, z)
                    }
                }
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                sensorType = "ROTVEC"
                x = values[0]
                y = values[1]
                z = values[2]
                // Live-Daten (throttled)
                if (currentTimeMs - lastRotVecUpdateTime > LIVE_UPDATE_INTERVAL_MS) {
                    lastRotVecUpdateTime = currentTimeMs
                    runOnUiThread { // UI-Updates müssen im Main Thread erfolgen
                        binding.tvLiveData.text = updateLiveDataText("RotVec", x, y, z)
                    }
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                sensorType = "MAGFIELD"
                x = values[0]
                y = values[1]
                z = values[2]
                // Live-Daten (throttled)
                if (currentTimeMs - lastMagFieldUpdateTime > LIVE_UPDATE_INTERVAL_MS) {
                    lastMagFieldUpdateTime = currentTimeMs
                    runOnUiThread { // UI-Updates müssen im Main Thread erfolgen
                        binding.tvLiveData.text = updateLiveDataText("MagField", x, y, z)
                    }
                }
            }
            else -> return // Andere Sensoren ignorieren
        }
    }

    // Hilfsfunktion für Live-Daten Anzeige
    private fun updateLiveDataText(sensor: String, x: Float, y: Float, z: Float): String {
        val currentText = binding.tvLiveData.text.toString().split("\n")
        var gyroLineRad = if (currentText.isNotEmpty()) currentText[0] else "Gyro: ..."
        var accelLine = if (currentText.size > 1) currentText[1] else "Accel: ..."
//        var linLine = if (currentText.size > 1) currentText[3] else "LinAcc: ..."
//        var gravLine = if (currentText.size > 1) currentText[2] else "Gravity: ..."
//        var RotLine = if (currentText.size > 1) currentText[4] else "RotVec: ..."
//        var magLine = if (currentText.size > 1) currentText[5] else "MagField: ..."

        val formattedData = "[X: %.2f, Y: %.2f, Z: %.2f]".format(x, y, z)

        if (sensor == "Gyro") {
            gyroLineRad = "GyroRad: $formattedData"

        }
        else if (sensor == "Accel") {
            accelLine = "Accel: $formattedData"
        }
//        else if (sensor == "Grav") {
//            gravLine = "Grav: $formattedData"
//        }
//        else if (sensor == "LinAcc") {
//            linLine = "LinAcc: $formattedData"
//        }
//        else if (sensor == "RotVec") {
//            RotLine = "RotVec: $formattedData"
//        }
//        else if (sensor == "MagField") {
//            magLine = "MagField: $formattedData"
//        }



        // Nur die ersten beiden Zeilen behalten und neu zusammensetzen
        // return "$gyroLine\n$accelLine\n$gravLine\n$linLine\n$RotLine\n$magLine"
        return "$gyroLineRad\n$accelLine"
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


