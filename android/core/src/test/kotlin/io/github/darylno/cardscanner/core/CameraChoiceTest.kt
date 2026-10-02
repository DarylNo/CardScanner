package io.github.darylno.cardscanner.core

import io.github.darylno.cardscanner.core.CameraChoice.Facing
import io.github.darylno.cardscanner.core.CameraChoice.Lens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraChoiceTest {
    /** The Nord N200 as Camera2 lists it: 0 main, 1 front, 2 macro, 3 the 2 MP fixed-focus module. */
    private val n200 = listOf(
        Lens("0", Facing.BACK, 12.6, true, 4.7f),
        Lens("1", Facing.FRONT, 16.0, false, 3.0f),
        Lens("2", Facing.BACK, 2.0, false, 1.9f),
        Lens("3", Facing.BACK, 2.0, false, 2.2f),
    )

    @Test fun automaticIsCameraZeroWhenItFacesBack() {
        assertEquals("0", CameraChoice.automatic(n200))
        assertEquals("0", CameraChoice.resolve(n200, null))
    }

    @Test fun automaticFallsBackToTheFirstBackCameraThenExternal() {
        val noZero = listOf(Lens("5", Facing.FRONT), Lens("7", Facing.BACK), Lens("9", Facing.BACK))
        assertEquals("7", CameraChoice.automatic(noZero))
        val frontZero = listOf(Lens("0", Facing.FRONT), Lens("4", Facing.BACK))
        assertEquals("a front camera 0 is never automatic", "4", CameraChoice.automatic(frontZero))
        assertEquals("10", CameraChoice.automatic(listOf(Lens("1", Facing.FRONT), Lens("10", Facing.EXTERNAL))))
        assertNull(CameraChoice.automatic(listOf(Lens("1", Facing.FRONT))))
        assertNull(CameraChoice.automatic(emptyList()))
    }

    @Test fun aSavedChoiceIsUsedOnlyWhenThisPhoneOffersIt() {
        assertEquals("2", CameraChoice.resolve(n200, "2"))
        assertEquals("unknown id → automatic", "0", CameraChoice.resolve(n200, "8"))
        assertEquals("a front camera is never used, even if saved", "0", CameraChoice.resolve(n200, "1"))
    }

    @Test fun frontCamerasAreNotOffered() {
        assertEquals(listOf("0", "2", "3"), CameraChoice.offered(n200).map { it.id })
    }

    @Test fun labelsTellTheLensesApart() {
        assertEquals("Camera 0 · back · 13 MP · autofocus · 4.7 mm", CameraChoice.label(n200[0]))
        assertEquals("Camera 3 · back · 2.0 MP · fixed focus · 2.2 mm", CameraChoice.label(n200[3]))
        assertEquals("Camera 9 · external", CameraChoice.label(Lens("9", Facing.EXTERNAL)))
    }
}
