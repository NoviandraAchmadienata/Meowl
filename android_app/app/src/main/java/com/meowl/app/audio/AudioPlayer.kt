package com.meowl.app.audio

import android.content.Context
import android.media.MediaPlayer
import android.widget.Toast
import java.io.File

/**
 * Non-blocking Audio Player Helper with Pause/Resume and Crash-Proof Error Handling.
 */
class AudioPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null

    fun playAudioFile(file: File, onComplete: () -> Unit) {
        stopAudio()

        if (!file.exists() || file.length() <= 0L) {
            Toast.makeText(context, "File audio tidak valid", Toast.LENGTH_SHORT).show()
            onComplete()
            return
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    onComplete()
                    stopAudio()
                }
                setOnErrorListener { _, _, _ ->
                    Toast.makeText(context, "Gagal memutar audio", Toast.LENGTH_SHORT).show()
                    onComplete()
                    stopAudio()
                    true
                }
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Gagal membuka audio", Toast.LENGTH_SHORT).show()
            onComplete()
            stopAudio()
        }
    }

    fun pauseAudio() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun resumeAudio() {
        try {
            if (mediaPlayer != null && !mediaPlayer!!.isPlaying) {
                mediaPlayer?.start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopAudio() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaPlayer = null
        }
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0

    fun getDuration(): Int = mediaPlayer?.duration ?: 0
}
