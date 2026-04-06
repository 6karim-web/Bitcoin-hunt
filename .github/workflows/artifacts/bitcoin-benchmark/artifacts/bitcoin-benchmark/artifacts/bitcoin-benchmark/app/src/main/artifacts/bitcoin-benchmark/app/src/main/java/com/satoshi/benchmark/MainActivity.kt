package com.satoshi.benchmark

import android.app.AlertDialog
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.params.MainNetParams
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.NumberFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {

    private lateinit var editTargetAddress: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvKeysPerSec: TextView
    private lateinit var tvTotalKeys: TextView
    private lateinit var tvConsole: TextView
    private lateinit var scrollConsole: ScrollView

    private val isRunning = AtomicBoolean(false)
    private val totalKeys = AtomicLong(0)
    private val keysThisSecond = AtomicLong(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val numberFormat = NumberFormat.getNumberInstance()

    private val networkParams by lazy { MainNetParams.get() }

    private val targetHashSet = HashSet<String>()
    private var benchmarkThread: Thread? = null

    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            if (isRunning.get()) {
                val kps = keysThisSecond.getAndSet(0)
                val total = totalKeys.get()
                tvKeysPerSec.text = numberFormat.format(kps)
                tvTotalKeys.text = numberFormat.format(total)
                appendConsole("[STAT] ${numberFormat.format(kps)} keys/sec | total: ${numberFormat.format(total)}")
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_main)

            editTargetAddress = findViewById(R.id.editTargetAddress)
            btnStart         = findViewById(R.id.btnStart)
            btnStop          = findViewById(R.id.btnStop)
            tvKeysPerSec     = findViewById(R.id.tvKeysPerSec)
            tvTotalKeys      = findViewById(R.id.tvTotalKeys)
            tvConsole        = findViewById(R.id.tvConsole)
            scrollConsole    = findViewById(R.id.scrollConsole)

            btnStart.setOnClickListener { startBenchmark() }
            btnStop.setOnClickListener  { stopBenchmark() }

            appendConsole("[INFO] App started successfully")
            appendConsole("[INFO] BitcoinJ 0.16.2 | Mainnet | P2PKH")
            loadTargetsFromAssets()
            appendConsole("[INFO] Ready. Press START BENCHMARK.")

        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Startup Error")
                .setMessage("${e.javaClass.simpleName}: ${e.message}")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun loadTargetsFromAssets() {
        try {
            val reader = BufferedReader(InputStreamReader(assets.open("targets.txt")))
            var count = 0
            reader.useLines { lines ->
                lines.forEach { raw ->
                    val line = raw.trim()
                    if (line.isNotEmpty() && !line.startsWith("#")) {
                        targetHashSet.add(line)
                        count++
                    }
                }
            }
            appendConsole("[TARGETS] Loaded $count address(es) from targets.txt")
        } catch (e: Exception) {
            appendConsole("[WARN] targets.txt not found or unreadable: ${e.message}")
        }
    }

    private fun startBenchmark() {
        if (isRunning.get()) return

        try {
            networkParams
        } catch (e: Exception) {
            appendConsole("[ERROR] BitcoinJ init failed: ${e.message}")
            return
        }

        val manualTarget = editTargetAddress.text.toString().trim()
        isRunning.set(true)
        totalKeys.set(0)
        keysThisSecond.set(0)

        btnStart.isEnabled = false
        btnStop.isEnabled  = true
        tvKeysPerSec.text  = "0"
        tvTotalKeys.text   = "0"

        appendConsole("----------------------------------------")
        appendConsole("[START] Benchmark running")
        if (manualTarget.isNotEmpty()) appendConsole("[TARGET] Manual: $manualTarget")
        val total = targetHashSet.size + if (manualTarget.isNotEmpty()) 1 else 0
        appendConsole("[TARGET] Checking $total address(es)")
        appendConsole("----------------------------------------")

        mainHandler.postDelayed(uiUpdateRunnable, 1000)

        benchmarkThread = Thread { runBenchmarkLoop(manualTarget) }.also {
            it.name = "ECKey-Benchmark"
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopBenchmark() {
        isRunning.set(false)
        mainHandler.removeCallbacks(uiUpdateRunnable)
        benchmarkThread?.interrupt()
        mainHandler.post {
            btnStart.isEnabled = true
            btnStop.isEnabled  = false
            appendConsole("----------------------------------------")
            appendConsole("[STOP] Total keys: ${numberFormat.format(totalKeys.get())}")
            appendConsole("----------------------------------------")
        }
    }

    private fun runBenchmarkLoop(manualTarget: String) {
        val hasManual   = manualTarget.isNotEmpty()
        val hasHashSet  = targetHashSet.isNotEmpty()
        var localCount  = 0L
        val logInterval = 5000L

        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                val key     = ECKey()
                val address = LegacyAddress.fromKey(networkParams, key).toString()

                localCount++
                keysThisSecond.incrementAndGet()
                totalKeys.incrementAndGet()

                if (localCount % logInterval == 0L) {
                    val wif     = key.getPrivateKeyAsWiF(networkParams)
                    val snippet = address.take(12) + "..." + address.takeLast(6)
                    mainHandler.post {
                        appendConsole("[SAMPLE] $snippet | wif=${wif.take(8)}...")
                    }
                }

                val inHashSet     = hasHashSet && targetHashSet.contains(address)
                val matchesManual = hasManual  && address == manualTarget

                if (inHashSet || matchesManual) {
                    val wif    = key.getPrivateKeyAsWiF(networkParams)
                    val hex    = key.privateKeyAsHex
                    val source = when {
                        inHashSet && matchesManual -> "HashSet + Manual"
                        inHashSet                  -> "targets.txt"
                        else                       -> "Manual Target"
                    }
                    isRunning.set(false)
                    mainHandler.post { handleMatch(address, wif, hex, source) }
                    return
                }

            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                mainHandler.post { appendConsole("[ERROR] ${e.javaClass.simpleName}: ${e.message}") }
            }
        }
    }

    private fun handleMatch(address: String, wif: String, hex: String, source: String) {
        appendConsole("========================================")
        appendConsole("[MATCH] Source  : $source")
        appendConsole("[MATCH] Address : $address")
        appendConsole("[MATCH] WIF     : $wif")
        appendConsole("[MATCH] Hex     : $hex")
        appendConsole("========================================")

        mainHandler.removeCallbacks(uiUpdateRunnable)
        btnStart.isEnabled = true
        btnStop.isEnabled  = false

        try {
            val uri      = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(applicationContext, uri)
            ringtone?.play()
        } catch (_: Exception) {}

        AlertDialog.Builder(this)
            .setTitle("Match Found")
            .setMessage(
                "Source: $source\n\n" +
                "Address:\n$address\n\n" +
                "WIF Key:\n$wif\n\n" +
                "Hex Key:\n$hex\n\n" +
                "Keys searched: ${numberFormat.format(totalKeys.get())}"
            )
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .setCancelable(false)
            .show()
    }

    private fun appendConsole(line: String) {
        val lines = tvConsole.text.split("\n")
        val trimmed = if (lines.size > 200) lines.takeLast(200).joinToString("\n") else tvConsole.text
        tvConsole.text = "$trimmed\n$line"
        scrollConsole.post { scrollConsole.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        benchmarkThread?.interrupt()
    }
}
