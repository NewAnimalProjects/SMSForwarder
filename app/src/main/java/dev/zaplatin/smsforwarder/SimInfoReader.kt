package dev.zaplatin.smsforwarder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** Reads active SIM cards. Call off the main thread. */
object SimInfoReader {

    fun hasPhoneStatePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun read(context: Context): List<SimInfo> {
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        val telephonyManager = context.getSystemService(TelephonyManager::class.java)
        val includeNumbers = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_NUMBERS,
        ) == PackageManager.PERMISSION_GRANTED
        val entries = subscriptionManager?.activeSubscriptionInfoList ?: emptyList()
        return entries.mapIndexed { index, info ->
            val label = info.displayName?.toString()?.takeIf { it.isNotBlank() } ?: "SIM ${index + 1}"
            val number = if (includeNumbers) {
                info.number?.takeIf { it.isNotBlank() }
                    ?: telephonyManager?.line1Number?.takeIf { !it.isNullOrBlank() }
            } else {
                null
            }
            SimInfo(label, number)
        }
    }
}
