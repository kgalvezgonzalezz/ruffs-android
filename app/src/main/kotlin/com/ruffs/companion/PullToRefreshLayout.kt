package com.ruffs.companion

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.math.abs

/**
 * "Tirar para recargar" al estilo de las apps grandes (Instagram, YouTube…).
 *
 * El problema de la versión de Odysseus: SwipeRefreshLayout ve el dedo antes
 * que la página y, si el gesto va hacia abajo, se queda con él y la página
 * recibe "cancelar". Así se rompían las burbujas/chips arrastrables de RUFFS.
 *
 * Aquí el gesto solo se convierte en recarga si TODO esto se cumple:
 *  - el dedo ya se movió un poco (1,5× el umbral del sistema) y claramente hacia
 *    abajo (no en diagonal ni de lado);
 *  - no hubo pulsación larga antes de moverse (así empiezan los arrastres de
 *    RUFFS: chips flotantes, listas ordenables, tarjetas de notas);
 *  - un solo dedo (no pellizcos);
 *  - la página dice que se puede: lo que hay bajo el dedo está arriba del todo,
 *    no es un elemento arrastrable ni un campo de texto, no hay un modal, y
 *    RUFFS no ha empezado un arrastre ([pageAllowsPull], [pageDragging]).
 *
 * Mientras no se decide, los movimientos siguen llegando a la página, así que
 * los arrastres de RUFFS no se interrumpen nunca.
 */
class PullToRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SwipeRefreshLayout(context, attrs) {

    /** La página: contenido arriba y nada arrastrable bajo el dedo. */
    @Volatile var pageAllowsPull = true

    /** La página: RUFFS empezó a arrastrar algo durante este toque. */
    @Volatile var pageDragging = false

    /** Condición de la app (página cargada, sin teclado, sin ventanas encima…). */
    var canRefresh: () -> Boolean = { true }

    /** Se llama al cruzar el punto en que soltar recarga (true) o al volver atrás (false). */
    var onThresholdChanged: ((Boolean) -> Unit)? = null

    private val config = ViewConfiguration.get(context)
    private val touchSlop = config.scaledTouchSlop
    private val decideDistance = touchSlop * 1.5f
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var decided = false
    private var allowed = false
    private var triggerPx = (64 * resources.displayMetrics.density).toInt()
    private var thresholdReached = false

    override fun setDistanceToTriggerSync(distance: Int) {
        super.setDistanceToTriggerSync(distance)
        triggerPx = distance
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downTime = ev.eventTime
                decided = false
                allowed = false
                thresholdReached = false
                super.onInterceptTouchEvent(ev) // registra el punto inicial; nunca intercepta en DOWN
                return false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Un segundo dedo: es un pellizco, no una recarga.
                decided = true
                allowed = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!decided) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) < decideDistance && abs(dy) < decideDistance) {
                        // Aún no: la página sigue recibiendo el gesto. No se lo
                        // pasamos a SwipeRefreshLayout: su umbral es menor y se
                        // quedaría con el dedo antes de que decidamos.
                        return false
                    }
                    decided = true
                    allowed = dy > 0 &&
                        dy > abs(dx) * 1.7f &&
                        ev.pointerCount == 1 &&
                        ev.eventTime - downTime < longPressMs &&
                        pageAllowsPull &&
                        !pageDragging &&
                        canRefresh()
                }
                if (!allowed || pageDragging) return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasAllowed = allowed
                decided = false
                allowed = false
                if (!wasAllowed) return false
            }
        }
        if (!allowed) return false
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (!isRefreshing) {
                // Mismo cálculo que SwipeRefreshLayout: (y - inicio - umbral) × 0,5
                val pulled = (ev.y - downY - touchSlop) * 0.5f
                val reached = pulled >= triggerPx
                if (reached != thresholdReached) {
                    thresholdReached = reached
                    onThresholdChanged?.invoke(reached)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> thresholdReached = false
        }
        return super.onTouchEvent(ev)
    }

    /** Vibración corta respetando la configuración del sistema. */
    fun tick(kind: Haptic) {
        val constant = when (kind) {
            Haptic.THRESHOLD_ON -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK
            Haptic.THRESHOLD_OFF -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE else HapticFeedbackConstants.CLOCK_TICK
            Haptic.CONFIRM -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.REJECT -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        }
        performHapticFeedback(constant)
    }

    enum class Haptic { THRESHOLD_ON, THRESHOLD_OFF, CONFIRM, REJECT }
}
