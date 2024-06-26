package kotlinx.coroutines

import kotlin.coroutines.*
import kotlin.jvm.*

/**
 * A coroutine dispatcher that is not confined to any specific thread.
 */
internal object Unconfined : CoroutineDispatcher() {

    override fun limitedParallelism(parallelism: Int, name: String?): CoroutineDispatcher {
        throw UnsupportedOperationException("limitedParallelism is not supported for Dispatchers.Unconfined")
    }

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = false

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        /** It can only be called by the [yield] function. See also code of [yield] function. */
        val yieldContext = context[YieldContext]
        if (yieldContext != null) {
            // report to "yield" that it is an unconfined dispatcher and don't call "block.run()"
            yieldContext.dispatcherWasUnconfined = true
            return
        }
        throw UnsupportedOperationException("Dispatchers.Unconfined.dispatch function can only be used by the yield function. " +
            "If you wrap Unconfined dispatcher in your code, make sure you properly delegate " +
            "isDispatchNeeded and dispatch calls.")
    }
    
    override fun toString(): String = "Dispatchers.Unconfined"
}

/**
 * Used to detect calls to [Unconfined.dispatch] from [yield] function.
 */
@PublishedApi
internal class YieldContext : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<YieldContext>

    @JvmField
    var dispatcherWasUnconfined = false
}
