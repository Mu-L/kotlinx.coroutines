@file:Suppress("DEPRECATION")

package kotlinx.coroutines

import kotlinx.coroutines.testing.*
import kotlin.test.*

class JobTest : TestBase() {
    @Test
    fun testState() {
        val job = Job()
        assertNull(job.parent)
        assertTrue(job.isActive)
        job.cancel()
        assertTrue(!job.isActive)
    }

    @Test
    fun testHandler() {
        val job = Job()
        var fireCount = 0
        job.invokeOnCompletion { fireCount++ }
        assertTrue(job.isActive)
        assertEquals(0, fireCount)
        // cancel once
        job.cancel()
        assertTrue(!job.isActive)
        assertEquals(1, fireCount)
        // cancel again
        job.cancel()
        assertTrue(!job.isActive)
        assertEquals(1, fireCount)
    }

    @Test
    fun testManyHandlers() {
        val job = Job()
        val n = 100 * stressTestMultiplier
        val fireCount = IntArray(n)
        for (i in 0 until n) job.invokeOnCompletion { fireCount[i]++ }
        assertTrue(job.isActive)
        for (i in 0 until n) assertEquals(0, fireCount[i])
        // cancel once
        job.cancel()
        assertTrue(!job.isActive)
        for (i in 0 until n) assertEquals(1, fireCount[i])
        // cancel again
        job.cancel()
        assertTrue(!job.isActive)
        for (i in 0 until n) assertEquals(1, fireCount[i])
    }

    @Test
    fun testUnregisterInHandler() {
        val job = Job()
        val n = 100 * stressTestMultiplier
        val fireCount = IntArray(n)
        for (i in 0 until n) {
            var registration: DisposableHandle? = null
            registration = job.invokeOnCompletion {
                fireCount[i]++
                registration!!.dispose()
            }
        }
        assertTrue(job.isActive)
        for (i in 0 until n) assertEquals(0, fireCount[i])
        // cancel once
        job.cancel()
        assertTrue(!job.isActive)
        for (i in 0 until n) assertEquals(1, fireCount[i])
        // cancel again
        job.cancel()
        assertTrue(!job.isActive)
        for (i in 0 until n) assertEquals(1, fireCount[i])
    }

    @Test
    fun testManyHandlersWithUnregister() {
        val job = Job()
        val n = 100 * stressTestMultiplier
        val fireCount = IntArray(n)
        val registrations = Array<DisposableHandle>(n) { i -> job.invokeOnCompletion { fireCount[i]++ } }
        assertTrue(job.isActive)
        fun unreg(i: Int) = i % 4 <= 1
        for (i in 0 until n) if (unreg(i)) registrations[i].dispose()
        for (i in 0 until n) assertEquals(0, fireCount[i])
        job.cancel()
        assertTrue(!job.isActive)
        for (i in 0 until n) assertEquals(if (unreg(i)) 0 else 1, fireCount[i])
    }

    @Test
    fun testExceptionsInHandler() {
        val job = Job()
        val n = 100 * stressTestMultiplier
        val fireCount = IntArray(n)
        for (i in 0 until n) job.invokeOnCompletion {
            fireCount[i]++
            throw TestException()
        }
        assertTrue(job.isActive)
        for (i in 0 until n) assertEquals(0, fireCount[i])
        val cancelResult = runCatching { job.cancel() }
        assertTrue(!job.isActive)
        for (i in 0 until n) assertEquals(1, fireCount[i])
        assertIs<CompletionHandlerException>(cancelResult.exceptionOrNull())
        assertIs<TestException>(cancelResult.exceptionOrNull()!!.cause)
    }

    @Test
    fun testCancelledParent() {
        val parent = Job()
        parent.cancel()
        assertTrue(!parent.isActive)
        val child = Job(parent)
        assertTrue(!child.isActive)
    }

    @Test
    fun testDisposeSingleHandler() {
        val job = Job()
        var fireCount = 0
        val handler = job.invokeOnCompletion { fireCount++ }
        handler.dispose()
        job.cancel()
        assertEquals(0, fireCount)
    }

    @Test
    fun testDisposeMultipleHandler() {
        val job = Job()
        val handlerCount = 10
        var fireCount = 0
        val handlers = Array(handlerCount) { job.invokeOnCompletion { fireCount++ } }
        handlers.forEach { it.dispose() }
        job.cancel()
        assertEquals(0, fireCount)
    }

    @Test
    fun testCancelAndJoinParentWaitChildren() = runTest {
        expect(1)
        val parent = Job()
        launch(parent, start = CoroutineStart.UNDISPATCHED) {
            expect(2)
            try {
                yield() // will get cancelled
            } finally {
                expect(5)
            }
        }
        expect(3)
        parent.cancel()
        expect(4)
        parent.join()
        finish(6)
    }

    @Test
    fun testOnCancellingHandler() = runTest {
        val job = launch {
            expect(2)
            delay(Long.MAX_VALUE)
        }

        job.invokeOnCompletion(onCancelling = true) {
            assertNotNull(it)
            expect(3)
        }

        expect(1)
        yield()
        job.cancelAndJoin()
        finish(4)
    }

    @Test
    fun testInvokeOnCancellingFiringOnNormalExit() = runTest {
        val job = launch {
            expect(2)
        }
        job.invokeOnCompletion(onCancelling = true) {
            assertNull(it)
            expect(3)
        }
        expect(1)
        job.join()
        finish(4)
    }

    @Test
    fun testOverriddenParent() = runTest {
        val parent = Job()
        val deferred = launch(parent, CoroutineStart.ATOMIC) {
            expect(2)
            delay(Long.MAX_VALUE)
        }

        parent.cancel()
        expect(1)
        deferred.join()
        finish(3)
    }

    @Test
    fun testJobWithParentCancelNormally() {
        val parent = Job()
        val job = Job(parent)
        job.cancel()
        assertTrue(job.isCancelled)
        assertFalse(parent.isCancelled)
    }

    @Test
    fun testJobWithParentCancelException() {
        val parent = Job()
        val job = Job(parent)
        job.completeExceptionally(TestException())
        assertTrue(job.isCancelled)
        assertTrue(parent.isCancelled)
    }

    @Test
    fun testIncompleteJobState() = runTest {
        val parent = coroutineContext.job
        val job = launch {
            coroutineContext[Job]!!.invokeOnCompletion {  }
        }
        assertSame(parent, job.parent)
        job.join()
        assertNull(job.parent)
        assertTrue(job.isCompleted)
        assertFalse(job.isActive)
        assertFalse(job.isCancelled)
    }

    @Test
    fun testChildrenWithIncompleteState() = runTest {
        val job = async { Wrapper() }
        job.join()
        assertTrue(job.children.toList().isEmpty())
    }

    private class Wrapper : Incomplete {
        override val isActive: Boolean
            get() =  error("")
        override val list: NodeList?
            get() = error("")
    }
}
