package os.deckbridge.manager.mobile

import os.deckbridge.manager.studio.*

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * O set tocando como um player de música: um serviço em primeiro plano (o Android não encerra o processo com o app
 * fechado ou a tela bloqueada) com a notificação de mídia e os controles na tela de bloqueio e nos fones (sessão de
 * mídia): voltar, pausar/tocar, próxima transição e parar. Quem toca é o [Preview] do app; o serviço só o mantém vivo e
 * mostra o que toca. Tirar o fone pausa.
 */
class SetPlaybackService : Service() {
    private lateinit var session: MediaSession
    private val main = Handler(Looper.getMainLooper())
    private val app get() = ManagerApp.get(this)
    private val preview get() = app.preview

    private val tick = object : Runnable {
        override fun run() {
            if (preview.setPlay == null) { stopSelf(); return }
            publish()
            main.postDelayed(this, 1_000)
        }
    }

    // Tirou o fone (ou desconectou o Bluetooth): pausa, como qualquer player.
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) preview.pause()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ouvir o set", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        session = MediaSession(this, "deckbridge-set").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = preview.resume()
                override fun onPause() = preview.pause()
                override fun onSkipToNext() = preview.skip(1)
                override fun onSkipToPrevious() = preview.skip(-1)
                override fun onStop() = preview.stop()
                override fun onSeekTo(pos: Long) { preview.seekTo(pos.toInt()); publish() }
            })
            isActive = true
        }
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> preview.togglePause()
            ACTION_NEXT -> preview.skip(1)
            ACTION_PREV -> preview.skip(-1)
            ACTION_STOP -> { preview.stop(); stopSelf(); return START_NOT_STICKY }
        }
        if (preview.setPlay == null) { stopSelf(); return START_NOT_STICKY }
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(ID, n)
        main.removeCallbacks(tick)
        main.post(tick)
        return START_NOT_STICKY
    }

    /** A capa da virada tocando (refeita só quando muda de virada). */
    private var coverAt = -2
    private var cover: android.graphics.Bitmap? = null

    private fun chapterNow(): Int {
        val sp = preview.setPlay ?: return -1
        return Chapter.at(sp.chapters, preview.positionMs())
    }

    private fun coverNow(): android.graphics.Bitmap? {
        val sp = preview.setPlay ?: return null
        val at = chapterNow()
        if (at != coverAt || cover == null) {
            cover = runCatching { cover(sp.title, sp.chapters, at) }.getOrNull()
            coverAt = at
        }
        return cover
    }

    /** "Virada 3/8 · Sexta à noite": o número da virada e o set (a segunda linha do player). */
    private fun subtitle(): String {
        val sp = preview.setPlay ?: return ""
        val at = chapterNow()
        return if (sp.chapters.isEmpty() || at < 0) sp.title else "Virada ${at + 1}/${sp.chapters.size} · ${sp.title}"
    }

    private fun publish() {
        val sp = preview.setPlay ?: return
        val pos = preview.positionMs().toLong()
        val art = coverNow()
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, preview.nowPlaying())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle())
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitle())
                .putString(MediaMetadata.METADATA_KEY_ALBUM, sp.title)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, sp.durationMs.toLong())
                .apply { if (art != null) { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art); putBitmap(MediaMetadata.METADATA_KEY_ART, art) } }
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO)
                .setState(if (preview.paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING, pos, if (preview.paused) 0f else 1f)
                .build(),
        )
        getSystemService(NotificationManager::class.java).notify(ID, notification())
    }

    private fun action(icon: Int, label: String, act: String): Notification.Action {
        val pi = PendingIntent.getService(this, act.hashCode(), Intent(this, SetPlaybackService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), label, pi).build()
    }

    private fun notification(): Notification {
        val sp = preview.setPlay
        // Tocar na notificação volta para o app (o modo Biblioteca, onde o set está).
        val open = PendingIntent.getActivity(this, 0, Intent(this, ManagerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(preview.nowPlaying().ifEmpty { "Set" })
            .setContentText(subtitle().ifEmpty { sp?.title ?: "" })
            .setLargeIcon(coverNow())
            .setContentIntent(open)
            .setOngoing(!preview.paused)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "Virada anterior", ACTION_PREV))
            .addAction(if (preview.paused) action(android.R.drawable.ic_media_play, "Tocar", ACTION_TOGGLE) else action(android.R.drawable.ic_media_pause, "Pausar", ACTION_TOGGLE))
            .addAction(action(android.R.drawable.ic_media_next, "Próxima virada", ACTION_NEXT))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Parar", ACTION_STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        runCatching { unregisterReceiver(noisy) }
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    /** Fechou o app pela lista de recentes: o set continua (é um player); parar é pela notificação. */
    override fun onTaskRemoved(rootIntent: Intent?) = Unit

    companion object {
        private const val CHANNEL = "set"
        private const val ID = 7031
        const val ACTION_TOGGLE = "os.deckbridge.set.TOGGLE"
        const val ACTION_NEXT = "os.deckbridge.set.NEXT"
        const val ACTION_PREV = "os.deckbridge.set.PREV"
        const val ACTION_STOP = "os.deckbridge.set.STOP"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, SetPlaybackService::class.java)) }
        }

        /** Atualiza a notificação já (pausou, pulou). */
        fun refresh(context: Context) = start(context)

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SetPlaybackService::class.java)) }
        }

        /**
         * A capa da virada para o player da notificação e da tela de bloqueio: fundo escuro, "VIRADA 3/8", a que sai (cor do
         * deck 1) e a que entra (cor do deck 2), e embaixo a linha do set com um traço por virada (a de agora acesa).
         */
        fun cover(setName: String, chapters: List<Chapter>, at: Int, size: Int = 640): android.graphics.Bitmap {
            val pal = os.deckbridge.logic.StagePalette
            val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            c.drawColor(pal.bg)
            val u = size / 640f
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            // Brilho das duas cores dos decks no fundo (a troca de um para o outro).
            paint.shader = android.graphics.LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), intArrayOf((pal.deckA and 0x00FFFFFF) or 0x55000000, pal.bg, (pal.deckB and 0x00FFFFFF) or 0x55000000), null, android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
            paint.shader = null
            fun text(t: String, x: Float, y: Float, sizePx: Float, color: Int, bold: Boolean = false, maxW: Float = size - 2 * 48 * u) {
                paint.color = color
                paint.textSize = sizePx
                paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                var s = t
                while (s.length > 1 && paint.measureText(s) > maxW) s = s.dropLast(2) + "…"
                c.drawText(s, x, y, paint)
            }
            val left = 48 * u
            val total = chapters.size
            text(if (total > 0 && at >= 0) "VIRADA ${at + 1}/$total" else "SET", left, 92 * u, 34 * u, pal.accentOrange, bold = true)
            text(setName, left, 136 * u, 28 * u, pal.inkMuted)
            val parts = chapters.getOrNull(at)?.title?.split(" → ", limit = 2).orEmpty()
            text("SAI", left, 250 * u, 24 * u, pal.inkMuted, bold = true)
            text(parts.getOrNull(0) ?: setName, left, 300 * u, 46 * u, pal.deckA, bold = true)
            text("↓", left, 370 * u, 44 * u, pal.ink)
            text("ENTRA", left, 430 * u, 24 * u, pal.inkMuted, bold = true)
            text(parts.getOrNull(1) ?: "", left, 480 * u, 46 * u, pal.deckB, bold = true)
            // A linha do set: um traço por virada; as que já foram em azul, a de agora em laranja.
            if (total > 0) {
                val y = 568 * u
                val w = size - 2 * left
                val gap = 6 * u
                val seg = (w - gap * (total - 1)) / total
                for (i in 0 until total) {
                    paint.color = when {
                        i == at -> pal.accentOrange
                        i < at -> pal.accentBlue
                        else -> pal.line
                    }
                    val x0 = left + i * (seg + gap)
                    c.drawRect(x0, y - (if (i == at) 9 else 5) * u, x0 + seg, y + (if (i == at) 9 else 5) * u, paint)
                }
            }
            return bmp
        }
    }
}
