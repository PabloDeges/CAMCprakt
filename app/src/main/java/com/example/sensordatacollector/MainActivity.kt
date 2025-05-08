package com.example.sensordatacollector

import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.sensordatacollector.databinding.ActivityMainBinding
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorManager: SensorManager
    private var gyroscope: Sensor? = null
    private var accelerometer: Sensor? = null
    private var gravitySensor: Sensor? = null
    private var proximitySensor: Sensor? = null
    private var lastProximityUpdateTime = 0L

    private var currentSamplingRateUs: Int = SensorManager.SENSOR_DELAY_NORMAL
    private var isCollecting = false
    private var fileOutputStream: FileOutputStream? = null
    private val dataBuffer = StringBuilder()
    private var lastWriteTime = 0L
    private val WRITE_BUFFER_INTERVAL_MS = 1000L

    private var sensorHandlerThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    private var lastGyroUpdateTime = 0L
    private var lastAccelUpdateTime = 0L

    private lateinit var accelerationChart: LineChart
    private var lastAccelChartUpdateTime = 0L
    private val MAX_ACCEL_CHART_ENTRIES = 200
    private var accelStartTimeNanos: Long = 0

    // Graph für Neigung
    private lateinit var tiltChart: LineChart
    private var lastTiltChartUpdateTime = 0L
    private val MAX_TILT_CHART_ENTRIES = 200
    private var tiltStartTimeNanos: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        setupSamplingRateButtons()
        setupToggleButton()
        setupAccelerationChart()
        setupTiltChart()

        // Prüfen ob Sensoren vorhanden sind
        binding.checkboxGyroscope.isEnabled = (gyroscope != null)
        binding.checkboxAccelerometer.isEnabled = (accelerometer != null)
        binding.checkboxProximity.isEnabled = (proximitySensor != null)

        if (gyroscope == null) binding.checkboxGyroscope.text = "Gyroscope (Not available)"
        if (accelerometer == null) binding.checkboxAccelerometer.text = "Accelerometer (Not available)"
        if (gravitySensor == null) Log.w("SensorCollector", "Gravity sensor not available, tilt graph might not work.")
        if (proximitySensor == null) binding.checkboxProximity.text = "Proximity (Not available)"
    }

    private fun setupSamplingRateButtons() {
        binding.button1s.setOnClickListener { setSamplingRate(1000000) }
        binding.button3s.setOnClickListener { setSamplingRate(3000000) }
        binding.button5s.setOnClickListener { setSamplingRate(5000000) }
        binding.button10s.setOnClickListener { setSamplingRate(10000000) }
        setSamplingRate(1000000)
        updateButtonStates(1000000)
    }

    private fun updateButtonStates(rateUs: Int) {
        binding.button1s.isSelected = rateUs == 1000000
        binding.button3s.isSelected = rateUs == 3000000
        binding.button5s.isSelected = rateUs == 5000000
        binding.button10s.isSelected = rateUs == 10000000
    }

    private fun setSamplingRate(rateUs: Int) {
        currentSamplingRateUs = rateUs
        Log.i("SensorCollector", "Sampling rate set to: ${rateUs / 1000000.0} seconds")
        updateButtonStates(rateUs)
        if (isCollecting) {
            stopCollecting()
            startCollecting()
        }
    }

    private fun setupToggleButton() {
        binding.toggleButtonCollect.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startCollecting()
            } else {
                stopCollecting()
            }

            // Sensoren Sperren/Entsperren
            binding.checkboxGyroscope.isEnabled = !isChecked && (gyroscope != null)
            binding.checkboxAccelerometer.isEnabled = !isChecked && (accelerometer != null)
            binding.checkboxProximity.isEnabled = !isChecked && (proximitySensor != null)
            binding.button1s.isEnabled = !isChecked
            binding.button3s.isEnabled = !isChecked
            binding.button5s.isEnabled = !isChecked
            binding.button10s.isEnabled = !isChecked
        }
    }

    // Acc-Graph
    private fun setupAccelerationChart() {
        accelerationChart = binding.accelerationChart
        accelerationChart.description.isEnabled = true
        accelerationChart.description.text = "Live Acceleration Data"
        accelerationChart.setTouchEnabled(true)
        accelerationChart.isDragEnabled = true
        accelerationChart.setScaleEnabled(true)
        accelerationChart.setPinchZoom(true)
        accelerationChart.setBackgroundColor(Color.WHITE)

        val lineData = LineData()
        accelerationChart.data = lineData
        accelerationChart.invalidate()
    }

    private fun clearAccelerationChartData() {
        accelerationChart.data?.clearValues()
        accelerationChart.notifyDataSetChanged()
        accelerationChart.invalidate()
        setupAccelerationChartDataSets()
    }

    private fun setupAccelerationChartDataSets() {
        val dataSets = ArrayList<ILineDataSet>()
        if (binding.checkboxAccelerometer.isChecked) {
            dataSets.add(createDataSet("Accel X", Color.MAGENTA))
            dataSets.add(createDataSet("Accel Y", Color.CYAN))
            dataSets.add(createDataSet("Accel Z", Color.YELLOW))
        }
        accelerationChart.data = LineData(dataSets)
        accelerationChart.invalidate()
    }

    private fun addAccelerationChartEntry(eventTimeNanos: Long, x: Float, y: Float, z: Float) {
        val data = accelerationChart.data ?: return
        val timeSeconds = TimeUnit.NANOSECONDS.toSeconds(eventTimeNanos - accelStartTimeNanos).toFloat()
        data.addEntry(Entry(timeSeconds, x), findDataSetIndex(data, "Accel X"))
        data.addEntry(Entry(timeSeconds, y), findDataSetIndex(data, "Accel Y"))
        data.addEntry(Entry(timeSeconds, z), findDataSetIndex(data, "Accel Z"))
        limitChartEntries(data, MAX_ACCEL_CHART_ENTRIES, accelerationChart)
    }

    // Neigungs-Graph
    private fun setupTiltChart() {
        tiltChart = binding.tiltChart
        tiltChart.description.isEnabled = true
        tiltChart.description.text = "Device Tilt (Around X-axis)"
        tiltChart.setTouchEnabled(true)
        tiltChart.isDragEnabled = true
        tiltChart.setScaleEnabled(true)
        tiltChart.setPinchZoom(true)
        tiltChart.setBackgroundColor(Color.WHITE)

        val lineData = LineData()
        tiltChart.data = lineData
        tiltChart.invalidate()
    }

    private fun clearTiltChartData() {
        tiltChart.data?.clearValues()
        tiltChart.notifyDataSetChanged()
        tiltChart.invalidate()
        setupTiltChartDataSets()
    }

    private fun setupTiltChartDataSets() {
        val dataSets = ArrayList<ILineDataSet>()
        dataSets.add(createDataSet("Tilt Angle", Color.GREEN))
        tiltChart.data = LineData(dataSets)
        tiltChart.invalidate()
    }

    private fun addTiltChartEntry(eventTimeNanos: Long, tiltAngle: Float) {
        val data = tiltChart.data ?: return
        val timeSeconds = TimeUnit.NANOSECONDS.toSeconds(eventTimeNanos - tiltStartTimeNanos).toFloat()
        data.addEntry(Entry(timeSeconds, tiltAngle), findDataSetIndex(data, "Tilt Angle"))
        limitChartEntries(data, MAX_TILT_CHART_ENTRIES, tiltChart)
    }

    private fun calculateTiltAngle(event: SensorEvent?): Float {
        if (event?.sensor?.type == Sensor.TYPE_GRAVITY) {
            val gravity = event.values
            // Normiere den Gravitationsvektor
            val norm = Math.sqrt((gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]).toDouble()).toFloat()
            val normalizedGravity = floatArrayOf(gravity[0] / norm, gravity[1] / norm, gravity[2] / norm)

            // Berechne den Rollwinkel
            val rollRadians = Math.atan2(normalizedGravity[1].toDouble(), normalizedGravity[2].toDouble())
            return Math.toDegrees(rollRadians).toFloat()
        }
        return 0f
    }

    private fun createDataSet(label: String, color: Int): LineDataSet {
        val set = LineDataSet(null, label)
        set.axisDependency = com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT
        set.color = color
        set.setDrawCircles(false)
        set.lineWidth = 1.5f
        set.setDrawValues(false)
        return set
    }

    private fun findDataSetIndex(data: LineData, label: String): Int {
        for (i in 0 until data.dataSetCount) {
            if (data.getDataSetByIndex(i).label == label) {
                return i
            }
        }
        Log.e("ChartError", "DataSet not found for label: $label")
        return -1
    }

    private fun limitChartEntries(data: LineData, maxEntries: Int, chart: LineChart) {
        for (set in data.dataSets) {
            if (set.entryCount > maxEntries) {
                set.removeFirst()
            }
        }
        chart.xAxis.axisMinimum = data.xMin
        chart.xAxis.axisMaximum = data.xMax
    }

    private fun startCollecting() {
        if (isCollecting) return

        sensorHandlerThread = HandlerThread("SensorThread").apply { start() }
        sensorHandler = Handler(sensorHandlerThread!!.looper)

        isCollecting = true
        binding.tvStatus.text = "Status: Collecting..."
        dataBuffer.clear()
        lastWriteTime = System.currentTimeMillis()

        accelStartTimeNanos = SystemClock.elapsedRealtimeNanos()
        clearAccelerationChartData()

        tiltStartTimeNanos = SystemClock.elapsedRealtimeNanos()
        clearTiltChartData()

        if (!prepareFileOutputStream()) {
            stopCollecting()
            return
        }
        writeHeaderToFile()

        //Listener für Sensoren
        sensorHandler?.post {
            if (binding.checkboxGyroscope.isChecked && gyroscope != null) {
                sensorManager.registerListener(this@MainActivity, gyroscope, currentSamplingRateUs, sensorHandler)
            }
            if (binding.checkboxAccelerometer.isChecked && accelerometer != null) {
                sensorManager.registerListener(this@MainActivity, accelerometer, currentSamplingRateUs, sensorHandler)
            }
            if (gravitySensor != null) {
                sensorManager.registerListener(this@MainActivity, gravitySensor, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
            }
            if (binding.checkboxProximity.isChecked && proximitySensor != null) {
                sensorManager.registerListener(this@MainActivity, proximitySensor, currentSamplingRateUs, sensorHandler)
            }
        }
        Log.i("SensorCollector", "Started collecting with rate: ${currentSamplingRateUs / 1000000.0} s")
    }

    private fun stopCollecting() {
        if (!isCollecting) return

        sensorManager.unregisterListener(this@MainActivity)

        sensorHandlerThread?.quitSafely()
        try {
            sensorHandlerThread?.join()
            sensorHandlerThread = null
            sensorHandler = null
        } catch (e: InterruptedException) {
            Log.e("SensorCollector", "Error joining sensor thread", e)
        }

        isCollecting = false
        binding.tvStatus.text = "Status: Stopped"

        flushBufferToFile()
        closeFileOutputStream()
        Log.i("SensorCollector", "Stopped collecting.")

        binding.checkboxGyroscope.isEnabled = (gyroscope != null)
        binding.checkboxAccelerometer.isEnabled = (accelerometer != null)
        binding.checkboxProximity.isEnabled = (proximitySensor != null)
        binding.button1s.isEnabled = true
        binding.button3s.isEnabled = true
        binding.button5s.isEnabled = true
        binding.button10s.isEnabled = true
    }

    private fun prepareFileOutputStream(): Boolean {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "sensor_data_$timestamp.csv"

            val storageDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val appDir = File(storageDir, "SensorDataCollector")
            if (!appDir.exists()) {
                appDir.mkdirs()
            }
            val file = File(appDir, fileName)
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
        val header = "Timestamp_ms,SystemTime_ns,Sensor,X,Y,Z,Value\n"
        try {
            fileOutputStream?.write(header.toByteArray())
        } catch (e: IOException) {
            Log.e("SensorCollector", "Error writing header to file", e)
        }
    }

    private fun writeToFile(data: String) {
        if (fileOutputStream == null) return
        try {
            fileOutputStream?.write(data.toByteArray())
        } catch (e: IOException) {
            Log.e("SensorCollector", "Error writing to file", e)
        }
    }

    private fun flushBufferToFile() {
        if (dataBuffer.isNotEmpty()) {
            writeToFile(dataBuffer.toString())
            dataBuffer.clear()
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

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isCollecting) return

        val currentTimeMs = System.currentTimeMillis()
        val eventTimeNanos = SystemClock.elapsedRealtimeNanos()
        val timestampMs = TimeUnit.NANOSECONDS.toMillis(eventTimeNanos)
        val samplingIntervalMs = currentSamplingRateUs / 1000L

        var sensorType: String = ""
        val values = event.values
        var x: Float = Float.NaN
        var y: Float = Float.NaN
        var z: Float = Float.NaN
        var value: Float = Float.NaN

        var updateAccelChart = false
        var updateTiltChart = false

        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                sensorType = "GYRO"
                x = values[0]
                y = values[1]
                z = values[2]
                if (currentTimeMs - lastGyroUpdateTime > samplingIntervalMs) {
                    lastGyroUpdateTime = currentTimeMs
                    runOnUiThread {
                        binding.tvLiveData.text = updateLiveDataText("Gyro", x, y, z, Float.NaN)
                    }
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                sensorType = "ACCEL"
                x = values[0]
                y = values[1]
                z = values[2]
                if (currentTimeMs - lastAccelUpdateTime > samplingIntervalMs) {
                    lastAccelUpdateTime = currentTimeMs
                    runOnUiThread {
                        binding.tvLiveData.text = updateLiveDataText("Accel", x, y, z, Float.NaN)
                    }
                }
                if (binding.checkboxAccelerometer.isChecked) {
                    addAccelerationChartEntry(eventTimeNanos, x, y, z)
                    updateAccelChart = true
                }
            }
            Sensor.TYPE_PROXIMITY -> {
                sensorType = "PROXIMITY"
                value = values[0]
                if (currentTimeMs - lastProximityUpdateTime > samplingIntervalMs) {
                    lastProximityUpdateTime = currentTimeMs
                    runOnUiThread {
                        binding.tvLiveData.text = updateLiveDataText("Proximity", Float.NaN, Float.NaN, Float.NaN, value)
                    }
                }
            }
            Sensor.TYPE_GRAVITY -> {
                val tiltAngle = calculateTiltAngle(event)
                if (currentTimeMs - lastTiltChartUpdateTime > samplingIntervalMs) {
                    lastTiltChartUpdateTime = currentTimeMs
                    runOnUiThread {
                        addTiltChartEntry(eventTimeNanos, tiltAngle)
                        tiltChart.notifyDataSetChanged()
                        tiltChart.invalidate()
                        tiltChart.moveViewToX(tiltChart.data.entryCount.toFloat())
                    }
                }
            }
            else -> return
        }

        val dataLine = "$timestampMs,$eventTimeNanos,$sensorType,${if (x.isNaN()) "" else "%.6f".format(x)},${if (y.isNaN()) "" else "%.6f".format(y)},${if (z.isNaN()) "" else "%.6f".format(z)},${if (value.isNaN()) "" else "%.6f".format(value)}\n"
        dataBuffer.append(dataLine)

        if (currentTimeMs - lastWriteTime > WRITE_BUFFER_INTERVAL_MS) {
            flushBufferToFile()
            lastWriteTime = currentTimeMs
        }

        if (updateAccelChart && currentTimeMs - lastAccelChartUpdateTime > samplingIntervalMs) {
            lastAccelChartUpdateTime = currentTimeMs
            runOnUiThread {
                accelerationChart.notifyDataSetChanged()
                accelerationChart.invalidate()
                accelerationChart.moveViewToX(accelerationChart.data.entryCount.toFloat())
            }
        }
    }

    private fun updateLiveDataText(sensor: String, x: Float, y: Float, z: Float, value: Float): String {
        val currentText = binding.tvLiveData.text.toString().split("\n")
        var gyroLine = currentText.getOrNull(0) ?: "Gyro: ..."
        var accelLine = currentText.getOrNull(1) ?: "Accel: ..."
        var proximityLine = currentText.getOrNull(2) ?: "Proximity: ..."

        val formattedXYZ = "[X: %.2f, Y: %.2f, Z: %.2f]".format(x, y, z)
        val formattedValue = "[Value: %.2f]".format(value)

        when (sensor) {
            "Gyro" -> gyroLine = "Gyro: $formattedXYZ"
            "Accel" -> accelLine = "Accel: $formattedXYZ"
            "Proximity" -> proximityLine = "Proximity: $formattedValue"
        }
        return "$gyroLine\n$accelLine\n$proximityLine"
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val accuracyStr = when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "UNRELIABLE"
            else -> "UNKNOWN"
        }
        Log.i("SensorCollector", "Accuracy changed for ${sensor?.name}: $accuracyStr")
    }

    override fun onPause() {
        super.onPause()
        if (isCollecting) {
            binding.toggleButtonCollect.isChecked = false
            Log.w("SensorCollector", "App paused, stopping collection.")
        }
    }

    override fun onDestroy() {
        if (isCollecting) {
            stopCollecting()
        }
        sensorHandlerThread?.quitSafely()
        super.onDestroy()
    }
}