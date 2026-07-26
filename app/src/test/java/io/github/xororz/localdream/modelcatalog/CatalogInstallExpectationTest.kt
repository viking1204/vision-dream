package io.github.xororz.localdream.modelcatalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogInstallExpectationTest {
    @Test
    fun expectationRoundTripsAndRejectsDetectedBackendMismatch() {
        val expectation = CatalogInstallExpectation(
            backendType = "sd15npu",
            hardwareTarget = "8gen2",
        )

        assertEquals(
            expectation,
            CatalogInstallExpectation.fromJsonString(expectation.toJsonString()),
        )
        assertNull(expectation.validateDetectedBackend("sd15npu"))
        assertEquals(
            "Archive backend does not match the catalog expectation",
            expectation.validateDetectedBackend("sdxl"),
        )
    }

    @Test
    fun hardwareSpecificBackendWithoutTargetIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CatalogInstallExpectation("sd15npu", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CatalogInstallExpectation("sd15cpu", "min")
        }
    }

    @Test
    fun npuTargetMustMatchCurrentChipsetOrPortableMinimum() {
        val profile = CatalogDeviceProfile(
            sdkInt = 35,
            deviceSupported = true,
            chipsetSuffix = "8gen2",
            supportsLargeModels = false,
        )

        assertTrue(
            CatalogDeviceCompatibility.isCompatible(
                CatalogInstallExpectation("sd15npu", "8gen2"),
                profile,
            ),
        )
        assertTrue(
            CatalogDeviceCompatibility.isCompatible(
                CatalogInstallExpectation("sd15npu", "min"),
                profile,
            ),
        )
        assertFalse(
            CatalogDeviceCompatibility.isCompatible(
                CatalogInstallExpectation("sd15npu", "8gen3"),
                profile,
            ),
        )
    }

    @Test
    fun largeBackendsRequireLargeModelSocEvenForMinimumTarget() {
        val ordinaryProfile = CatalogDeviceProfile(
            sdkInt = 35,
            deviceSupported = true,
            chipsetSuffix = "8gen2",
            supportsLargeModels = false,
        )
        val largeProfile = ordinaryProfile.copy(supportsLargeModels = true)
        val expectation = CatalogInstallExpectation("sdxl", "min")

        assertFalse(CatalogDeviceCompatibility.isCompatible(expectation, ordinaryProfile))
        assertTrue(CatalogDeviceCompatibility.isCompatible(expectation, largeProfile))
    }
}
