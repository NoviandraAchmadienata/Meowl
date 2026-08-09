package com.meowl.app.network

import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Lightweight, non-blocking NetworkRelay Helper for Meowl VPS Communication.
 * Handles HTTP REST API uploads, downloads, file listings, remote deletion, and Ping Hati signals.
 */
object NetworkRelay {

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun normalizeHost(host: String): String {
        var h = host.trim()
        if (!h.startsWith("http://") && !h.startsWith("https://")) {
            h = "http://$h"
        }
        if (h.endsWith("/")) {
            h = h.substring(0, h.length - 1)
        }
        return h
    }

    /**
     * Register/Claim ID on VPS server with Device UUID to prevent ID duplication
     */
    fun registerId(vpsHost: String, myId: String, deviceId: String, onResult: (Boolean, String?) -> Unit) {
        Thread {
            try {
                val urlStr = "${normalizeHost(vpsHost)}/register-id"
                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 6000
                    readTimeout = 6000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                val payload = JSONObject().apply {
                    put("id", myId)
                    put("deviceId", deviceId)
                }.toString()

                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val responseText = stream?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()

                val json = if (responseText.isNotEmpty()) JSONObject(responseText) else JSONObject()
                val success = (code == 200) && json.optBoolean("success", false)
                val errorMsg = json.optString("error", if (!success) "Gagal mendaftarkan ID" else null)

                mainHandler.post { onResult(success, if (!success) errorMsg else null) }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post { onResult(false, "Gagal terhubung ke server VPS") }
            }
        }.start()
    }

    /**
     * Send Connection Request to targetId
     */
    fun sendConnectRequest(vpsHost: String, myId: String, targetId: String, onResult: (Boolean) -> Unit) {
        Thread {
            try {
                val urlStr = "${normalizeHost(vpsHost)}/connect-request"
                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = 5000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                val payload = JSONObject().apply {
                    put("fromId", myId)
                    put("targetId", targetId)
                }.toString()

                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()
                mainHandler.post { onResult(code == 200) }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post { onResult(false) }
            }
        }.start()
    }

    /**
     * Check if there is an incoming connection request for myId
     */
    fun checkConnectRequest(vpsHost: String, myId: String, onResult: (Boolean, String?) -> Unit) {
        Thread {
            try {
                val urlStr = "${normalizeHost(vpsHost)}/connect-request/$myId"
                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                val code = conn.responseCode
                if (code == 200) {
                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val json = JSONObject(responseText)
                    val hasRequest = json.optBoolean("hasRequest", false)
                    val fromId = json.optString("fromId", null)
                    mainHandler.post { onResult(hasRequest, fromId) }
                } else {
                    conn.disconnect()
                    mainHandler.post { onResult(false, null) }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(false, null) }
            }
        }.start()
    }

    /**
     * Respond (Accept or Reject) to an incoming connection request
     */
    fun respondConnectRequest(vpsHost: String, myId: String, requesterId: String, accepted: Boolean, onResult: (Boolean) -> Unit) {
        Thread {
            try {
                val urlStr = "${normalizeHost(vpsHost)}/connect-response"
                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = 5000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                val payload = JSONObject().apply {
                    put("myId", myId)
                    put("requesterId", requesterId)
                    put("accepted", accepted)
                }.toString()

                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()
                mainHandler.post { onResult(code == 200) }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post { onResult(false) }
            }
        }.start()
    }

    /**
     * Check if requester's connect request was accepted by target
     */
    fun checkConnectStatus(vpsHost: String, myId: String, onResult: (Boolean, String?) -> Unit) {
        Thread {
            try {
                val urlStr = "${normalizeHost(vpsHost)}/connect-status/$myId"
                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                val code = conn.responseCode
                if (code == 200) {
                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val json = JSONObject(responseText)
                    val connected = json.optBoolean("connected", false)
                    val partnerId = json.optString("partnerId", null)
                    mainHandler.post { onResult(connected, partnerId) }
                } else {
                    conn.disconnect()
                    mainHandler.post { onResult(false, null) }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(false, null) }
            }
        }.start()
    }

    /**
     * Send Ping Hati Signal to Partner (target_id) via VPS REST API
     */
    fun sendPing(vpsHost: String, targetId: String, onResult: (Boolean) -> Unit) {
        Thread {
            try {
                val urlStr = "${normalizeHost(vpsHost)}/ping/$targetId"
                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = 5000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write("{}".toByteArray()) }
                val code = conn.responseCode
                conn.disconnect()

                mainHandler.post { onResult(code == 200) }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post { onResult(false) }
            }
        }.start()
    }

    /**
     * Check if Partner sent a Ping Hati Signal to me (my_id)
     */
    fun checkPing(vpsHost: String, myId: String, onResult: (Boolean) -> Unit) {
        Thread {
            try {
                val urlStr = "${normalizeHost(vpsHost)}/ping/$myId"
                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                val code = conn.responseCode
                if (code == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val hasPing = json.optBoolean("ping", false)
                    conn.disconnect()
                    mainHandler.post { onResult(hasPing) }
                } else {
                    conn.disconnect()
                    mainHandler.post { onResult(false) }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(false) }
            }
        }.start()
    }

    /**
     * Upload Voicemail WAV File to VPS for target_id
     */
    fun uploadAudioFile(vpsHost: String, targetId: String, audioFile: File, onResult: (Boolean) -> Unit) {
        Thread {
            try {
                if (!audioFile.exists()) {
                    mainHandler.post { onResult(false) }
                    return@Thread
                }

                val urlStr = "${normalizeHost(vpsHost)}/upload/$targetId"
                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 10000
                    doOutput = true
                    setRequestProperty("Content-Type", "audio/wav")
                }

                FileInputStream(audioFile).use { input ->
                    conn.outputStream.use { output ->
                        input.copyTo(output)
                    }
                }

                val code = conn.responseCode
                conn.disconnect()
                mainHandler.post { onResult(code == 200 || code == 201) }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post { onResult(false) }
            }
        }.start()
    }

    /**
     * Check & Fetch list of incoming unread voicemails from VPS for my_id
     */
    fun checkIncomingFiles(vpsHost: String, myId: String, onResult: (List<String>) -> Unit) {
        Thread {
            try {
                val urlStr = "${normalizeHost(vpsHost)}/files/$myId"
                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val jsonArray = json.optJSONArray("files")
                    val fileList = mutableListOf<String>()
                    if (jsonArray != null) {
                        for (i in 0 until jsonArray.length()) {
                            fileList.add(jsonArray.getString(i))
                        }
                    }
                    conn.disconnect()
                    mainHandler.post { onResult(fileList) }
                } else {
                    conn.disconnect()
                    mainHandler.post { onResult(emptyList()) }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(emptyList()) }
            }
        }.start()
    }

    /**
     * Download Audio File from VPS to local storage and auto-clean remote file from VPS server
     */
    fun downloadAudioFile(vpsHost: String, myId: String, filename: String, destFile: File, onResult: (Boolean) -> Unit) {
        Thread {
            try {
                val urlStr = "${normalizeHost(vpsHost)}/download/$myId/$filename"
                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                if (conn.responseCode == 200) {
                    val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
                    conn.inputStream.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    conn.disconnect()

                    // Atomic rename to prevent partial/corrupted files if download drops
                    if (tempFile.exists() && tempFile.length() > 0) {
                        tempFile.renameTo(destFile)
                    } else {
                        tempFile.delete()
                    }

                    // Extra safeguard: Confirm remote file deletion on server
                    deleteRemoteFile(vpsHost, myId, filename) {}

                    mainHandler.post { onResult(true) }
                } else {
                    conn.disconnect()
                    mainHandler.post { onResult(false) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post { onResult(false) }
            }
        }.start()
    }

    /**
     * Permanently delete a file from VPS server for my_id
     */
    fun deleteRemoteFile(vpsHost: String, myId: String, filename: String, onResult: (Boolean) -> Unit) {
        Thread {
            try {
                val urlStr = "${normalizeHost(vpsHost)}/files/$myId/$filename"
                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "DELETE"
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                val code = conn.responseCode
                conn.disconnect()
                mainHandler.post { onResult(code == 200) }
            } catch (e: Exception) {
                mainHandler.post { onResult(false) }
            }
        }.start()
    }
}
