package com.ahmedgamil.wakili.core.data.stream

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ahmedgamil.wakili.core.data.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Floating question alert — a persistent card drawn OVER whatever app is open
 * (Facebook-chat-heads style), because a heads-up notification retreats after
 * a few seconds and an ask_options card used to wait out its whole window
 * unseen. Questions only, by design; guarded by the Settings toggle and by
 * the OS "display over other apps" grant ([canShow]).
 *
 * Plain views on purpose: an overlay window has no lifecycle/savedstate owner,
 * which ComposeView requires — a programmatic layout needs neither.
 */
@Singleton
class QuestionOverlay @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private class Entry(val view: View, val sessionId: String)

    private val main = Handler(Looper.getMainLooper())
    private val entries = LinkedHashMap<String, Entry>() // card id -> overlay

    private val wm get() = context.getSystemService(WindowManager::class.java)

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    /** Show one floating card for a question card. No-op without the OS grant. */
    fun show(cardId: String, sessionId: String, question: String?) {
        main.post {
            if (!canShow()) {
                android.util.Log.w("QuestionOverlay", "not shown: display-over-apps not granted")
                return@post
            }
            if (entries.containsKey(cardId)) return@post
            val density = context.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            val title = TextView(context).apply {
                text = context.getString(R.string.data_notify_question_title)
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            }
            val body = TextView(context).apply {
                text = question?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.data_notify_question_text)
                setTextColor(0xB3FFFFFF.toInt())
                textSize = 13f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(title)
                addView(body)
            }
            val close = TextView(context).apply {
                text = "✕"
                setTextColor(0x80FFFFFF.toInt())
                textSize = 16f
                setPadding(dp(12), dp(4), dp(6), dp(4))
                setOnClickListener { remove(cardId) }
            }
            val logo = android.widget.ImageView(context).apply {
                setImageDrawable(context.packageManager.getApplicationIcon(context.applicationInfo))
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) }
            }
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(8), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(0xFF202124.toInt()) // fully opaque — text under the card must not bleed through
                    setStroke(dp(1), 0x33FFFFFF)
                }
                elevation = dp(10).toFloat()
                addView(logo)
                addView(texts)
                addView(close)
                setOnClickListener {
                    // Straight into the asking chat, same extra the notification uses.
                    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        ?.apply {
                            putExtra(NOTIFICATION_SESSION_ID_EXTRA, sessionId)
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
                            )
                        }
                    if (launch != null) runCatching { context.startActivity(launch) }
                    remove(cardId)
                }
            }
            // Outer frame supplies screen-edge margins (an overlay window has no parent to pad it).
            val outer = FrameLayout(context).apply {
                setPadding(dp(10), 0, dp(10), 0)
                addView(card)
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Never steal input from the app underneath; taps on the card still land.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP
                // Stack below any cards already up (rough spacing; one at a time is the norm).
                y = dp(48) + entries.size * dp(84)
            }
            runCatching { wm.addView(outer, lp) }
                .onSuccess { entries[cardId] = Entry(outer, sessionId) }
                // OEM skins (ColorOS/MIUI) can refuse overlay windows from
                // backgrounded apps even WITH the grant — surface the refusal
                // instead of failing into silence.
                .onFailure { android.util.Log.w("QuestionOverlay", "addView failed", it) }
        }
    }

    /** The card was answered/resolved (any device) or dismissed — take it down. */
    fun remove(cardId: String) {
        main.post {
            entries.remove(cardId)?.let { runCatching { wm.removeView(it.view) } }
        }
    }

    /** Turn ended/stopped — nothing is waiting on this session anymore. */
    fun removeForSession(sessionId: String) {
        main.post {
            val gone = entries.filterValues { it.sessionId == sessionId }.keys.toList()
            for (id in gone) entries.remove(id)?.let { runCatching { wm.removeView(it.view) } }
        }
    }

    /** The app is on screen — its own cards are visible; overlays would double up. */
    fun clearAll() {
        main.post {
            for (e in entries.values) runCatching { wm.removeView(e.view) }
            entries.clear()
        }
    }
}
