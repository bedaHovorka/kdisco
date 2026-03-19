package cz.hovorka.kdisco.engine

internal actual object SimulationContextHolder {
    private val threadLocal = ThreadLocal<SimulationContext?>()
    actual var context: SimulationContext?
        get() = threadLocal.get()
        set(value) { threadLocal.set(value) }
}
