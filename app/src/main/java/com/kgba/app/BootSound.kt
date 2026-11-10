package com.kgba.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Sonido de arranque de KGBA, sintetizado al vuelo (no hay ningún archivo de audio).
 * Va sincronizado con [BootView]: mientras el destello recorre el logotipo suena un arpegio
 * ascendente de campanillas, y cuando brilla la estrella final entra un acorde luminoso que se
 * apaga despacio, con un «ting» agudo encima.
 */
internal class BootSound {
    private var track: AudioTrack? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        track = try {
            val pcm = render()
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build().apply { write(pcm, 0, pcm.size); play() }
        } catch (_: Exception) { null }
    }

    fun pause() { runCatching { track?.pause() } }
    fun resume() { runCatching { track?.takeIf { it.playState == AudioTrack.PLAYSTATE_PAUSED }?.play() } }
    fun release() { runCatching { track?.stop() }; track?.release(); track = null }

    private companion object {
        const val RATE = 22050
        const val LENGTH = 2.55f // acaba antes que la animación (2,6 s): no se corta al quitarla

        /** Frecuencia de una nota MIDI. */
        fun hz(note: Int) = 440.0 * Math.pow(2.0, (note - 69) / 12.0)

        fun render(): ShortArray {
            val buf = FloatArray((RATE * LENGTH).toInt())
            // Destello (0,26 s → 1,43 s): arpegio ascendente de campanillas cortas.
            val arp = intArrayOf(72, 76, 79, 84, 88, 91)        // Do mayor subiendo
            arp.forEachIndexed { i, n -> bell(buf, 0.26f + i * 0.19f, hz(n), 0.20f, 0.30f, 0.16f) }
            // Estrella final (1,43 s): acorde amplio que se desvanece.
            for (n in intArrayOf(60, 67, 72, 76, 79, 86)) bell(buf, 1.43f, hz(n), 0.13f, 1.3f, 0.10f)
            // «ting» agudo del brillo
            bell(buf, 1.55f, hz(96), 0.11f, 0.45f, 0.05f)
            bell(buf, 1.68f, hz(103), 0.06f, 0.35f, 0.04f)
            // Normaliza, suaviza el final y convierte a 16 bits.
            var peak = 0.0001f
            for (v in buf) peak = maxOf(peak, kotlin.math.abs(v))
            val gain = 0.8f / peak
            val fade = (RATE * 0.25f).toInt()
            return ShortArray(buf.size) { i ->
                val tail = if (i > buf.size - fade) (buf.size - i).toFloat() / fade else 1f
                (buf[i] * gain * tail * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
            }
        }

        /**
         * Nota de campana con timbre «chip»: fundamental más armónicos suaves, ataque rápido,
         * caída exponencial ([decay] s) y un ligero vibrato.
         */
        fun bell(buf: FloatArray, at: Float, f: Double, amp: Float, decay: Float, attack: Float) {
            val start = (at * RATE).toInt()
            val len = minOf(buf.size - start, (RATE * (decay * 4f)).toInt())
            val atk = maxOf(1, (RATE * attack * 0.03f).toInt()) // 1–5 ms: ataque nítido sin chasquido
            for (i in 0 until len) {
                val t = i.toDouble() / RATE
                val env = (if (i < atk) i.toFloat() / atk else 1f) * exp(-t / decay).toFloat()
                val vib = 1.0 + 0.004 * sin(2 * PI * 5.5 * t)
                val ph = 2 * PI * f * vib * t
                val s = sin(ph) + 0.35 * sin(2 * ph) * exp(-t * 6) + 0.15 * sin(3 * ph) * exp(-t * 10)
                buf[start + i] += (s * env * amp).toFloat()
            }
        }
    }
}
