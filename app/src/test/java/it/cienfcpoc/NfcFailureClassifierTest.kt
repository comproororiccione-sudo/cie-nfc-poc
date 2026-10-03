package it.cienfcpoc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NfcFailureClassifierTest {
    private class PACEException(cause:Throwable?=null):Exception(cause)

    @Test fun ioWrappedByPaceIsInterruptedBeforeRejected(){
        val error=PACEException(IOException("simulated transport loss"))
        assertTrue(NfcFailureClassifier.isInterrupted(error))
        assertFalse(NfcFailureClassifier.isCanRejectedDuringPace(error))
    }

    @Test fun paceWithoutIoCanBeRejected(){
        val error=PACEException()
        assertFalse(NfcFailureClassifier.isInterrupted(error))
        assertTrue(NfcFailureClassifier.isCanRejectedDuringPace(error))
    }
}
