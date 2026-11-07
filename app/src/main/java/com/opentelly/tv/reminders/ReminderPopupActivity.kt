package com.opentelly.tv.reminders

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.lifecycle.lifecycleScope
import com.opentelly.tv.R
import com.opentelly.tv.appContainer
import com.opentelly.tv.data.db.ReminderEntity
import com.opentelly.tv.databinding.ActivityReminderPopupBinding
import com.opentelly.tv.ui.MainActivity
import com.opentelly.tv.ui.player.PlayerActivity
import kotlinx.coroutines.launch
import java.util.Date

/** "Starting soon" card over whatever is on screen, with a shortcut to the channel. */
class ReminderPopupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReminderPopupBinding
    private val handler = Handler(Looper.getMainLooper())
    private var remaining = 0

    private val tick = object : Runnable {
        override fun run() {
            if (remaining <= 0) return finish()
            binding.countdown.text = getString(R.string.reminder_closes_in, remaining)
            remaining--
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Wake the TV if it dozed off.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityReminderPopupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Dialog windows wrap to their minimum width otherwise, squeezing the buttons.
        window.setLayout((600 * resources.displayMetrics.density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        setFinishOnTouchOutside(false)

        NotificationManagerCompat.from(this).cancel(ReminderReceiver.NOTIFICATION_BASE + intent.getLongExtra(EXTRA_ID, 0).toInt())

        val start = intent.getLongExtra(EXTRA_START, 0)
        val minutes = ((start - System.currentTimeMillis() + 30_000) / 60_000).toInt()
        binding.`when`.text = if (minutes <= 0) getString(R.string.reminder_starting_now)
        else resources.getQuantityString(R.plurals.reminder_starting_in, minutes, minutes)
        binding.title.text = intent.getStringExtra(EXTRA_TITLE)
        binding.channel.text = getString(
            R.string.reminder_channel_time, intent.getStringExtra(EXTRA_CHANNEL).orEmpty(),
            DateFormat.getTimeFormat(this).format(Date(start)),
        )
        binding.watch.setOnClickListener { watch() }
        binding.dismiss.setOnClickListener { finish() }
        binding.watch.requestFocus()

        lifecycleScope.launch {
            remaining = appContainer.settings.get().reminderPopupSeconds
            handler.post(tick)
        }
    }

    private fun watch() {
        handler.removeCallbacks(tick)
        lifecycleScope.launch {
            val channel = appContainer.database.channels().findByKey(
                intent.getLongExtra(EXTRA_PLAYLIST, -1), intent.getStringExtra(EXTRA_KEY).orEmpty(),
            )
            if (channel == null) {
                Toast.makeText(this@ReminderPopupActivity, R.string.reminder_channel_gone, Toast.LENGTH_LONG).show()
            } else {
                // Main screen underneath so Back from the player lands somewhere sensible.
                TaskStackBuilder.create(this@ReminderPopupActivity)
                    .addNextIntent(Intent(this@ReminderPopupActivity, MainActivity::class.java))
                    .addNextIntent(PlayerActivity.intent(this@ReminderPopupActivity, channel.id, longArrayOf(channel.id), 0))
                    .startActivities()
            }
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_PLAYLIST = "playlist"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_CHANNEL = "channel"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_START = "start"

        fun intent(context: Context, r: ReminderEntity): Intent =
            Intent(context, ReminderPopupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_ID, r.id)
                .putExtra(EXTRA_PLAYLIST, r.playlistId)
                .putExtra(EXTRA_KEY, r.channelKey)
                .putExtra(EXTRA_CHANNEL, r.channelName)
                .putExtra(EXTRA_TITLE, r.title)
                .putExtra(EXTRA_START, r.programStart)
    }
}
