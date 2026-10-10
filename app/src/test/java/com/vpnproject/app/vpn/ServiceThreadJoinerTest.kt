package com.vpnproject.app.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ServiceThreadJoinerTest {
    @Test
    fun nullAndCurrentThreadAreAlreadyComplete() {
        assertTrue(ServiceThreadJoiner.await(null))
        assertTrue(ServiceThreadJoiner.await(Thread.currentThread()))
    }

    @Test
    fun returnsFalseWhenWorkerDoesNotExitWithinTheBound() {
        val releaseWorker = CountDownLatch(1)
        val worker = Thread { releaseWorker.await() }.apply {
            isDaemon = true
            start()
        }

        try {
            assertFalse(ServiceThreadJoiner.await(worker, timeoutMs = 40L))
        } finally {
            releaseWorker.countDown()
        }
        assertTrue(ServiceThreadJoiner.await(worker, timeoutMs = 1_000L))
    }

    @Test
    fun completedWorkerReturnsTrue() {
        val worker = Thread {}.apply { start() }
        assertTrue(ServiceThreadJoiner.await(worker, timeoutMs = 1_000L))
    }

    @Test
    fun restoresInterruptStatusAfterBoundedWait() {
        val releaseWorker = CountDownLatch(1)
        val worker = Thread { releaseWorker.await() }.apply {
            isDaemon = true
            start()
        }
        val waiterStarted = CountDownLatch(1)
        val waitResult = AtomicBoolean(true)
        val interruptRestored = AtomicBoolean(false)
        val waiter = Thread {
            waiterStarted.countDown()
            waitResult.set(ServiceThreadJoiner.await(worker, timeoutMs = 2_000L))
            interruptRestored.set(Thread.currentThread().isInterrupted)
        }.apply {
            isDaemon = true
            start()
        }

        try {
            assertTrue(waiterStarted.await(1, TimeUnit.SECONDS))
            val waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (waiter.isAlive && waiter.state != Thread.State.TIMED_WAITING && System.nanoTime() < waitDeadline) {
                Thread.yield()
            }
            assertTrue(waiter.state == Thread.State.TIMED_WAITING)
            waiter.interrupt()
            releaseWorker.countDown()
            waiter.join(1_000L)
            assertFalse(waiter.isAlive)
            assertTrue(waitResult.get())
            assertTrue(interruptRestored.get())
        } finally {
            releaseWorker.countDown()
            worker.join(1_000L)
        }
    }
}
