package kotlinx.coroutines.internal

import kotlin.coroutines.*

internal actual fun <E: Throwable> recoverStackTrace(exception: E, continuation: Continuation<*>): E = exception
internal actual fun <E: Throwable> recoverStackTrace(exception: E): E = exception

@PublishedApi
internal actual fun <E : Throwable> unwrap(exception: E): E = exception
internal actual suspend inline fun recoverAndThrow(exception: Throwable): Nothing = throw exception

@Suppress("UNUSED")
internal actual interface CoroutineStackFrame {
    public actual val callerFrame: CoroutineStackFrame?
    public actual fun getStackTraceElement(): StackTraceElement?
}

internal actual typealias StackTraceElement = Any

internal actual fun Throwable.initCause(cause: Throwable) {
}
