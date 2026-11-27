package com.victorious.sms.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import java.security.MessageDigest

data class SmsSim(val id: Int, val label: String)

class SmsSubscriptions(private val context: Context) {
    private val prefs = context.getSharedPreferences("sms_subscriptions", Context.MODE_PRIVATE)

    fun canRead(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** Null means the available SIMs could not be read. An empty list means the phone reported none. */
    fun active(): List<SmsSim>? {
        if (!canRead()) return null
        return runCatching {
            val subscriptions = context.getSystemService(SubscriptionManager::class.java) ?: return null
            subscriptions.activeSubscriptionInfoList.orEmpty().map { info ->
                val slot = if (info.simSlotIndex >= 0) "SIM ${info.simSlotIndex + 1}" else "SIM"
                val name = info.displayName?.toString()?.trim().orEmpty()
                SmsSim(info.subscriptionId, if (name.isBlank() || name == slot) slot else "$slot · $name")
            }
        }.getOrNull()
    }

    fun preferred(address: String): Int? = prefs.getInt(key(address), -1).takeIf { it >= 0 }

    fun setPreferred(address: String, id: Int?) {
        val key = key(address)
        if (id == null) prefs.edit().remove(key).apply()
        else prefs.edit().putInt(key, id).apply()
    }

    fun resolve(id: Int?): SmsSim = select(id, defaultId(), active())

    private fun key(address: String): String =
        MessageDigest.getInstance("SHA-256").digest(CARRIER_ADDRESS_FORMAT.replace(address.trim(), "").toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        internal val CARRIER_ADDRESS_FORMAT = Regex("[\\s().-]")

        private fun defaultId(): Int =
            runCatching { SubscriptionManager.getDefaultSmsSubscriptionId() }.getOrDefault(-1)

        internal fun select(id: Int?, default: Int, active: List<SmsSim>?): SmsSim {
            if (id != null) {
                require(active != null) { "Allow SIM access to use the selected SIM, or choose System default." }
                return requireNotNull(active.firstOrNull { it.id == id }) { "The selected SIM is inactive. Choose another SIM." }
            }
            if (active != null) {
                require(active.isNotEmpty()) { "There is no active SMS SIM." }
                return active.firstOrNull { it.id == default }
                    ?: active.singleOrNull()
                    ?: throw IllegalArgumentException("Choose a SIM or set a default SMS SIM in Android settings.")
            }
            require(default in 0 until Int.MAX_VALUE) { "Set a default SMS SIM in Android settings." }
            return SmsSim(default, "Default SIM")
        }
    }
}
