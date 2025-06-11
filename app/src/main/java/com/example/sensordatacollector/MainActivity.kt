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
import java.io.BufferedReader
import java.io.FileReader
import java.io.FileWriter
import kotlin.math.pow


class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding // View Binding
    private lateinit var sensorManager: SensorManager

    private var gyroscope: Sensor? = null
    private var accelerometer: Sensor? = null
    private var gravitySensor: Sensor? = null
    private var magFieldSensor: Sensor? = null
    private var rotVecSensor: Sensor? = null
    private var linAccSensor: Sensor? = null
    private var fileName = "none"

    private var lastAccelerometerValues: FloatArray? = null
    private var lastGyroscopeValues: FloatArray? = null
    private var lastGravValues: FloatArray? = null
    private var lastLinAccValues: FloatArray? = null

    var currentTimeMs = System.currentTimeMillis()
    var lastClassifyUpdateTime = 0L



    private var isCollecting = false
    private var fileOutputStream: FileOutputStream? = null
    private val dataBuffer = StringBuilder()
    private val flushIntervalMillis: Long = 1000L // Write every 1 second
    private var lastWriteTime = 0L
    private var carreraMode = true // IF TRUE: EXPORT IN CARRERABAHN FORMAT, ELSE: EXPORT IN USER STAND LAUFEN ERKENNUNG FORMAT

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
        setupToggleButtonCarrera()

        // Überprüfen, ob Sensoren vorhanden sind
//        binding.checkboxGravitySensor.isEnabled = (gravitySensor != null)
//        binding.checkboxGyroscope.isEnabled = (gyroscope != null)
//        binding.checkboxAccelerometer.isEnabled = (accelerometer != null)
//        binding.checkboxMagFieldSensor.isEnabled = (magFieldSensor != null)
//        binding.checkboxRotVecSensor.isEnabled = (rotVecSensor != null)
//        binding.checkboxLinAccSensor.isEnabled = (linAccSensor != null)

//        if (gyroscope == null) binding.checkboxGyroscope.text = "Gyroscope (Not available)"
//        if (accelerometer == null) binding.checkboxAccelerometer.text = "Accelerometer (Not available)"
//        if (gravitySensor == null) binding.checkboxAccelerometer.text = "GravitySensor (Not available)"
//        if (magFieldSensor == null) binding.checkboxMagFieldSensor.text = "magField (Not available)"
//        if (rotVecSensor == null) binding.checkboxRotVecSensor.text = "RotVec (Not available)"
//        if (linAccSensor == null) binding.checkboxLinAccSensor.text = "LinAcc (Not available)"
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

    private fun setupToggleButtonCarrera() {
        binding.toggleButtonCollect.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                carreraMode = true
                prepareFileOutputStream()
                writeHeaderToFile()
                startCollecting()
            } else {
                stopCollecting()
            }
            binding.toggleButtonCollect2.isEnabled = !isChecked
            // UI-Konfiguration während der Sammlung sperren/entsperren
            binding.spinnerFrequency.isEnabled = !isChecked
        }
    }
    private fun setupToggleButton() { // für user lauf steh mode detect
        binding.toggleButtonCollect2.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                carreraMode = false
                prepareFileOutputStream()
                writeHeaderToFile()
                startCollecting()
            } else {
                stopCollecting()
            }
            binding.toggleButtonCollect.isEnabled = !isChecked
            // UI-Konfiguration während der Sammlung sperren/entsperren
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
            if ( gyroscope != null) {
                sensorManager.registerListener(this, gyroscope, selectedFrequency, sensorHandler)
            }
            if (accelerometer != null) {
                sensorManager.registerListener(this, accelerometer, selectedFrequency, sensorHandler)
            }
            if (linAccSensor != null) {
                sensorManager.registerListener(this, linAccSensor, selectedFrequency, sensorHandler)
            }
            if (gravitySensor != null) {
                sensorManager.registerListener(this, gravitySensor, selectedFrequency, sensorHandler)
            }
            if ( rotVecSensor != null) {
                sensorManager.registerListener(this, rotVecSensor, selectedFrequency, sensorHandler)
            }
            if (magFieldSensor != null) {
                sensorManager.registerListener(this, magFieldSensor, selectedFrequency, sensorHandler)
            }
        }
        Log.i("SensorCollector", "Started collecting.")
    }

    fun findeHaeufigsteAktivitaet(aktivitaetenListe: MutableList<Aktivitaet>): Aktivitaet? {
        if (aktivitaetenListe.isEmpty()) {
            return null // Keine Aktivitäten in der Liste
        }

        // 1. Gruppiere die Aktivitäten und zähle ihre Vorkommen
        // Das Ergebnis ist eine Map<Aktivitaet, Int>, wobei Int die Anzahl ist.
        val aktivitaetCounts = aktivitaetenListe.groupingBy { it }.eachCount()

        // Debug-Ausgabe (optional)
        // println("Aktivitätszählungen: $aktivitaetCounts")

        // 2. Finde den Eintrag in der Map mit dem höchsten Zählwert
        // maxByOrNull gibt den Eintrag (Map.Entry<Aktivitaet, Int>) zurück,
        // dessen Wert (it.value, also die Anzahl) maximal ist.
        val haeufigsteEintrag = aktivitaetCounts.entries.maxByOrNull { it.value }

        // 3. Gib den Schlüssel (die Aktivitaet) dieses Eintrags zurück
        return haeufigsteEintrag?.key
    }



    private fun meanPrimaryActivity() :String{

        var activityGuesses: MutableList<Aktivitaet> = mutableListOf()
        activityGuesses.add(Aktivitaet.ShowHistory)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, activityGuesses)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerAktivitaet.adapter = adapter

        var sensordatenListe = readSensorDataFromCsv(File(filesDir, fileName))
        for (daten in sensordatenListe) {
            var current = klassifiziereAktivitaet(daten)
            activityGuesses += current


        }
        adapter.notifyDataSetChanged()

        var guess = findeHaeufigsteAktivitaet(activityGuesses)




        return "Wahrscheinlichste UserActivity: $guess"

    }

    private fun stopCollecting() {
        if (!isCollecting) return // Schon gestoppt

        // write collected data to file
        writeToFile(dataBuffer.toString())

        binding.guessedUserAct.text = meanPrimaryActivity()




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



        Log.i("SensorCollector", "Stopped collecting.")

        // UI wieder aktivieren
        binding.spinnerFrequency.isEnabled = true
    }



    private fun combineAndExportSensorData() {
        // Ensure we have recent values from both sensors before combining
        val accelValues = lastAccelerometerValues
        val gyroValues = lastGyroscopeValues
        val gravValues = lastGravValues
        val linAccValues = lastLinAccValues
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
        var fortbewegungsart = when {
            binding.radioButtonStehen.isChecked -> "Stehen"
            binding.radioButtonLaufen.isChecked -> "Laufen"
            binding.radioButtonRennen.isChecked -> "Rennen"

            else -> {
                "NONE"
            }

        }



        if (carreraMode) {
            if (accelValues != null && gyroValues != null) {
                val timestamp = System.currentTimeMillis()
                val dataEntry = "$timestamp,${accelValues.joinToString(",")},${gyroValues.joinToString(",")},${gyroValuesDeg?.joinToString(",")},$tracktype\n"

                dataBuffer.append(dataEntry)

                // Reset the individual sensor values after combining to ensure fresh pairs

                lastAccelerometerValues = null
                lastGyroscopeValues = null
            }
        }
        else{
            if (accelValues != null && gyroValues != null && gravValues != null && linAccValues != null) {
                val timestamp = System.currentTimeMillis()
                var accelMean = accelValues.average().toFloat()
                var accelStdDev = accelValues.map { (it - accelMean).toDouble().pow(2.0).toFloat() }.average().toFloat()
                var accelMin = accelValues.minOrNull() ?: 0f
                var accelMax = accelValues.maxOrNull() ?: 0f
                var gyroMean = gyroValues.average().toFloat()
                var gyroStdDev = gyroValues.map { (it - gyroMean).toDouble().pow(2.0).toFloat() }.average().toFloat()
                var gyroMin = gyroValues.minOrNull() ?: 0f
                var gyroMax = gyroValues.maxOrNull() ?: 0f

                val dataEntry = "$timestamp,${accelMean},${accelStdDev},${accelMin},${accelMax},${gyroMean},${gyroStdDev},${gyroMin},${gyroMax},$fortbewegungsart\n"

                dataBuffer.append(dataEntry)
                var daten = Sensordaten(accelMean, accelStdDev, accelMin, accelMax, gyroMean, gyroStdDev, gyroMin, gyroMax)
                var current = klassifiziereAktivitaet(daten)

                    runOnUiThread { binding.spinnerAktivitaetLabel.text = "${System.currentTimeMillis()} -  $current"}



                // Reset the individual sensor values after combining to ensure fresh pairs

                lastAccelerometerValues = null
                lastGyroscopeValues = null
            }
        }
    }

    private fun prepareFileOutputStream(): Boolean {
        try {
            val timestamp = SimpleDateFormat("MMdd_HHmmss", Locale.getDefault()).format(Date())

            if(carreraMode) {
                fileName = "carrera_sensor_$timestamp.csv"
            }
            else {
                fileName = "humanactivity_sensor_$timestamp.csv"
            }
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
        var header: String
        if(carreraMode) {
            header = "did,AccelerationX,AccelerationY,AccelerationZ,GyroscopeX_Grad,GyroscopeY_Grad,GyroscopeZ_Grad,GyroscopeX_Radiant,GyroscopeY_Radiant,GyroscopeZ_Radiant,Streckentyp\n"
        }
        else {
            header = "did,AccelerationMean,AccelerationStdDev,AccelerationMin,AccelerationMax,GyroscopeMean,GyroscopeStdDev,GyroscopeMin,GyroscopeMax,Fortbewegungsart\n"

        }
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
                lastGravValues = values.clone()
                sensorType = "GRAV"
                x = values[0]
                y = values[1]
                z = values[2]
                // Live-Daten (throttled)
                if (currentTimeMs - lastGravUpdateTime > LIVE_UPDATE_INTERVAL_MS) {
                    combineAndExportSensorData()
                    lastGravUpdateTime = currentTimeMs
                    runOnUiThread { // UI-Updates müssen im Main Thread erfolgen
                        binding.tvLiveData.text = updateLiveDataText("Grav", x, y, z)
                    }
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                lastLinAccValues = values.clone()
                sensorType = "LINACC"
                x = values[0]
                y = values[1]
                z = values[2]
                // Live-Daten (throttled)
                if (currentTimeMs - lastLinAccUpdateTime > LIVE_UPDATE_INTERVAL_MS) {
                    combineAndExportSensorData()
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
        var linLine = if (currentText.size > 1) currentText[3] else "LinAcc: ..."
        var gravLine = if (currentText.size > 1) currentText[2] else "Gravity: ..."


        val formattedData = "[X: %.2f, Y: %.2f, Z: %.2f]".format(x, y, z)

        if (sensor == "Gyro") {
            gyroLineRad = "GyroRad: $formattedData"
        }
        else if (sensor == "Accel") {
            accelLine = "Accel: $formattedData"
        }
        else if (sensor == "Grav") {
            gravLine = "Grav: $formattedData"
        }
        else if (sensor == "LinAcc") {
            linLine = "LinAcc: $formattedData"
        }




        // Nur die ersten beiden Zeilen behalten und neu zusammensetzen
        // return "$gyroLine\n$accelLine\n$gravLine\n$linLine\n$RotLine\n$magLine"
        return "$gyroLineRad\n$accelLine\n$gravLine\n$linLine"
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

    fun readSensorDataFromCsv(
        csvFile: File,
        delimiter: Char = ',',
        skipHeader: Boolean = true
    ): List<Sensordaten> {
        val sensorDataList = mutableListOf<Sensordaten>()

        if (!csvFile.exists() || !csvFile.canRead()) {
            println("Fehler: CSV-Datei existiert nicht oder kann nicht gelesen werden: ${csvFile.absolutePath}")
            return emptyList()
        }

        try {
            BufferedReader(FileReader(csvFile)).use { reader ->
                var line: String?
                if (skipHeader) {
                    reader.readLine() // Kopfzeile lesen und verwerfen
                }
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.split(delimiter)
                    try {
                        // Annahme: Die Reihenfolge der Spalten in der CSV entspricht den Parametern von Sensordaten
                        // Passen Sie die Indizes und Typkonvertierungen ggf. an Ihre CSV-Struktur an!
                        if (parts.size >= 10) { // Mindestens die erforderlichen Felder + timestamp und class
                            val amean = parts[1].trim().toFloat()
                            val asd = parts[2].trim().toFloat()
                            val amin = parts[3].trim().toFloat()
                            val amax = parts[4].trim().toFloat()
                            val gmean = parts[5].trim().toFloat()
                            val gsd = parts[6].trim().toFloat()
                            val gmin = parts[7].trim().toFloat()
                            val gmax = parts[8].trim().toFloat()


                            sensorDataList.add(
                                Sensordaten (
                                    GyroscopeStdDev = gsd,
                                AccelerationMin = amin,
                            GyroscopeMean = gmean,
                            AccelerationMax = amax, AccelerationMean = amean,
                            AccelerationStdDev = asd,
                            GyroscopeMin = gmin,
                            GyroscopeMax = gmax,
                                )
                            )
                        } else {
                            println("Warnung: Zeile übersprungen, da nicht genügend Spalten: $line")
                        }
                    } catch (e: NumberFormatException) {
                        println("Warnung: Zeile übersprungen aufgrund eines Zahlenformatfehlers: $line - Fehler: ${e.message}")
                    } catch (e: IndexOutOfBoundsException) {
                        println("Warnung: Zeile übersprungen aufgrund fehlender Spalten: $line - Fehler: ${e.message}")
                    }
                }
            }
        } catch (e: IOException) {
            println("Fehler beim Lesen der CSV-Datei: ${e.message}")
            e.printStackTrace() // Für detailliertere Fehlersuche
            return emptyList() // Bei schwerwiegenden IO-Fehlern leere Liste zurückgeben
        }

        return sensorDataList
    }

    enum class Aktivitaet {
        Rennen,
        Laufen,
        Stehen,
        ShowHistory
    }

    /**
     * Datenklasse zur Aufnahme der Sensor-Merkmale.
     * Alle Werte sind Double, da die Regeln Dezimalzahlen enthalten.
     */
    data class Sensordaten(
        val AccelerationMean: Float,
        val AccelerationStdDev: Float,
        val AccelerationMin: Float,
        val AccelerationMax: Float,
        val GyroscopeMean: Float,
        val GyroscopeStdDev: Float,
        val GyroscopeMin: Float,
        val GyroscopeMax: Float
    )

    /**
     * Klassifiziert die Aktivität basierend auf den bereitgestellten Sensor-Merkmalen
     * und den übersetzten J48-Entscheidungsbaum-Regeln.
     *
     * @param daten Eine Instanz der Sensordaten-Klasse, die alle benötigten Merkmale enthält.
     * @return Die klassifizierte Aktivität (Rennen, Laufen, Stehen) oder Unbekannt.
     */
    fun klassifiziereAktivitaet(daten: Sensordaten): Aktivitaet {
        // Top-Level-Regel: GyroscopeStdDev
        if (daten.GyroscopeStdDev <= 0.078208) {
            if (daten.AccelerationMin <= -6.745663) {
                if (daten.GyroscopeMean <= 0.349619) {
                    if (daten.AccelerationMin <= -10.316615) {
                        if (daten.AccelerationMin <= -11.549629) {
                            if (daten.AccelerationMax <= 0.150835) {
                                return Aktivitaet.Rennen
                            } else {
                                if (daten.AccelerationMean <= -3.157952) {
                                    return Aktivitaet.Laufen
                                } else {
                                    if (daten.AccelerationMean <= -1.926933) {
                                        return Aktivitaet.Stehen
                                    } else {
                                        return Aktivitaet.Laufen
                                    }
                                }
                            }
                        } else { // AccelerationMin > -11.549629
                            if (daten.AccelerationMean <= -4.112441) {
                                return Aktivitaet.Laufen
                            } else {
                                return Aktivitaet.Stehen
                            }
                        }
                    } else { // AccelerationMin > -10.316615
                        if (daten.GyroscopeStdDev <= 0.030954) {
                            return Aktivitaet.Stehen
                        } else { // GyroscopeStdDev > 0.030954
                            if (daten.GyroscopeMean <= -0.889216) {
                                return Aktivitaet.Laufen
                            } else { // GyroscopeMean > -0.889216
                                if (daten.AccelerationStdDev <= 15.872898) {
                                    if (daten.AccelerationMean <= -3.872621) {
                                        return Aktivitaet.Stehen
                                    } else {
                                        return Aktivitaet.Laufen
                                    }
                                } else { // AccelerationStdDev > 15.872898
                                    return Aktivitaet.Stehen
                                }
                            }
                        }
                    }
                } else { // GyroscopeMean > 0.349619
                    if (daten.AccelerationStdDev <= 10.6532) {
                        return Aktivitaet.Stehen
                    } else {
                        return Aktivitaet.Laufen
                    }
                }
            } else { // AccelerationMin > -6.745663
                if (daten.AccelerationMax <= 8.157045) {
                    if (daten.AccelerationMin <= -0.895431) {
                        if (daten.GyroscopeMin <= -0.80283) {
                            return Aktivitaet.Laufen
                        } else { // GyroscopeMin > -0.80283
                            if (daten.GyroscopeMax <= 0.312763) {
                                if (daten.GyroscopeStdDev <= 0.024373) {
                                    return Aktivitaet.Stehen
                                } else { // GyroscopeStdDev > 0.024373
                                    if (daten.AccelerationMean <= 2.595315) {
                                        return Aktivitaet.Laufen
                                    } else {
                                        return Aktivitaet.Stehen
                                    }
                                }
                            } else { // GyroscopeMax > 0.312763
                                if (daten.GyroscopeStdDev <= 0.046356) {
                                    return Aktivitaet.Laufen
                                } else {
                                    return Aktivitaet.Rennen
                                }
                            }
                        }
                    } else { // AccelerationMin > -0.895431
                        if (daten.AccelerationMin <= 1.58975) {
                            return Aktivitaet.Rennen
                        } else {
                            return Aktivitaet.Laufen
                        }
                    }
                } else { // AccelerationMax > 8.157045
                    if (daten.GyroscopeMin <= -0.060628) {
                        if (daten.GyroscopeMean <= -0.217875) {
                            if (daten.GyroscopeMax <= -0.240834) {
                                return Aktivitaet.Rennen
                            } else { // GyroscopeMax > -0.240834
                                if (daten.AccelerationMean <= 4.427278) {
                                    return Aktivitaet.Stehen
                                } else {
                                    if (daten.AccelerationStdDev <= 12.519543) {
                                        return Aktivitaet.Stehen
                                    } else {
                                        return Aktivitaet.Rennen
                                    }
                                }
                            }
                        } else { // GyroscopeMean > -0.217875
                            if (daten.AccelerationStdDev <= 17.266388) {
                                if (daten.GyroscopeMax <= 0.17822) {
                                    return Aktivitaet.Stehen
                                } else { // GyroscopeMax > 0.17822
                                    if (daten.AccelerationMax <= 8.829816) {
                                        return Aktivitaet.Laufen
                                    } else {
                                        return Aktivitaet.Stehen
                                    }
                                }
                            } else { // AccelerationStdDev > 17.266388
                                if (daten.AccelerationMax <= 8.822633) {
                                    return Aktivitaet.Stehen
                                } else {
                                    return Aktivitaet.Laufen
                                }
                            }
                        }
                    } else { // GyroscopeMin > -0.060628
                        if (daten.AccelerationMax <= 9.200917) {
                            if (daten.AccelerationStdDev <= 12.221857) {
                                if (daten.AccelerationStdDev <= 10.543983) {
                                    return Aktivitaet.Laufen
                                } else { // AccelerationStdDev > 10.543983
                                    if (daten.AccelerationMean <= 4.451619) {
                                        if (daten.AccelerationMean <= 4.391365) {
                                            return Aktivitaet.Stehen
                                        } else {
                                            return Aktivitaet.Laufen
                                        }
                                    } else { // AccelerationMean > 4.451619
                                        return Aktivitaet.Stehen
                                    }
                                }
                            } else { // AccelerationStdDev > 12.221857
                                if (daten.GyroscopeMax <= 0.128893) {
                                    return Aktivitaet.Laufen
                                } else { // GyroscopeMax > 0.128893
                                    if (daten.AccelerationMean <= 3.31597) {
                                        return Aktivitaet.Laufen
                                    } else {
                                        return Aktivitaet.Stehen
                                    }
                                }
                            }
                        } else { // AccelerationMax > 9.200917
                            if (daten.GyroscopeMin <= 0.162337) {
                                if (daten.AccelerationStdDev <= 13.338376) {
                                    return Aktivitaet.Laufen
                                } else {
                                    return Aktivitaet.Stehen
                                }
                            } else { // GyroscopeMin > 0.162337
                                return Aktivitaet.Laufen
                            }
                        }
                    }
                }
            }
        } else { // GyroscopeStdDev > 0.078208
            if (daten.GyroscopeMin <= -4.647921) {
                if (daten.AccelerationMin <= -11.152191) {
                    if (daten.GyroscopeStdDev <= 6.410218) {
                        if (daten.AccelerationMax <= 1.595735) {
                            return Aktivitaet.Rennen
                        } else {
                            return Aktivitaet.Laufen
                        }
                    } else { // GyroscopeStdDev > 6.410218
                        return Aktivitaet.Rennen
                    }
                } else { // AccelerationMin > -11.152191
                    return Aktivitaet.Rennen
                }
            } else { // GyroscopeMin > -4.647921
                if (daten.AccelerationMean <= -9.143456) {
                    if (daten.GyroscopeStdDev <= 0.528013) {
                        if (daten.GyroscopeMean <= 0.967407) {
                            if (daten.AccelerationMean <= -12.867238) {
                                if (daten.AccelerationMax <= -0.751779) {
                                    return Aktivitaet.Rennen
                                } else {
                                    return Aktivitaet.Laufen
                                }
                            } else { // AccelerationMean > -12.867238
                                return Aktivitaet.Laufen
                            }
                        } else { // GyroscopeMean > 0.967407
                            return Aktivitaet.Rennen
                        }
                    } else { // GyroscopeStdDev > 0.528013
                        return Aktivitaet.Rennen
                    }
                } else { // AccelerationMean > -9.143456
                    if (daten.GyroscopeMax <= 4.508185) {
                        if (daten.AccelerationMean <= 1.611298) {
                            if (daten.GyroscopeStdDev <= 0.79118) {
                                if (daten.AccelerationMin <= -11.134235) {
                                    return Aktivitaet.Laufen
                                } else { // AccelerationMin > -11.134235
                                    if (daten.AccelerationMin <= -7.547721) {
                                        if (daten.GyroscopeMean <= 0.456724) {
                                            if (daten.GyroscopeMean <= -0.554666) {
                                                return Aktivitaet.Laufen
                                            } else { // GyroscopeMean > -0.554666
                                                if (daten.AccelerationMean <= -0.49161) {
                                                    if (daten.AccelerationMax <= 3.46441) {
                                                        if (daten.GyroscopeMean <= -0.259821) {
                                                            if (daten.AccelerationMin <= -8.913613) {
                                                                if (daten.GyroscopeMean <= -0.416814) {
                                                                    return Aktivitaet.Stehen
                                                                } else {
                                                                    return Aktivitaet.Laufen
                                                                }
                                                            } else { // AccelerationMin > -8.913613
                                                                if (daten.AccelerationMean <= -2.205459) {
                                                                    return Aktivitaet.Rennen
                                                                } else {
                                                                    return Aktivitaet.Laufen
                                                                }
                                                            }
                                                        } else { // GyroscopeMean > -0.259821
                                                            if (daten.GyroscopeStdDev <= 0.502538) {
                                                                return Aktivitaet.Stehen
                                                            } else {
                                                                return Aktivitaet.Laufen
                                                            }
                                                        }
                                                    } else { // AccelerationMax > 3.46441
                                                        if (daten.GyroscopeMean <= 0.218282) {
                                                            return Aktivitaet.Stehen
                                                        } else { // GyroscopeMean > 0.218282
                                                            if (daten.GyroscopeStdDev <= 0.363193) {
                                                                return Aktivitaet.Laufen
                                                            } else {
                                                                return Aktivitaet.Stehen
                                                            }
                                                        }
                                                    }
                                                } else { // AccelerationMean > -0.49161
                                                    return Aktivitaet.Laufen
                                                }
                                            }
                                        } else { // GyroscopeMean > 0.456724
                                            if (daten.GyroscopeMean <= 0.488692) {
                                                return Aktivitaet.Rennen
                                            } else { // GyroscopeMean > 0.488692
                                                if (daten.GyroscopeMean <= 2.144952) {
                                                    return Aktivitaet.Laufen
                                                } else {
                                                    return Aktivitaet.Rennen
                                                }
                                            }
                                        }
                                    } else { // AccelerationMin > -7.547721
                                        if (daten.AccelerationStdDev <= 13.053646) {
                                            if (daten.GyroscopeStdDev <= 0.162174) {
                                                if (daten.AccelerationMean <= -3.20025) {
                                                    return Aktivitaet.Rennen
                                                } else {
                                                    return Aktivitaet.Laufen
                                                }
                                            } else { // GyroscopeStdDev > 0.162174
                                                return Aktivitaet.Laufen
                                            }
                                        } else { // AccelerationStdDev > 13.053646
                                            if (daten.GyroscopeStdDev <= 0.327222) {
                                                return Aktivitaet.Laufen
                                            } else { // GyroscopeStdDev > 0.327222
                                                if (daten.AccelerationMin <= -5.374983) {
                                                    if (daten.AccelerationMin <= -5.762844) {
                                                        if (daten.GyroscopeMean <= 0.424551) {
                                                            if (daten.AccelerationStdDev <= 28.296442) {
                                                                if (daten.GyroscopeMean <= 0.162083) {
                                                                    return Aktivitaet.Laufen
                                                                } else {
                                                                    return Aktivitaet.Stehen
                                                                }
                                                            } else { // AccelerationStdDev > 28.296442
                                                                return Aktivitaet.Stehen
                                                            }
                                                        } else { // GyroscopeMean > 0.424551
                                                            return Aktivitaet.Laufen
                                                        }
                                                    } else { // AccelerationMin > -5.762844
                                                        return Aktivitaet.Stehen
                                                    }
                                                } else { // AccelerationMin > -5.374983
                                                    if (daten.AccelerationMean <= 0.381875) {
                                                        return Aktivitaet.Rennen
                                                    } else {
                                                        return Aktivitaet.Laufen
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else { // GyroscopeStdDev > 0.79118
                                if (daten.AccelerationStdDev <= 3.06026) {
                                    if (daten.GyroscopeStdDev <= 2.988615) {
                                        if (daten.GyroscopeMean <= 0.693332) {
                                            return Aktivitaet.Laufen
                                        } else {
                                            return Aktivitaet.Rennen
                                        }
                                    } else { // GyroscopeStdDev > 2.988615
                                        return Aktivitaet.Rennen
                                    }
                                } else { // AccelerationStdDev > 3.06026
                                    if (daten.GyroscopeStdDev <= 2.350206) {
                                        if (daten.AccelerationStdDev <= 11.291626) {
                                            if (daten.GyroscopeMean <= 0.920167) {
                                                if (daten.AccelerationMax <= 4.748899) {
                                                    return Aktivitaet.Laufen
                                                } else {
                                                    return Aktivitaet.Rennen
                                                }
                                            } else { // GyroscopeMean > 0.920167
                                                return Aktivitaet.Rennen
                                            }
                                        } else { // AccelerationStdDev > 11.291626
                                            if (daten.AccelerationMin <= -6.908469) {
                                                return Aktivitaet.Laufen
                                            } else { // AccelerationMin > -6.908469
                                                if (daten.AccelerationStdDev <= 13.09557) {
                                                    return Aktivitaet.Laufen
                                                } else {
                                                    return Aktivitaet.Rennen
                                                }
                                            }
                                        }
                                    } else { // GyroscopeStdDev > 2.350206
                                        if (daten.AccelerationMin <= -7.25084) {
                                            if (daten.AccelerationMean <= -6.194199) {
                                                if (daten.AccelerationMax <= 4.086902) {
                                                    return Aktivitaet.Rennen
                                                } else {
                                                    return Aktivitaet.Laufen
                                                }
                                            } else { // AccelerationMean > -6.194199
                                                return Aktivitaet.Laufen
                                            }
                                        } else { // AccelerationMin > -7.25084
                                            if (daten.GyroscopeMean <= -0.659938) {
                                                if (daten.AccelerationMax <= 1.466449) {
                                                    return Aktivitaet.Laufen
                                                } else { // AccelerationMax > 1.466449
                                                    if (daten.GyroscopeStdDev <= 4.380723) {
                                                        return Aktivitaet.Rennen
                                                    } else {
                                                        return Aktivitaet.Stehen
                                                    }
                                                }
                                            } else { // GyroscopeMean > -0.659938
                                                if (daten.AccelerationMean <= -1.474828) {
                                                    if (daten.GyroscopeMax <= 1.806329) {
                                                        return Aktivitaet.Laufen
                                                    } else { // GyroscopeMax > 1.806329
                                                        if (daten.AccelerationStdDev <= 5.592368) {
                                                            if (daten.AccelerationMean <= -1.811613) {
                                                                return Aktivitaet.Laufen
                                                            } else {
                                                                return Aktivitaet.Rennen
                                                            }
                                                        } else {
                                                            return Aktivitaet.Rennen
                                                        }
                                                    }
                                                } else { // AccelerationMean > -1.474828
                                                    return Aktivitaet.Laufen
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else { // AccelerationMean > 1.611298
                            if (daten.AccelerationStdDev <= 64.68753) {
                                if (daten.GyroscopeMean <= -0.781704) {
                                    if (daten.AccelerationMin <= -5.292383) {
                                        return Aktivitaet.Laufen
                                    } else {
                                        return Aktivitaet.Rennen
                                    }
                                } else { // GyroscopeMean > -0.781704
                                    if (daten.GyroscopeMax <= 1.737453) {
                                        if (daten.AccelerationMax <= 10.147824) {
                                            if (daten.AccelerationMean <= 4.14955) {
                                                if (daten.GyroscopeMax <= 1.096045) {
                                                    if (daten.GyroscopeMean <= -0.213599) {
                                                        if (daten.GyroscopeStdDev <= 1.130474) {
                                                            return Aktivitaet.Laufen
                                                        } else {
                                                            return Aktivitaet.Rennen
                                                        }
                                                    } else { // GyroscopeMean > -0.213599
                                                        if (daten.AccelerationMax <= 6.117185) {
                                                            return Aktivitaet.Rennen
                                                        } else { // AccelerationMax > 6.117185
                                                            if (daten.AccelerationMax <= 8.499416) {
                                                                if (daten.AccelerationMax <= 7.924808) {
                                                                    if (daten.GyroscopeMean <= -0.163916) {
                                                                        return Aktivitaet.Stehen
                                                                    } else { // GyroscopeMean > -0.163916
                                                                        if (daten.GyroscopeMin <= -0.465327) {
                                                                            return Aktivitaet.Laufen
                                                                        } else { // GyroscopeMin > -0.465327
                                                                            if (daten.GyroscopeStdDev <= 0.09166) {
                                                                                return Aktivitaet.Laufen
                                                                            } else {
                                                                                return Aktivitaet.Stehen
                                                                            }
                                                                        }
                                                                    }
                                                                } else { // AccelerationMax > 7.924808
                                                                    return Aktivitaet.Stehen
                                                                }
                                                            } else { // AccelerationMax > 8.499416
                                                                if (daten.AccelerationMin <= -0.814029) {
                                                                    if (daten.GyroscopeMean <= 0.094073) {
                                                                        return Aktivitaet.Rennen
                                                                    } else {
                                                                        return Aktivitaet.Laufen
                                                                    }
                                                                } else { // AccelerationMin > -0.814029
                                                                    return Aktivitaet.Laufen
                                                                }
                                                            }
                                                        }
                                                    }
                                                } else { // GyroscopeMax > 1.096045
                                                    if (daten.GyroscopeMax <= 1.3465) {
                                                        if (daten.AccelerationStdDev <= 5.994978) {
                                                            return Aktivitaet.Laufen
                                                        } else {
                                                            return Aktivitaet.Stehen
                                                        }
                                                    } else { // GyroscopeMax > 1.3465
                                                        return Aktivitaet.Laufen
                                                    }
                                                }
                                            } else { // AccelerationMean > 4.14955
                                                if (daten.AccelerationMax <= 8.561666) {
                                                    if (daten.GyroscopeMin <= -0.66569) {
                                                        return Aktivitaet.Laufen
                                                    } else {
                                                        return Aktivitaet.Rennen
                                                    }
                                                } else { // AccelerationMax > 8.561666
                                                    if (daten.GyroscopeMin <= -0.282831) {
                                                        return Aktivitaet.Stehen
                                                    } else {
                                                        return Aktivitaet.Laufen
                                                    }
                                                }
                                            }
                                        } else { // AccelerationMax > 10.147824
                                            if (daten.GyroscopeMin <= -1.1385) {
                                                return Aktivitaet.Rennen
                                            } else { // GyroscopeMin > -1.1385
                                                if (daten.AccelerationMean <= 6.310318) {
                                                    return Aktivitaet.Laufen
                                                } else {
                                                    return Aktivitaet.Rennen
                                                }
                                            }
                                        }
                                    } else { // GyroscopeMax > 1.737453
                                        if (daten.GyroscopeMin <= 0.797179) {
                                            if (daten.AccelerationMax <= 13.867216) {
                                                if (daten.GyroscopeMin <= -2.290134) {
                                                    if (daten.AccelerationMean <= 2.486378) {
                                                        if (daten.GyroscopeStdDev <= 6.723572) {
                                                            return Aktivitaet.Laufen
                                                        } else {
                                                            return Aktivitaet.Stehen
                                                        }
                                                    } else { // AccelerationMean > 2.486378
                                                        return Aktivitaet.Rennen
                                                    }
                                                } else { // GyroscopeMin > -2.290134
                                                    return Aktivitaet.Rennen
                                                }
                                            } else { // AccelerationMax > 13.867216
                                                return Aktivitaet.Stehen
                                            }
                                        } else { // GyroscopeMin > 0.797179
                                            return Aktivitaet.Laufen
                                        }
                                    }
                                }
                            } else { // AccelerationStdDev > 64.68753
                                if (daten.GyroscopeStdDev <= 2.414417) {
                                    if (daten.AccelerationMax <= 13.994109) {
                                        if (daten.AccelerationMean <= 3.183889) {
                                            return Aktivitaet.Laufen
                                        } else {
                                            return Aktivitaet.Rennen
                                        }
                                    } else { // AccelerationMax > 13.994109
                                        return Aktivitaet.Laufen
                                    }
                                } else { // GyroscopeStdDev > 2.414417
                                    if (daten.GyroscopeMax <= 3.720017) {
                                        return Aktivitaet.Rennen
                                    } else { // GyroscopeMax > 3.720017
                                        if (daten.AccelerationMean <= 3.237759) {
                                            return Aktivitaet.Rennen
                                        } else {
                                            return Aktivitaet.Laufen
                                        }
                                    }
                                }
                            }
                        }
                    } else { // GyroscopeMax > 4.508185
                        if (daten.GyroscopeStdDev <= 5.161704) {
                            if (daten.GyroscopeMean <= 3.189124) {
                                return Aktivitaet.Laufen
                            } else {
                                return Aktivitaet.Rennen
                            }
                        } else { // GyroscopeStdDev > 5.161704
                            if (daten.AccelerationMin <= -12.057199) {
                                return Aktivitaet.Rennen
                            } else { // AccelerationMin > -12.057199
                                if (daten.AccelerationMin <= -6.998251) {
                                    if (daten.AccelerationMin <= -8.907627) {
                                        if (daten.AccelerationStdDev <= 28.346554) {
                                            return Aktivitaet.Rennen
                                        } else {
                                            return Aktivitaet.Laufen
                                        }
                                    } else { // AccelerationMin > -8.907627
                                        return Aktivitaet.Laufen
                                    }
                                } else { // AccelerationMin > -6.998251
                                    return Aktivitaet.Rennen
                                }
                            }
                        }
                    }
                }
            }
        }
        // Dieser Teil sollte idealerweise nicht erreicht werden, wenn der Baum vollständig ist.
        return Aktivitaet.ShowHistory
    }
}


