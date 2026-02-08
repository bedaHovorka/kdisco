package cz.hovorka.kdisco

actual class Variable actual constructor(initialValue: Double) {
    private val jDiscoVariable: jDisco.Variable = jDisco.Variable(initialValue)

    actual var state: Double
        get() = jDiscoVariable.state
        set(value) {
            jDiscoVariable.state = value
        }

    actual var rate: Double
        get() = jDiscoVariable.rate
        set(value) {
            jDiscoVariable.rate = value
        }

    actual fun start(): Variable {
        jDiscoVariable.start()
        return this
    }

    actual fun stop() {
        jDiscoVariable.stop()
    }

    actual fun isActive(): Boolean {
        return jDiscoVariable.isActive()
    }
}
