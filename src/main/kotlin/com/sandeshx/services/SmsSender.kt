package com.sandeshx.services

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

interface SmsSender {
    fun send(phoneNumber: String, code: String)
}

/** Dev-only: logs the code instead of sending a real SMS. Used automatically when
 *  Twilio env vars aren't set, so local/dev runs never need real credentials. */
class LogSmsSender : SmsSender {
    override fun send(phoneNumber: String, code: String) {
        println("[DEV SMS] OTP for $phoneNumber -> $code (wire up a real provider before shipping)")
    }
}

/** Sends the OTP as a real SMS via Twilio's REST API. Needs three env vars on the
 *  server: ACCOUNT_SID, AUTH_TOKEN (both already visible in your Render env — those
 *  are Twilio's, not a coincidence), and TWILIO_FROM_NUMBER — the Twilio phone number
 *  messages are sent *from* (Twilio Console → Phone Numbers → your active number, in
 *  E.164 format e.g. +15551234567). Uses the JDK's built-in HTTP client, same as
 *  AiAssistService — no extra dependency needed. */
class TwilioSmsSender(
    private val accountSid: String,
    private val authToken: String,
    private val fromNumber: String
) : SmsSender {

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    override fun send(phoneNumber: String, code: String) {
        val body = "This is your SandeshX verification code: $code. It expires in 5 minutes."
        val form = "To=${encode(phoneNumber)}&From=${encode(fromNumber)}&Body=${encode(body)}"
        val basicAuth = Base64.getEncoder().encodeToString("$accountSid:$authToken".toByteArray())

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.twilio.com/2010-04-01/Accounts/$accountSid/Messages.json"))
            .header("Authorization", "Basic $basicAuth")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()

        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                // Don't throw here: the OTP is already generated and stored in Redis by the time
                // this runs (see AuthService.sendOtp), so surfacing a 500 to the client would be
                // misleading — the code is valid, it just may not have arrived by SMS. Log loudly
                // instead so it's visible in Render's logs without breaking the request.
                println("[TwilioSmsSender] Twilio API error ${response.statusCode()}: ${response.body()}")
            }
        } catch (e: Exception) {
            println("[TwilioSmsSender] Failed to reach Twilio: ${e.message}")
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}
