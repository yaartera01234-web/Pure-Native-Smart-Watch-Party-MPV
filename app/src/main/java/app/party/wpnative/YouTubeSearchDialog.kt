package app.party.wpnative

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** Original Smart Music YouTube-search sheet, rendered with pure native Android Views. */
internal class YouTubeSearchDialog(
    private val activity: Activity,
    private val theme: WpTheme,
    private val play: (YouTubeSearch.Item) -> Boolean
) {
    private val dialog = Dialog(activity)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var query: EditText
    private lateinit var suggestions: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var instanceMeta: TextView
    private lateinit var countMeta: TextView
    private lateinit var sourceMeta: TextView
    private var searchHandle: YouTubeSearch.Handle? = null
    private var generation = 0
    private var settingSuggestion = false

    private val fixedSuggestions = listOf(
        "lofi girl", "arijit singh songs", "punjabi songs", "coke studio",
        "old hindi songs", "english top hits", "quran recitation", "live radio"
    )
    private val debounce = Runnable { runSearch(query.text.toString()) }

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(build())
        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            searchHandle?.cancel(); searchHandle = null
            generation++
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                (activity.resources.displayMetrics.heightPixels * .86f).toInt())
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        renderSuggestions()
        renderEmpty("idle", "")
        query.postDelayed({
            if (!dialog.isShowing) return@postDelayed
            query.requestFocus()
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT)
        }, 180L)
    }

    private fun build(): View {
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, theme.menu).apply {
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(),
                    dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
                setStroke(dp(1), theme.panelStroke)
            }
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heading = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(TextView(activity).apply {
            text = "🔍 YouTube Search"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        heading.addView(TextView(activity).apply {
            text = "Piped primary · YouTube API backup"
            textSize = 10.5f
            setTextColor(hex("#a9a6c8"))
        })
        header.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(activity).apply {
            text = "✕"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = round(Color.argb(20, 255, 255, 255), Color.argb(36, 255, 255, 255), 10)
            setOnClickListener { dialog.dismiss() }
        }, lp(dp(32), dp(32)))
        page.addView(header, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(9)
        })

        val searchRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        query = EditText(activity).apply {
            hint = "Gaana, movie, video... likho"
            setHintTextColor(hex("#88869b"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(13), 0, dp(13), 0)
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, theme.input).apply {
                cornerRadius = dp(12).toFloat(); setStroke(dp(2), theme.inputStroke)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (settingSuggestion) return
                    renderSuggestions()
                    handler.removeCallbacks(debounce)
                    if (s.isNullOrBlank()) {
                        searchHandle?.cancel(); searchHandle = null; generation++
                        renderEmpty("idle", "")
                    } else handler.postDelayed(debounce, 350L)
                }
            })
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                    handler.removeCallbacks(debounce); runSearch(text.toString()); true
                } else false
            }
        }
        searchRow.addView(query, LinearLayout.LayoutParams(0, dp(46), 1f))
        searchRow.addView(TextView(activity).apply {
            text = "🔍"
            textSize = 16f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, theme.accent).apply {
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { handler.removeCallbacks(debounce); runSearch(query.text.toString()) }
        }, lp(dp(48), dp(46)).apply { leftMargin = dp(7) })
        page.addView(searchRow, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        suggestions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(2))
        }
        page.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(suggestions)
        }, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val meta = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(5))
        }
        instanceMeta = metaPill("⚡ ready")
        countMeta = metaPill("0 results")
        sourceMeta = metaPill("source: —")
        meta.addView(instanceMeta, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(25)).apply { rightMargin = dp(5) })
        meta.addView(countMeta, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(25)).apply { rightMargin = dp(5) })
        meta.addView(sourceMeta, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(25)))
        page.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(meta)
        }, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        results = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(1), 0, dp(1))
        }
        val resultScroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(results)
        }
        page.addView(resultScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        page.addView(TextView(activity).apply {
            text = "Result pe tap = ▶ Play sab ke liye · item queue ke aakhir mein add hoga"
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(hex("#8f8bb3"))
            setPadding(dp(2), dp(7), dp(2), 0)
        }, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return page
    }

    private fun renderSuggestions() {
        if (!::suggestions.isInitialized) return
        suggestions.removeAllViews()
        val filter = if (::query.isInitialized) query.text.toString().trim().lowercase(Locale.ROOT) else ""
        val values = (recent() + fixedSuggestions).distinct().filter {
            filter.isBlank() || it.lowercase(Locale.ROOT).contains(filter)
        }.take(9)
        values.forEach { value ->
            suggestions.addView(TextView(activity).apply {
                text = value
                textSize = 11.5f
                gravity = Gravity.CENTER
                setTextColor(hex("#ddd9f5"))
                setPadding(dp(11), 0, dp(11), 0)
                background = round(theme.chip, theme.itemStroke, 20)
                setOnClickListener { chooseSuggestion(value) }
            }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(31)).apply { rightMargin = dp(6) })
        }
        suggestions.visibility = if (values.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * A chip must not rewrite and rebuild its own parent while Android is still
     * dispatching that chip's click. Finish dispatching first, then update the field
     * without re-entering its TextWatcher.
     */
    private fun chooseSuggestion(value: String) {
        handler.removeCallbacks(debounce)
        handler.post {
            if (!dialog.isShowing || activity.isFinishing || activity.isDestroyed) return@post
            try {
                settingSuggestion = true
                query.setText(value)
                query.setSelection(query.text.length)
                settingSuggestion = false
                runSearch(value)
            } catch (_: RuntimeException) {
                settingSuggestion = false
                searchHandle?.cancel(); searchHandle = null; generation++
                if (dialog.isShowing) runCatching { renderEmpty("down", value) }
            }
        }
    }

    private fun runSearch(raw: String) {
        val value = raw.trim()
        if (value.isBlank()) { renderEmpty("idle", ""); return }
        addRecent(value)
        renderSuggestions()
        searchHandle?.cancel()
        val thisGeneration = ++generation
        renderSkeleton()
        searchHandle = YouTubeSearch.search(value) { outcome ->
            if (thisGeneration != generation || !dialog.isShowing) return@search
            searchHandle = null
            if (outcome == null || outcome.items.isEmpty()) renderEmpty("down", value)
            else renderResults(outcome)
        }
    }

    private fun renderSkeleton() {
        results.removeAllViews()
        repeat(5) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(7), dp(7), dp(7), dp(7))
                background = round(theme.soft2, Color.argb(20, 255, 255, 255), 12)
            }
            row.addView(View(activity).apply {
                background = round(Color.argb(28, 255, 255, 255), Color.TRANSPARENT, 9)
            }, lp(dp(96), dp(54)))
            val bars = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            bars.addView(View(activity).apply {
                background = round(Color.argb(32, 255, 255, 255), Color.TRANSPARENT, 7)
            }, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(11)))
            bars.addView(View(activity).apply {
                background = round(Color.argb(22, 255, 255, 255), Color.TRANSPARENT, 7)
            }, lp(dp(110), dp(11)).apply { topMargin = dp(8) })
            row.addView(bars, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(9)
            })
            results.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(7)
            })
        }
        instanceMeta.text = "⚡ searching…"
        instanceMeta.setTextColor(hex("#a9a6c8"))
        countMeta.text = "0 results"
        sourceMeta.text = "source: —"
    }

    private fun renderResults(outcome: YouTubeSearch.Outcome) {
        results.removeAllViews()
        outcome.items.forEach { item -> results.addView(resultRow(item),
            lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(7) }) }
        instanceMeta.text = "⚡ ${outcome.instance}"
        instanceMeta.setTextColor(hex("#86efac"))
        countMeta.text = "${outcome.items.size} results"
        sourceMeta.text = "source: ${outcome.source}"
    }

    private fun resultRow(item: YouTubeSearch.Item): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = round(theme.soft, Color.argb(25, 255, 255, 255), 12)
        }
        val thumb = FrameLayout(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, theme.fill2).apply {
                cornerRadius = dp(9).toFloat()
            }
            clipToOutline = true
        }
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "${item.title} thumbnail"
        }
        thumb.addView(image, FrameLayout.LayoutParams(dp(96), dp(54)))
        PartyPlaylistMedia.thumbnail(activity, item.videoId, image)
        if (item.durationSeconds > 0) thumb.addView(TextView(activity).apply {
            text = formatDuration(item.durationSeconds)
            textSize = 10f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(5), 0, dp(5), 0)
            background = round(Color.argb(205, 0, 0, 0), Color.TRANSPARENT, 5)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(20),
            Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, dp(4), dp(4)) })
        row.addView(thumb, lp(dp(96), dp(54)))

        val info = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(activity).apply {
            text = item.title
            textSize = 12.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        info.addView(TextView(activity).apply {
            text = item.channel.ifBlank { "YouTube" }
            textSize = 10.5f
            setTextColor(hex("#a9a6c8"))
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(9); rightMargin = dp(6)
        })
        row.addView(TextView(activity).apply {
            text = "▶ Play"
            textSize = 11f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(hex("#22c55e"), hex("#16a34a"))).apply { cornerRadius = dp(10).toFloat() }
        }, lp(dp(62), dp(36)))

        row.setOnClickListener {
            if (play(item)) {
                hideKeyboard()
                dialog.dismiss()
            }
        }
        return row
    }

    private fun renderEmpty(kind: String, value: String) {
        if (!::results.isInitialized) return
        results.removeAllViews()
        val idle = kind == "idle"
        results.addView(TextView(activity).apply {
            text = if (idle) {
                "🔍\nKuch likho — ya upar wala chip dabao.\nYouTube search yahin, link copy karne ki zaroorat nahi."
            } else {
                "📡\nAbhi search nahi ho raha.\nPiped instances aur YouTube backup ne jawab nahi diya.\n\nParty chalu hai — link paste karke video phir bhi chala sakte ho."
            }
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(hex("#a9a6c8"))
            setLineSpacing(0f, 1.35f)
            setPadding(dp(10), dp(26), dp(10), dp(26))
        }, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        instanceMeta.text = if (idle) "⚡ ready" else "⚡ sab down"
        instanceMeta.setTextColor(if (idle) hex("#a9a6c8") else hex("#fcd34d"))
        countMeta.text = "0 results"
        sourceMeta.text = "source: —"
    }

    private fun recent(): List<String> {
        val raw = activity.getSharedPreferences("wp_youtube_search", Context.MODE_PRIVATE)
            .getString("recent", "").orEmpty()
        return raw.split('\u001f').map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun addRecent(value: String) {
        val list = recent().filterNot { it.equals(value, ignoreCase = true) }.toMutableList()
        list.add(0, value)
        activity.getSharedPreferences("wp_youtube_search", Context.MODE_PRIVATE).edit()
            .putString("recent", list.take(10).joinToString("\u001f")).apply()
    }

    private fun hideKeyboard() {
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(query.windowToken, 0)
        query.clearFocus()
    }

    private fun metaPill(label: String) = TextView(activity).apply {
        text = label
        textSize = 10.5f
        gravity = Gravity.CENTER
        setTextColor(hex("#a9a6c8"))
        setPadding(dp(8), 0, dp(8), 0)
        background = round(Color.argb(15, 255, 255, 255), Color.argb(31, 255, 255, 255), 20)
    }

    private fun formatDuration(total: Long): String {
        val seconds = total.coerceAtLeast(0L)
        val h = seconds / 3600L
        val m = seconds / 60L % 60L
        val s = seconds % 60L
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        else "${m}:${s.toString().padStart(2, '0')}"
    }

    private fun round(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }
    private fun hex(value: String) = Color.parseColor(value)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun lp(width: Int, height: Int) = LinearLayout.LayoutParams(width, height)
}
