package cz.hovorka.kdisco

actual abstract class Reporter actual constructor() : Link() {
    /**
     * Internal jDisco.Reporter delegate that hooks into jDisco's reporter scheduling system.
     * The anonymous class overrides `actions()` to call back into this Kotlin class.
     */
    internal val jDiscoReporterDelegate: jDisco.Reporter = object : jDisco.Reporter() {
        override fun actions() {
            this@Reporter.actions()
        }
    }

    /**
     * Override Link's jDiscoDelegate to use the Reporter's jDisco.Link (which is jDisco.Reporter).
     * jDisco.Reporter extends jDisco.Link, so this is type-safe.
     */
    final override val jDiscoDelegate: jDisco.Link
        get() = jDiscoReporterDelegate

    actual protected open fun actions() {}

    actual open fun start(): Reporter {
        jDiscoReporterDelegate.start()
        return this
    }

    actual open fun stop() {
        jDiscoReporterDelegate.stop()
    }

    actual fun setFrequency(f: Double): Reporter {
        jDiscoReporterDelegate.setFrequency(f)
        return this
    }

    actual fun isActive(): Boolean = jDiscoReporterDelegate.isActive()
}
