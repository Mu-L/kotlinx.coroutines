@file:Suppress("UNCHECKED_CAST")

package kotlinx.coroutines.internal

import kotlinx.coroutines.*
import _COROUTINE.ARTIFICIAL_FRAME_PACKAGE_NAME
import _COROUTINE.ArtificialStackFrames
import java.util.*
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

/*
 * `Class.forName(name).canonicalName` instead of plain `name` is required to properly handle
 * Android's minifier that renames these classes and breaks our recovery heuristic without such lookup.
 */
private const val baseContinuationImplClass = "kotlin.coroutines.jvm.internal.BaseContinuationImpl"
private const val stackTraceRecoveryClass = "kotlinx.coroutines.internal.StackTraceRecoveryKt"

private val ARTIFICIAL_FRAME = ArtificialStackFrames().coroutineBoundary()

private val baseContinuationImplClassName = runCatching {
    Class.forName(baseContinuationImplClass).canonicalName
}.getOrElse { baseContinuationImplClass }

private val stackTraceRecoveryClassName = runCatching {
    Class.forName(stackTraceRecoveryClass).canonicalName
}.getOrElse { stackTraceRecoveryClass }

internal actual fun <E : Throwable> recoverStackTrace(exception: E): E {
    if (!RECOVER_STACK_TRACES) return exception
    // No unwrapping on continuation-less path: exception is not reported multiple times via slow paths
    val copy = tryCopyException(exception) ?: return exception
    return copy.sanitizeStackTrace()
}

private fun <E : Throwable> E.sanitizeStackTrace(): E {
    val stackTrace = stackTrace
    val size = stackTrace.size
    val lastIntrinsic = stackTrace.indexOfLast { stackTraceRecoveryClassName == it.className }
    val startIndex = lastIntrinsic + 1
    val endIndex = stackTrace.firstFrameIndex(baseContinuationImplClassName)
    val adjustment = if (endIndex == -1) 0 else size - endIndex
    val trace = Array(size - lastIntrinsic - adjustment) {
        if (it == 0) {
            ARTIFICIAL_FRAME
        } else {
            stackTrace[startIndex + it - 1]
        }
    }

    setStackTrace(trace)
    return this
}

@Suppress("NOTHING_TO_INLINE") // Inline for better R8 optimization
internal actual inline fun <E : Throwable> recoverStackTrace(exception: E, continuation: Continuation<*>): E {
    if (!RECOVER_STACK_TRACES || continuation !is CoroutineStackFrame) return exception
    return recoverFromStackFrame(exception, continuation)
}

private fun <E : Throwable> recoverFromStackFrame(exception: E, continuation: CoroutineStackFrame): E {
    /*
    * Here we are checking whether exception has already recovered stacktrace.
    * If so, we extract initial and merge recovered stacktrace and current one
    */
    val (cause, recoveredStacktrace) = exception.causeAndStacktrace()

    // Try to create an exception of the same type and get stacktrace from continuation
    val newException = tryCopyException(cause) ?: return exception
    // Update stacktrace
    val stacktrace = createStackTrace(continuation)
    if (stacktrace.isEmpty()) return exception
    // Merge if necessary
    if (cause !== exception) {
        mergeRecoveredTraces(recoveredStacktrace, stacktrace)
    }
    // Take recovered stacktrace, merge it with existing one if necessary and return
    return createFinalException(cause, newException, stacktrace)
}

/*
 * Here we partially copy original exception stackTrace to make current one much prettier.
 * E.g. for
 * ```
 * fun foo() = async { error(...) }
 * suspend fun bar() = foo().await()
 * ```
 * we would like to produce following exception:
 * IllegalStateException
 *   at foo
 *   at kotlin.coroutines.resumeWith
 *   at _COROUTINE._BOUNDARY._(CoroutineDebugging.kt)
 *   at bar
 *   ...real stackTrace...
 * caused by "IllegalStateException" (original one)
 */
private fun <E : Throwable> createFinalException(cause: E, result: E, resultStackTrace: ArrayDeque<StackTraceElement>): E {
    resultStackTrace.addFirst(ARTIFICIAL_FRAME)
    val causeTrace = cause.stackTrace
    val size = causeTrace.firstFrameIndex(baseContinuationImplClassName)
    if (size == -1) {
        result.stackTrace = resultStackTrace.toTypedArray()
        return result
    }

    val mergedStackTrace = arrayOfNulls<StackTraceElement>(resultStackTrace.size + size)
    for (i in 0 until size) {
        mergedStackTrace[i] = causeTrace[i]
    }

    for ((index, element) in resultStackTrace.withIndex()) {
        mergedStackTrace[size + index] = element
    }

    result.stackTrace = mergedStackTrace
    return result
}

/**
 * Find initial cause of the exception without restored stacktrace.
 * Returns intermediate stacktrace as well in order to avoid excess cloning of array as an optimization.
 */
private fun <E : Throwable> E.causeAndStacktrace(): Pair<E, Array<StackTraceElement>> {
    val cause = cause
    return if (cause != null && cause.javaClass == javaClass) {
        val currentTrace = stackTrace
        if (currentTrace.any { it.isArtificial() })
            cause as E to currentTrace
        else this to emptyArray()
    } else {
        this to emptyArray()
    }
}

private fun mergeRecoveredTraces(recoveredStacktrace: Array<StackTraceElement>, result: ArrayDeque<StackTraceElement>) {
    // Merge two stacktraces and trim common prefix
    val startIndex = recoveredStacktrace.indexOfFirst { it.isArtificial() } + 1
    val lastFrameIndex = recoveredStacktrace.size - 1
    for (i in lastFrameIndex downTo startIndex) {
        val element = recoveredStacktrace[i]
        if (element.elementWiseEquals(result.last)) {
            result.removeLast()
        }
        result.addFirst(recoveredStacktrace[i])
    }
}

internal actual suspend inline fun recoverAndThrow(exception: Throwable): Nothing {
    if (!RECOVER_STACK_TRACES) throw exception
    suspendCoroutineUninterceptedOrReturn<Nothing> {
        if (it !is CoroutineStackFrame) throw exception
        throw recoverFromStackFrame(exception, it)
    }
}

@PublishedApi
@Suppress("NOTHING_TO_INLINE") // Inline for better R8 optimizations
internal actual inline fun <E : Throwable> unwrap(exception: E): E =
    if (!RECOVER_STACK_TRACES) exception else unwrapImpl(exception)

@PublishedApi
internal fun <E : Throwable> unwrapImpl(exception: E): E {
    val cause = exception.cause
    // Fast-path to avoid array cloning
    if (cause == null || cause.javaClass != exception.javaClass) {
        return exception
    }
    // Slow path looks for artificial frames in a stack-trace
    if (exception.stackTrace.any { it.isArtificial() }) {
        @Suppress("UNCHECKED_CAST")
        return cause as E
    } else {
        return exception
    }
}

private fun createStackTrace(continuation: CoroutineStackFrame): ArrayDeque<StackTraceElement> {
    val stack = ArrayDeque<StackTraceElement>()
    continuation.getStackTraceElement()?.let { stack.add(it) }

    var last = continuation
    while (true) {
        last = (last as? CoroutineStackFrame)?.callerFrame ?: break
        last.getStackTraceElement()?.let { stack.add(it) }
    }
    return stack
}

internal fun StackTraceElement.isArtificial() = className.startsWith(ARTIFICIAL_FRAME_PACKAGE_NAME)
private fun Array<StackTraceElement>.firstFrameIndex(methodName: String) = indexOfFirst { methodName == it.className }

private fun StackTraceElement.elementWiseEquals(e: StackTraceElement): Boolean {
    /*
     * In order to work on Java 9 where modules and classloaders of enclosing class
     * are part of the comparison
     */
    return lineNumber == e.lineNumber && methodName == e.methodName
            && fileName == e.fileName && className == e.className
}

internal actual typealias CoroutineStackFrame = kotlin.coroutines.jvm.internal.CoroutineStackFrame

internal actual typealias StackTraceElement = java.lang.StackTraceElement

@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
internal actual fun Throwable.initCause(cause: Throwable) {
    // Resolved to member, verified by test
    initCause(cause)
}
