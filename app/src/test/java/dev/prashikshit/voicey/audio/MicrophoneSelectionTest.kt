package dev.prashikshit.voicey.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneSelectionTest {
    private fun candidate(key: String, kind: MicrophoneKind, id: Int) =
        MicrophoneCandidate(key, kind, id)

    @Test fun automaticWithNothingConnectedUsesThePhoneDefault() {
        val choice = chooseMicrophone(MICROPHONE_AUTO, emptyList(), communicationDeviceId = null)
        assertNull(choice.candidate)
        assertFalse(choice.explicit)
    }

    @Test fun automaticPrefersWiredOrUsbOverBluetooth() {
        val choice = chooseMicrophone(MICROPHONE_AUTO, listOf(
            candidate("bt:aa", MicrophoneKind.BLUETOOTH, 3),
            candidate("usb:mic", MicrophoneKind.USB, 4),
            candidate("wired", MicrophoneKind.WIRED, 5),
        ), communicationDeviceId = 3)
        assertEquals("wired", choice.candidate?.key)
        assertFalse(choice.explicit)
    }

    @Test fun automaticPrefersTheCommunicationDeviceAmongBluetooth() {
        val candidates = listOf(
            candidate("bt:aa", MicrophoneKind.BLUETOOTH, 3),
            candidate("bt:bb", MicrophoneKind.BLUETOOTH, 6),
        )
        assertEquals("bt:bb", chooseMicrophone(MICROPHONE_AUTO, candidates, 6).candidate?.key)
        assertEquals("bt:aa", chooseMicrophone(MICROPHONE_AUTO, candidates, 99).candidate?.key)
        assertEquals("bt:aa", chooseMicrophone(MICROPHONE_AUTO, candidates, null).candidate?.key)
    }

    @Test fun connectedPinIsExplicit() {
        val choice = chooseMicrophone("bt:aa", listOf(
            candidate("bt:aa", MicrophoneKind.BLUETOOTH, 3),
        ), communicationDeviceId = null)
        assertEquals("bt:aa", choice.candidate?.key)
        assertTrue(choice.explicit)
    }

    @Test fun missingPinFallsBackToAutomaticWithoutBeingExplicit() {
        val choice = chooseMicrophone("bt:gone", listOf(
            candidate("usb:mic", MicrophoneKind.USB, 4),
        ), communicationDeviceId = null)
        assertEquals("usb:mic", choice.candidate?.key)
        assertFalse(choice.explicit)
    }

    @Test fun phonePrefersTheBuiltInMicrophone() {
        val choice = chooseMicrophone(MICROPHONE_PHONE, listOf(
            candidate("bt:aa", MicrophoneKind.BLUETOOTH, 3),
            candidate(MICROPHONE_PHONE, MicrophoneKind.PHONE, 15),
        ), communicationDeviceId = 3)
        assertEquals(MICROPHONE_PHONE, choice.candidate?.key)
        assertFalse(choice.explicit)
    }

    @Test fun phoneWithoutABuiltInMicrophoneUsesTheSystemDefault() {
        val choice = chooseMicrophone(MICROPHONE_PHONE, emptyList(), communicationDeviceId = null)
        assertNull(choice.candidate)
        assertFalse(choice.explicit)
    }
}
