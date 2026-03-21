package cz.hovorka.kdisco

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test

class LinuxX64SimulationTest {

	private data class Model(
		val random: Random,
		val position: Variable,
		val dynamics: Continuous,
		val driver: Process
	)

	@Test
	fun nativeSimulationMatchesJvmExpectations() = runTest {
		// Expected values come from java.util.Random with seed = 1234L
		val expectedVelocities = listOf(1.6465821602909256, 1.951357710919392)
		val expectedTimes = listOf(0.0, 1.5, 3.5)
		val expectedPosition = 6.372588662275172

		val position = Variable(0.0)
		var velocity = 0.0
		val dynamics = object : Continuous() {
			override fun derivatives() {
				position.rate = velocity
			}
		}
		val timeLog = mutableListOf<Double>()
		val velocityLog = mutableListOf<Double>()

		val model = Model(
			random = Random(1234L),
			position = position,
			dynamics = dynamics,
			driver = object : Process() {
				override suspend fun actions() {
					assertThat(position.isActive()).isFalse()
					assertThat(dynamics.isActive()).isFalse()

					position.start()
					dynamics.start()

					assertThat(position.isActive()).isTrue()
					assertThat(dynamics.isActive()).isTrue()

					timeLog += time()

					velocity = model.random.uniform(1.0, 2.0)
					velocityLog += velocity
					hold(1.5)
					timeLog += time()

					velocity = model.random.uniform(1.0, 2.0)
					velocityLog += velocity
					hold(2.0)
					timeLog += time()

					dynamics.stop()
					position.stop()
				}
			}
		)

		runSimulation(endTime = 3.5) {
			maxAbsError = 1e-9
			maxRelError = 1e-9
			dtMax = 0.05
			Process.activate(model.driver)
		}

		assertThat(timeLog).isEqualTo(expectedTimes)
		assertThat(velocityLog).isEqualTo(expectedVelocities)
		assertThat(abs(position.state - expectedPosition)).isLessThan(1e-6)
		assertThat(position.isActive()).isFalse()
		assertThat(dynamics.isActive()).isFalse()
	}

	@Test
	fun randomSequenceMatchesJvmLcg() = runTest {
		val rng = Random(1234L)
		val expected = listOf(
			0.6465821602909256,
			0.9513577109193919,
			0.8575884598068334,
			0.45823330506267057
		)
		val actual = List(expected.size) { rng.uniform(0.0, 1.0) }
		assertThat(actual).isEqualTo(expected)
	}
}
