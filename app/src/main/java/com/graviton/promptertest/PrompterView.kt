package com.graviton.promptertest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.graviton.promptertest.core.ScriptParser
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Draws the script. Read words are dimmed, the word you are on sits in a glowing box, and the
 * text glides so that word stays on the reading line. Tap a word to jump there; drag to look around.
 */
class PrompterView(context: Context) : View(context) {

    var onWordTap: ((Int) -> Unit)? = null

    private var parsed: ScriptParser.Parsed? = null
    private var theme: PTheme = Themes.all[0]
    private var hiColor = 0
    private var textSp = 30
    private var readingPct = 35
    private var bgAlphaPct = 70

    var pos = 0
        private set

    private val density = resources.displayMetrics.density
    private val padH = 12f * density

    private val pBase = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val pDone = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val pCur = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val pHi = Paint(Paint.ANTI_ALIAS_FLAG)

    private var baseLayout: StaticLayout? = null
    private var doneLayout: StaticLayout? = null
    private var curLayout: StaticLayout? = null
    private var layoutW = 0

    // done-region boundary (layout coordinates)
    private var doneMode = 0 // 0 none, 1 partial, 2 all
    private var doneLineTop = 0f
    private var doneLineBot = 0f
    private var doneX = 0f

    // animation
    private var scrollY = 0f
    private var targetY = 0f
    private val hRect = RectF()
    private val tRect = RectF()
    private var manual = 0f
    private var manualUntil = 0L
    private var lastFrame = 0L
    private var animating = false
    private var haveTarget = false

    private val frame = Runnable { step() }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            manual += dy
            manualUntil = SystemClock.uptimeMillis() + 2500
            kick()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val ps = parsed ?: return false
            val l = baseLayout ?: return false
            if (ps.words.isEmpty()) return false
            val y = (e.y + scrollY + manual).toInt()
            val line = l.getLineForVertical(y)
            val off = l.getOffsetForHorizontal(line, e.x - padH)
            onWordTap?.invoke(ps.wordAtOffset(off))
            return true
        }
    })

    init {
        val tf = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        pBase.typeface = tf
        pDone.typeface = tf
        pCur.typeface = tf
        applyColors()
        setBackgroundColor(Color.argb(180, 0, 0, 0))
    }

    // ------------------------------------------------------------ public API

    fun setScript(p: ScriptParser.Parsed) {
        parsed = p
        pos = 0
        manual = 0f
        rebuild()
        computeTargets(true)
        invalidate()
    }

    fun setStyle(t: PTheme, highlight: Int, sizeSp: Int, readingLinePct: Int, opacityPct: Int) {
        val sizeChanged = sizeSp != textSp
        val themeChanged = t !== theme
        theme = t
        hiColor = highlight
        textSp = sizeSp
        readingPct = readingLinePct
        bgAlphaPct = opacityPct
        applyColors()
        if (sizeChanged || themeChanged) rebuild()
        computeTargets(false)
        kick()
        invalidate()
    }

    fun setPos(p: Int) {
        val ps = parsed ?: return
        pos = p.coerceIn(0, ps.n())
        computeTargets(false)
        kick()
        invalidate()
    }

    // ------------------------------------------------------------ internals

    private fun applyColors() {
        val px = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, textSp.toFloat(), resources.displayMetrics
        )
        pBase.textSize = px
        pDone.textSize = px
        pCur.textSize = px
        pBase.color = theme.up
        pDone.color = theme.done
        pCur.color = theme.cur
        val hi = if (hiColor != 0) hiColor else theme.hi
        val light = Color.red(theme.bg) + Color.green(theme.bg) + Color.blue(theme.bg) > 600
        pHi.color = Color.argb(if (light) 190 else 105, Color.red(hi), Color.green(hi), Color.blue(hi))
        val a = (bgAlphaPct.coerceIn(0, 100) * 255) / 100
        setBackgroundColor(Color.argb(a, Color.red(theme.bg), Color.green(theme.bg), Color.blue(theme.bg)))
    }

    private fun spannable(text: String): SpannableString {
        val s = SpannableString(text)
        val ps = parsed
        if (ps != null) {
            for (c in ps.cues) {
                if (c[0] >= 0 && c[1] <= text.length && c[0] < c[1]) {
                    s.setSpan(ForegroundColorSpan(theme.cue), c[0], c[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return s
    }

    private fun make(paint: TextPaint, text: CharSequence, w: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.3f)
            .setIncludePad(false)
            .build()

    private fun rebuild() {
        val ps = parsed
        val w = (width - 2 * padH).toInt()
        if (ps == null || w <= 50) {
            baseLayout = null
            doneLayout = null
            curLayout = null
            return
        }
        layoutW = w
        baseLayout = make(pBase, spannable(ps.text), w)
        doneLayout = make(pDone, spannable(ps.text), w)
        curLayout = make(pCur, ps.text, w)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
        computeTargets(true)
    }

    private fun computeTargets(snap: Boolean) {
        val ps = parsed ?: return
        val l = baseLayout ?: return
        if (ps.words.isEmpty() || height == 0) return
        val idx = min(pos, ps.n() - 1)
        val w = ps.words[idx]
        val line = l.getLineForOffset(w.start)
        val top = l.getLineTop(line).toFloat()
        val bot = l.getLineBottom(line).toFloat()
        val endOff = min(w.end, l.getLineEnd(line))
        val x0 = l.getPrimaryHorizontal(w.start)
        var x1 = l.getPrimaryHorizontal(endOff)
        if (x1 <= x0) x1 = x0 + 10f
        val pad = 3f * density
        tRect.set(x0 - pad, top, x1 + pad, bot)
        targetY = (top + bot) / 2f - height * readingPct / 100f

        doneLineTop = top
        doneLineBot = bot
        doneX = x0
        doneMode = if (pos <= 0) 0 else if (pos >= ps.n()) 2 else 1

        if (snap || !haveTarget) {
            hRect.set(tRect)
            scrollY = targetY
            haveTarget = true
        }
    }

    private fun kick() {
        if (!animating && isAttachedToWindow) {
            animating = true
            lastFrame = SystemClock.uptimeMillis()
            postOnAnimation(frame)
        }
    }

    private fun step() {
        val now = SystemClock.uptimeMillis()
        val dt = min(0.05f, (now - lastFrame) / 1000f)
        lastFrame = now
        val k = 1f - exp(-dt * 9f)
        val k2 = 1f - exp(-dt * 16f)
        var moving = false

        if (manualUntil != 0L && now > manualUntil) {
            manual -= manual * k * 0.8f
            if (abs(manual) < 0.5f) {
                manual = 0f
                manualUntil = 0L
            }
        }
        if (manualUntil != 0L) moving = true

        val dy = targetY - scrollY
        if (abs(dy) < 0.5f) scrollY = targetY else { scrollY += dy * k; moving = true }

        hRect.left += (tRect.left - hRect.left) * k2
        hRect.right += (tRect.right - hRect.right) * k2
        hRect.top += (tRect.top - hRect.top) * k2
        hRect.bottom += (tRect.bottom - hRect.bottom) * k2
        if (abs(tRect.left - hRect.left) > 0.5f || abs(tRect.top - hRect.top) > 0.5f ||
            abs(tRect.right - hRect.right) > 0.5f
        ) moving = true else hRect.set(tRect)

        invalidate()
        if (moving) postOnAnimation(frame) else animating = false
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        animating = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val base = baseLayout ?: return
        val done = doneLayout
        val cur = curLayout
        val r = 6f * density
        canvas.save()
        canvas.translate(padH, -(scrollY + manual))

        canvas.drawRoundRect(hRect, r, r, pHi)
        base.draw(canvas)

        if (done != null) {
            when (doneMode) {
                2 -> done.draw(canvas)
                1 -> {
                    canvas.save()
                    canvas.clipRect(-padH, 0f, layoutW + padH, doneLineTop)
                    done.draw(canvas)
                    canvas.restore()
                    canvas.save()
                    canvas.clipRect(-padH, doneLineTop, doneX, doneLineBot)
                    done.draw(canvas)
                    canvas.restore()
                }
            }
        }
        if (cur != null && doneMode != 2) {
            canvas.save()
            canvas.clipRect(hRect)
            cur.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestures.onTouchEvent(event) || super.onTouchEvent(event)
    }
}
