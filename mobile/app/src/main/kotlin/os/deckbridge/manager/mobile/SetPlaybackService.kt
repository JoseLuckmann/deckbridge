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

    private fun publish() {
        val sp = preview.setPlay ?: return
        val pos = preview.positionMs().toLong()
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, preview.nowPlaying())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, sp.title)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "DeckBridge · set")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, sp.durationMs.toLong())
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
            .setContentText(sp?.title ?: "")
            .setContentIntent(open)
            .setOngoing(!preview.paused)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "Voltar", ACTION_PREV))
            .addAction(if (preview.paused) action(android.R.drawable.ic_media_play, "Tocar", ACTION_TOGGLE) else action(android.R.drawable.ic_media_pause, "Pausar", ACTION_TOGGLE))
            .addAction(action(android.R.drawable.ic_media_next, "Próxima", ACTION_NEXT))
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
    }
}
