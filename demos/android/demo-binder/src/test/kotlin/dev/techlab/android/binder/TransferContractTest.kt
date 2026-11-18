package dev.techlab.android.binder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferContractTest {
    private val running = TransferUiState.Idle.reduce(TransferIntent.Run)

    @Test fun duplicateStartDoesNotInterruptProgress() {
        val measuring = running.reduce(TransferEvent.Progress("8 MiB"))
        assertEquals(TransferUiState.Running("8 MiB"), measuring)
        assertEquals(measuring, measuring.reduce(TransferIntent.Run))
        assertFalse(measuring.canRun)
        assertTrue(measuring.canCancel)
    }

    @Test fun cancelWaitsForCleanupAndIgnoresLateProgress() {
        val cancelling = running.reduce(TransferIntent.Cancel)
        assertEquals(TransferUiState.Cancelling, cancelling)
        listOf(TransferIntent.Run, TransferIntent.Cancel, TransferEvent.Progress("8 MiB")).forEach {
            assertEquals(cancelling, cancelling.reduce(it))
        }
        listOf(TransferEvent.Cancelled, TransferEvent.Completed("报告"), TransferEvent.Failed("连接断开")).forEach {
            assertEquals(TransferUiState.Cancelled, cancelling.reduce(it))
        }
        assertEquals(running, TransferUiState.Cancelled.reduce(TransferIntent.Run))
    }

    @Test fun successAndFailureCanStartANewStudy() {
        val complete = running.reduce(TransferEvent.Completed("报告"))
        val failed = running.reduce(TransferEvent.Failed("连接超时"))
        assertEquals(TransferUiState.Completed("报告"), complete)
        assertEquals(TransferUiState.Failed("连接超时"), failed)
        listOf(complete, failed).forEach { terminal ->
            assertEquals(terminal, terminal.reduce(TransferIntent.Cancel))
            assertEquals(terminal, terminal.reduce(TransferEvent.Progress("迟到的进度")))
            assertEquals(running, terminal.reduce(TransferIntent.Run))
        }
    }
}
