package dev.prashikshit.voicey.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BluetoothRouteSelectionTest {
    @Test fun matchesOutputPortToSavedInputByAddress() {
        val selected = BluetoothEndpoint(1, 7, "buds")
        assertEquals(2, selectBluetoothEndpoint(selected, listOf(
            BluetoothEndpoint(2, 7, "buds"), BluetoothEndpoint(3, 7, "other"),
        )))
    }

    @Test fun prefersExactEndpointAndSupportsBle() {
        val selected = BluetoothEndpoint(2, 26, "")
        assertEquals(2, selectBluetoothEndpoint(selected, listOf(
            selected, BluetoothEndpoint(3, 26, ""),
        )))
    }

    @Test fun neverSubstitutesAnotherHeadsetOrProfile() {
        val selected = BluetoothEndpoint(1, 7, "buds")
        assertNull(selectBluetoothEndpoint(selected, listOf(BluetoothEndpoint(2, 7, "other"))))
        assertNull(selectBluetoothEndpoint(selected, listOf(BluetoothEndpoint(2, 26, "buds"))))
        assertNull(selectBluetoothEndpoint(selected, emptyList()))
    }

    @Test fun missingAddressesRequireAnUnambiguousEndpoint() {
        val selected = BluetoothEndpoint(1, 7, "")
        assertEquals(2, selectBluetoothEndpoint(selected, listOf(BluetoothEndpoint(2, 7, ""))))
        assertNull(selectBluetoothEndpoint(selected, listOf(
            BluetoothEndpoint(2, 7, ""), BluetoothEndpoint(3, 7, ""),
        )))
    }
}
