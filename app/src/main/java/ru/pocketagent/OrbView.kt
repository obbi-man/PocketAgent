package ru.pocketagent

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Большой анимированный «шар» живого режима: дышит, пульсирует от голоса, крутится пока думает. */
class OrbView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    enum class State { IDLE, LISTENING, THINKING, SPEAKING, ERROR }
    var state = State.IDLE
        set(v) { field = v; invalidate() }
    private var level = 0f          // громкость 0..1
    private var phase = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 4000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; level *= 0.92f; invalidate() }
    }

    fun setRms(db: Float) { level = maxOf(level, ((db + 2f) / 12f).coerceIn(0f, 1f)) }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    // фиолетово-розовая гамма, как в оформлении приложения
    private fun colors(): Pair<Int, Int> = when (state) {
        State.IDLE -> Color.parseColor("#7C6BA8") to Color.parseColor("#3A3350")
        State.LISTENING -> Color.parseColor("#8B5CF6") to Color.parseColor("#EC4899")
        State.THINKING -> Color.parseColor("#6366F1") to Color.parseColor("#A855F7")
        State.SPEAKING -> Color.parseColor("#D946EF") to Color.parseColor("#F472B6")
        State.ERROR -> Color.parseColor("#F87171") to Color.parseColor("#B91C1C")
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val maxR = min(width, height) / 2f * 0.92f
        val t = phase * 2 * PI.toFloat()
        val breathe = when (state) {
            State.IDLE -> 0.04f * sin(t)
            State.LISTENING -> 0.05f * sin(t * 2) + 0.18f * level
            State.THINKING -> 0.03f * sin(t * 4)
            State.SPEAKING -> 0.07f * sin(t * 6) * (0.6f + 0.4f * sin(t * 1.7f))
            State.ERROR -> 0f
        }
        val r = maxR * (0.72f + breathe)
        val (light, dark) = colors()
        // ореол
        paint.shader = RadialGradient(cx, cy, maxR, intArrayOf(Color.argb(110, Color.red(dark), Color.green(dark), Color.blue(dark)), Color.TRANSPARENT),
            floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, maxR, paint)
        // шар
        val rot = phase * 2 * PI.toFloat()
        val dx = kotlin.math.cos(rot) * r; val dy = kotlin.math.sin(rot) * r
        paint.shader = LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy, light, dark, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, paint)
        // блик
        paint.shader = RadialGradient(cx - r * 0.32f, cy - r * 0.38f, r * 0.9f, intArrayOf(Color.argb(150, 255, 255, 255), Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, paint)
        paint.shader = null
        // «думаю» — вращающиеся дуги
        if (state == State.THINKING) {
            ring.strokeWidth = r * 0.07f; ring.color = Color.argb(220, 255, 255, 255)
            val rr = r * 1.12f
            val rect = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
            val a = phase * 360f * 2
            c.drawArc(rect, a, 70f, false, ring); c.drawArc(rect, a + 180f, 70f, false, ring)
        }
        // «говорю» — волны
        if (state == State.SPEAKING || (state == State.LISTENING && level > 0.15f)) {
            ring.strokeWidth = r * 0.03f
            for (i in 0..2) {
                val k = ((phase * 3 + i / 3f) % 1f)
                ring.color = Color.argb((160 * (1 - k)).toInt(), Color.red(light), Color.green(light), Color.blue(light))
                c.drawCircle(cx, cy, r * (1.0f + 0.35f * k), ring)
            }
        }
    }
}
