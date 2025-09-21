package com.lavie.randochat.utils

import android.content.Context
import android.util.Base64
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.lavie.randochat.model.Emoji
import com.lavie.randochat.ui.component.MessageComponent
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object CommonUtils {
    //NETWORK
    fun isNetworkError(exception: Exception): Boolean {

        val message = exception.message?.lowercase() ?: ""

        return message.contains("network") ||
                message.contains("timeout") ||
                message.contains("connection") ||
                message.contains("unreachable") ||
                exception is TimeoutCancellationException
    }

    // ENCRYPTION
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val IV_LENGTH = 16

    fun generateMessageKey(roomId: String, userId: String): String {
        val input = "$roomId:$userId"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    fun encryptMessage(plainText: String, key: String): String {
        return try {
            val keyBytes = Base64.decode(key, Base64.NO_WRAP)
            val secretKeySpec = SecretKeySpec(keyBytes, "AES")

            val iv = ByteArray(IV_LENGTH)
            SecureRandom().nextBytes(iv)
            val ivParameterSpec = IvParameterSpec(iv)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec)

            val encryptedBytes = cipher.doFinal(plainText.toByteArray())

            val combined = iv + encryptedBytes
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Timber.e("Cannot Encrypt Message: ${e.message}")

            plainText
        }
    }

    fun decryptMessage(encryptedText: String, key: String): String {
        return try {
            val keyBytes = Base64.decode(key, Base64.NO_WRAP)
            val secretKeySpec = SecretKeySpec(keyBytes, "AES")

            val combined = Base64.decode(encryptedText, Base64.NO_WRAP)

            val iv = combined.sliceArray(0 until IV_LENGTH)
            val encryptedBytes = combined.sliceArray(IV_LENGTH until combined.size)

            val ivParameterSpec = IvParameterSpec(iv)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)

            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes)
        } catch (e: Exception) {
            Timber.e("Cannot Decrypt Message: ${e.message}")

            encryptedText
        }
    }

    //INPUT VALIDATOR
    private val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}$")
    private val PASSWORD_REGEX = Regex("^(?=.*[A-Za-z])(?=.*\\d).{6,}$")

    fun isValidEmail(email: String): Boolean {
        return EMAIL_REGEX.matches(email)
    }

    fun isValidPassword(password: String): Boolean {
        return PASSWORD_REGEX.matches(password)
    }

    //DATE TIME FORMAT
    fun formatToTime(timestamp: Long): String {
        val sdf = SimpleDateFormat(Constants.HH_MM, Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun formatToDateTimeDetailed(timestamp: Long): String {
        val calendar = Calendar.getInstance()
        val messageCalendar = Calendar.getInstance().apply { timeInMillis = timestamp }

        val isToday = calendar.get(Calendar.DAY_OF_YEAR) == messageCalendar.get(Calendar.DAY_OF_YEAR) &&
                calendar.get(Calendar.YEAR) == messageCalendar.get(Calendar.YEAR)

        return when {
            isToday -> {
                val timeFormat = SimpleDateFormat(Constants.HH_MM, Locale.getDefault())
                timeFormat.format(Date(timestamp))
            }

            isThisWeek(timestamp) -> {
                val dayFormat = SimpleDateFormat(Constants.EEEE_HH_MM, Locale.forLanguageTag(Constants.VI))
                dayFormat.format(Date(timestamp))
            }

            isThisYear(timestamp) -> {
                val dateFormat = SimpleDateFormat(Constants.DD_MM_HH_MM, Locale.getDefault())
                dateFormat.format(Date(timestamp))
            }

            else -> {
                val dateFormat = SimpleDateFormat(Constants.DD_MM_YYYY_HH_MM, Locale.getDefault())
                dateFormat.format(Date(timestamp))
            }
        }
    }

    private fun isThisWeek(timestamp: Long): Boolean {
        val calendar = Calendar.getInstance()
        val currentWeek = calendar.get(Calendar.WEEK_OF_YEAR)
        val currentYear = calendar.get(Calendar.YEAR)

        calendar.timeInMillis = timestamp
        val messageWeek = calendar.get(Calendar.WEEK_OF_YEAR)
        val messageYear = calendar.get(Calendar.YEAR)

        return currentWeek == messageWeek && currentYear == messageYear
    }

    private fun isThisYear(timestamp: Long): Boolean {
        val calendar = Calendar.getInstance()
        val currentYear = calendar.get(Calendar.YEAR)

        calendar.timeInMillis = timestamp
        val messageYear = calendar.get(Calendar.YEAR)

        return currentYear == messageYear
    }

    //CHECK APP IS IN FOREGROUND
    fun isAppInForeground(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false
        val packageName = context.packageName
        for (appProcess in appProcesses) {
            if (appProcess.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                && appProcess.processName == packageName
            ) {
                return true
            }
        }

        return false
    }

    //EMOJI
    fun parseMessageWithEmojis(content: String, emojiList: List<Emoji>): List<MessageComponent> {
        val components = buildList {
            val emojiRegex = "\\[:([^]]+):]".toRegex()
            var lastIndex = 0
            emojiRegex.findAll(content).forEach { match ->
                if (match.range.first > lastIndex) {
                    add(MessageComponent.TextComponent(content.substring(lastIndex, match.range.first)))
                }
                val name = match.groupValues[1]
                val emoji = emojiList.find { it.name == name }
                if (emoji != null) {
                    add(MessageComponent.EmojiComponent(emoji))
                } else {
                    add(MessageComponent.TextComponent(match.value))
                }
                lastIndex = match.range.last + 1
            }
            if (lastIndex < content.length) {
                add(MessageComponent.TextComponent(content.substring(lastIndex)))
            }
        }
        return components
    }

    //LOCATION
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    suspend fun getCurrentLocation(context: Context): Pair<Double, Double>? = suspendCancellableCoroutine { cont ->
        val fused = LocationServices.getFusedLocationProviderClient(context)
        try {
            fused.lastLocation
                .addOnSuccessListener { loc ->
                    if (loc != null) cont.resume(loc.latitude to loc.longitude) else {
                        val req = CurrentLocationRequest.Builder()
                            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                            .setMaxUpdateAgeMillis(0).build()
                        fused.getCurrentLocation(req, null)
                            .addOnSuccessListener { loc2 ->
                                if (loc2 != null) cont.resume(loc2.latitude to loc2.longitude)
                                else cont.resume(null)
                            }
                            .addOnFailureListener { cont.resume(null) }
                    }
                }
                .addOnFailureListener { cont.resume(null) }
        } catch (_: Exception) { cont.resume(null) }
    }
}

