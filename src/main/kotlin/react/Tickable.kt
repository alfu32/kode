package react

/**
 * Components that need periodic updates can implement this.
 * Return true from [tick] to request a repaint.
 */
interface Tickable {
    fun tick(nowMs: Long): Boolean
}
