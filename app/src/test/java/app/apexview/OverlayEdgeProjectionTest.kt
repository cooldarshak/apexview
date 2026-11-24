package app.apexview

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class OverlayEdgeProjectionTest {
    private val geometry = TrackGeometry(floatArrayOf(
        0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f,
    ))
    @Test fun primitiveSortingMatchesStableLegacySortedByProgressIncludingOverlaps() {
        val projection = OverlayEdgeProjection()
        val random = Random(66012L)
        repeat(400) { round ->
            val count = round % 24
            val cars = List(count) { index ->
                // Deliberately collocated markers and ties.
                val x = if (index % 4 == 0) 0f else random.nextInt(100).toFloat()
                val y = if (index % 4 == 0) 0f else random.nextInt(100).toFloat()
                Car(index.toString(), x, y, LapState.UNKNOWN)
            }
            val expected = cars.map { it to geometry.progress(it.x, it.y) }.sortedBy { it.second }
            assertEquals(count, projection.update(cars, geometry))
            for (i in expected.indices) {
                assertSame("incorrect sorted driver at round $round index $i",
                    expected[i].first, projection.car(i))
                assertEquals(expected[i].second, projection.fraction(i), 0f)
            }
        }
        assertEquals(0, projection.update(emptyList(), geometry))
    }
    @Test fun retainedProjectionCanGrowBeyondAnInitialGridWithoutAllocatingEachFrame() {
        val projection = OverlayEdgeProjection()
        val many = List(100) { Car(it.toString(), (it % 20).toFloat(), it.toFloat(), LapState.UNKNOWN) }
        assertEquals(100, projection.update(many, geometry))
        assertEquals(100, projection.update(many, geometry))
        val singleton = listOf(many[2])
        assertEquals(1, projection.update(singleton, geometry))
        assertSame(singleton[0], projection.car(0))
    }
    @Test fun mutableCanvasProjectionRetainsStableDriverOrderAndIdentity() {
        val projection = OverlayEdgeProjection()
        val cars = List(24) { index -> OverlayDrawCar(index.toString()).apply {
            x = (index % 7).toFloat() * 9f
            y = (index % 5).toFloat() * 11f
            state = LapState.UNKNOWN
        } }
        repeat(60) { frame ->
            cars.forEachIndexed { index, car -> car.x = (index * 7 + frame % 3).toFloat() }
            val expected = cars.map { it to geometry.progress(it.x, it.y) }.sortedBy { it.second }
            assertEquals(24, projection.updateForDraw(cars, geometry))
            for (i in expected.indices) {
                assertSame(expected[i].first, projection.drawCar(i))
                assertEquals(expected[i].second, projection.fraction(i), 0f)
            }
        }
        assertEquals(0, projection.updateForDraw(emptyList(), geometry))
        assertEquals(24, projection.updateForDraw(cars, geometry))
    }

    @Test fun castPacketCarsAndLocalDrawCarsShareTheSameStableProjection() {
        val index = OverlayEdgeProjection()
        val immutable: List<OverlayMotionCar> = listOf(
            Car("16", 10f, 10f, LapState.UNKNOWN),
            Car("44", 20f, 20f, LapState.PUSH),
        )
        val mutable: List<OverlayMotionCar> = immutable.map {
            OverlayDrawCar(it.number).apply {
                x = it.x
                y = it.y
                state = it.state
            }
        }
        assertEquals(2, index.updateForDraw(immutable, geometry))
        val orderedIds = (0 until 2).map { index.drawCar(it).number }
        assertEquals(2, index.updateForDraw(mutable, geometry))
        assertEquals(orderedIds, (0 until 2).map { index.drawCar(it).number })
        assertEquals("Cast packet state preserved", LapState.PUSH,
            mutable.first { it.number == "44" }.state)
    }

}
