package com.victorious.sms.matrix

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion

/**
 * Stores the Matrix session in EncryptedSharedPreferences (AES-256, key in Android Keystore).
 * One encrypted file per account. The access token is never logged.
 */
class SessionStore(context: Context, accountId: String) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "matrix_session_$accountId",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(s: Session) {
        prefs.edit()
            .putString("accessToken", s.accessToken)
            .putString("refreshToken", s.refreshToken)
            .putString("userId", s.userId)
            .putString("deviceId", s.deviceId)
            .putString("homeserverUrl", s.homeserverUrl)
            .putString("oauthData", s.oauthData)
            .putString("slidingSyncVersion", s.slidingSyncVersion.name)
            .apply()
    }

    fun load(): Session? {
        val token = prefs.getString("accessToken", null) ?: return null
        return Session(
            accessToken = token,
            refreshToken = prefs.getString("refreshToken", null),
            userId = prefs.getString("userId", null) ?: return null,
            deviceId = prefs.getString("deviceId", null) ?: return null,
            homeserverUrl = prefs.getString("homeserverUrl", null) ?: return null,
            oauthData = prefs.getString("oauthData", null),
            slidingSyncVersion = runCatching {
                SlidingSyncVersion.valueOf(prefs.getString("slidingSyncVersion", "NATIVE")!!)
            }.getOrDefault(SlidingSyncVersion.NATIVE),
        )
    }

    fun clear() = prefs.edit().clear().apply()
}
