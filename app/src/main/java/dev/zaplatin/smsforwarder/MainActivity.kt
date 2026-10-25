package dev.zaplatin.smsforwarder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var countsText: TextView
    private lateinit var requestPermissionButton: Button
    private lateinit var resendButton: Button
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var simInfoText: TextView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val smsGranted = result[Manifest.permission.RECEIVE_SMS] == true
            val simGranted = result[Manifest.permission.READ_PHONE_STATE] == true ||
                result[Manifest.permission.READ_PHONE_NUMBERS] == true
            OperationLogger.d(TAG, "Permission result -> sms=$smsGranted sim=$simGranted")
            updateStatus()
        }

    private val requiredPermissions = buildList {
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_PHONE_NUMBERS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        countsText = findViewById(R.id.countsText)
        requestPermissionButton = findViewById(R.id.requestPermissionButton)
        resendButton = findViewById(R.id.resendButton)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        simInfoText = findViewById(R.id.simInfoText)

        lifecycleScope.launch {
            OperationLogger.logs.collectLatest { entries ->
                logText.text = entries.joinToString(separator = "\n")
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
        OperationLogger.d(TAG, "MainActivity created")

        requestPermissionButton.setOnClickListener {
            OperationLogger.d(TAG, "Requesting runtime permissions")
            permissionLauncher.launch(requiredPermissions)
        }
        resendButton.setOnClickListener {
            OperationLogger.d(TAG, "Manual resend trigger pressed")
            enqueueResendAll()
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val hasSmsPermission = hasSmsPermission()
        val hasSimPermission = hasSimPermission()
        val smsStatus = if (hasSmsPermission) getString(R.string.permission_yes) else getString(R.string.permission_no)
        val simStatus = if (hasSimPermission) getString(R.string.permission_yes) else getString(R.string.permission_no)
        statusText.text = getString(R.string.permissions_summary, smsStatus, simStatus)
        OperationLogger.d(TAG, "updateStatus -> sms=$hasSmsPermission sim=$hasSimPermission")
        requestPermissionButton.isEnabled = requiredPermissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        resendButton.isEnabled = hasSmsPermission

        if (hasSmsPermission) {
            ForwarderService.start(this)
            requestIgnoreBatteryOptimizationsIfNeeded()
            enqueueResendAll()
        }
        refreshCounts()
        refreshSimInfo()
    }

    private fun hasSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasSimPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        val pkg = packageName
        if (!pm.isIgnoringBatteryOptimizations(pkg)) {
            OperationLogger.d(TAG, "Requesting ignore battery optimizations")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$pkg")
            }
            startActivity(intent)
        }
    }

    private fun enqueueResendAll() {
        val work = OneTimeWorkRequestBuilder<SmsForwardWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        OperationLogger.d(TAG, "Enqueuing resend worker: ${work.id}")
        WorkManager.getInstance(this).enqueue(work)
    }

    private fun refreshCounts() {
        lifecycleScope.launch {
            val dao = AppDatabase.getInstance(applicationContext).smsDao()
            val (total, pending) = withContext(Dispatchers.IO) {
                dao.countAll() to dao.countNotSent()
            }
            countsText.text = getString(R.string.sms_counts, total, pending)
        }
    }

    private fun refreshSimInfo() {
        if (!hasSimPermission()) {
            simInfoText.text = getString(R.string.sim_info_permission_needed)
            return
        }
        lifecycleScope.launch {
            val sims = withContext(Dispatchers.IO) { SimInfoReader.read(applicationContext) }
            if (sims.isEmpty()) {
                simInfoText.text = getString(R.string.sim_info_empty)
            } else {
                simInfoText.text = sims.joinToString(separator = "\n") { info ->
                    val number = info.number?.takeIf { it.isNotBlank() } ?: getString(R.string.sim_number_unknown)
                    "${info.label}: $number"
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
