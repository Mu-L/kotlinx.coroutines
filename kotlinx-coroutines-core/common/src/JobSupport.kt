@file:Suppress("DEPRECATION_ERROR")

package kotlinx.coroutines

import kotlinx.atomicfu.*
import kotlinx.coroutines.internal.*
import kotlinx.coroutines.selects.*
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*
import kotlin.js.*
import kotlin.jvm.*

/**
 * A concrete implementation of [Job]. It is optionally a child to a parent job.
 *
 * This is an open class designed for extension by more specific classes that might augment the
 * state and mare store addition state information for completed jobs, like their result values.
 *
 * @param active when `true` the job is created in _active_ state, when `false` in _new_ state. See [Job] for details.
 * @suppress **This is unstable API and it is subject to change.**
 */
@OptIn(InternalForInheritanceCoroutinesApi::class)
@Deprecated(level = DeprecationLevel.ERROR, message = "This is internal API and may be removed in the future releases")
public open class JobSupport constructor(active: Boolean) : Job, ChildJob, ParentJob {
    final override val key: CoroutineContext.Key<*> get() = Job

    /*
       === Internal states ===

       name       state class              public state  description
       ------     ------------             ------------  -----------
       EMPTY_N    EmptyNew               : New           no listeners
       EMPTY_A    EmptyActive            : Active        no listeners
       SINGLE     JobNode                : Active        a single listener
       SINGLE+    JobNode                : Active        a single listener + NodeList added as its next
       LIST_N     InactiveNodeList       : New           a list of listeners (promoted once, does not got back to EmptyNew)
       LIST_A     NodeList               : Active        a list of listeners (promoted once, does not got back to JobNode/EmptyActive)
       COMPLETING Finishing              : Completing    has a list of listeners (promoted once from LIST_*)
       CANCELLING Finishing              : Cancelling    -- " --
       FINAL_C    Cancelled              : Cancelled     Cancelled (final state)
       FINAL_R    <any>                  : Completed     produced some result

       === Transitions ===

           New states      Active states       Inactive states

          +---------+       +---------+                          }
          | EMPTY_N | ----> | EMPTY_A | ----+                    } Empty states
          +---------+       +---------+     |                    }
               |  |           |     ^       |    +----------+
               |  |           |     |       +--> |  FINAL_* |
               |  |           V     |       |    +----------+
               |  |         +---------+     |                    }
               |  |         | SINGLE  | ----+                    } JobNode states
               |  |         +---------+     |                    }
               |  |              |          |                    }
               |  |              V          |                    }
               |  |         +---------+     |                    }
               |  +-------> | SINGLE+ | ----+                    }
               |            +---------+     |                    }
               |                 |          |
               V                 V          |
          +---------+       +---------+     |                    }
          | LIST_N  | ----> | LIST_A  | ----+                    } [Inactive]NodeList states
          +---------+       +---------+     |                    }
             |   |             |   |        |
             |   |    +--------+   |        |
             |   |    |            V        |
             |   |    |    +------------+   |   +------------+   }
             |   +-------> | COMPLETING | --+-- | CANCELLING |   } Finishing states
             |        |    +------------+       +------------+   }
             |        |         |                    ^
             |        |         |                    |
             +--------+---------+--------------------+


       This state machine and its transition matrix are optimized for the common case when a job is created in active
       state (EMPTY_A), at most one completion listener is added to it during its life-time, and it completes
       successfully without children (in this case it directly goes from EMPTY_A or SINGLE state to FINAL_R
       state without going to COMPLETING state)

       Note that the actual `_state` variable can also be a reference to atomic operation descriptor `OpDescriptor`

       ---------- TIMELINE of state changes and notification in Job lifecycle ----------

       | The longest possible chain of events in shown, shorter versions cut-through intermediate states,
       |  while still performing all the notifications in this order.

         + Job object is created
       ## NEW: state == EMPTY_NEW | is InactiveNodeList
         + initParentJob / initParentJobInternal (invokes attachChild on its parent, initializes parentHandle)
         ~ waits for start
         >> start / join / await invoked
       ## ACTIVE: state == EMPTY_ACTIVE | is JobNode | is NodeList
         + onStart (lazy coroutine is started)
         ~ active coroutine is working (or scheduled to execution)
         >> childCancelled / cancelImpl invoked
       ## CANCELLING: state is Finishing, state.rootCause != null
        ------ cancelling listeners are not admitted anymore, invokeOnCompletion(onCancelling=true) returns NonDisposableHandle
        ------ new children get immediately cancelled, but are still admitted to the list
         + onCancelling
         + notifyCancelling (invoke all cancelling listeners -- cancel all children, suspended functions resume with exception)
         + cancelParent (rootCause of cancellation is communicated to the parent, parent is cancelled, too)
         ~ waits for completion of coroutine body
         >> makeCompleting / makeCompletingOnce invoked
       ## COMPLETING: state is Finishing, state.isCompleting == true
        ------ new children are not admitted anymore, attachChild returns NonDisposableHandle
         ~ waits for children
         >> last child completes
         - computes the final exception
       ## SEALED: state is Finishing, state.isSealed == true
        ------ cancel/childCancelled returns false (cannot handle exceptions anymore)
         + cancelParent (final exception is communicated to the parent, parent incorporates it)
         + handleJobException ("launch" StandaloneCoroutine invokes CoroutineExceptionHandler)
       ## COMPLETE: state !is Incomplete (CompletedExceptionally | Cancelled)
        ------ completion listeners are not admitted anymore, invokeOnCompletion returns NonDisposableHandle
         + parentHandle.dispose
         + notifyCompletion (invoke all completion listeners)
         + onCompletionInternal / onCompleted / onCancelled

       ---------------------------------------------------------------------------------
     */

    // Note: use shared objects while we have no listeners
    private val _state = atomic<Any?>(if (active) EMPTY_ACTIVE else EMPTY_NEW)

    private val _parentHandle = atomic<ChildHandle?>(null)
    internal var parentHandle: ChildHandle?
        get() = _parentHandle.value
        set(value) { _parentHandle.value = value }

    override val parent: Job?
        get() = parentHandle?.parent

    // ------------ initialization ------------

    /**
     * Initializes parent job.
     * It shall be invoked at most once after construction after all other initialization.
     */
    protected fun initParentJob(parent: Job?) {
        assert { parentHandle == null }
        if (parent == null) {
            parentHandle = NonDisposableHandle
            return
        }
        parent.start() // make sure the parent is started
        val handle = parent.attachChild(this)
        parentHandle = handle
        // now check our state _after_ registering (see tryFinalizeSimpleState order of actions)
        if (isCompleted) {
            handle.dispose()
            parentHandle = NonDisposableHandle // release it just in case, to aid GC
        }
    }

    // ------------ state query ------------
    /**
     * Returns current state of this job.
     * If final state of the job is [Incomplete], then it is boxed into [IncompleteStateBox]
     * and should be [unboxed][unboxState] before returning to user code.
     */
    internal val state: Any? get() = _state.value

    /**
     * @suppress **This is unstable API and it is subject to change.**
     */
    private inline fun loopOnState(block: (Any?) -> Unit): Nothing {
        while (true) {
            block(state)
        }
    }

    public override val isActive: Boolean get() {
        val state = this.state
        return state is Incomplete && state.isActive
    }

    public final override val isCompleted: Boolean get() = state !is Incomplete

    public final override val isCancelled: Boolean get() {
        val state = this.state
        return state is CompletedExceptionally || (state is Finishing && state.isCancelling)
    }

    // ------------ state update ------------

    // Finalizes Finishing -> Completed (terminal state) transition.
    // ## IMPORTANT INVARIANT: Only one thread can be concurrently invoking this method.
    // Returns final state that was created and updated to
    private fun finalizeFinishingState(state: Finishing, proposedUpdate: Any?): Any? {
        /*
         * Note: proposed state can be Incomplete, e.g.
         * async {
         *     something.invokeOnCompletion {} // <- returns handle which implements Incomplete under the hood
         * }
         */
        assert { this.state === state } // consistency check -- it cannot change
        assert { !state.isSealed } // consistency check -- cannot be sealed yet
        assert { state.isCompleting } // consistency check -- must be marked as completing
        val proposedException = (proposedUpdate as? CompletedExceptionally)?.cause
        // Create the final exception and seal the state so that no more exceptions can be added
        val wasCancelling: Boolean
        val finalException = synchronized(state) {
            wasCancelling = state.isCancelling
            val exceptions = state.sealLocked(proposedException)
            val finalCause = getFinalRootCause(state, exceptions)
            if (finalCause != null) addSuppressedExceptions(finalCause, exceptions)
            finalCause
        }
        // Create the final state object
        val finalState = when {
            // was not cancelled (no exception) -> use proposed update value
            finalException == null -> proposedUpdate
            // small optimization when we can used proposeUpdate object as is on cancellation
            finalException === proposedException -> proposedUpdate
            // cancelled job final state
            else -> CompletedExceptionally(finalException)
        }
        // Now handle the final exception
        if (finalException != null) {
            val handled = cancelParent(finalException) || handleJobException(finalException)
            if (handled) (finalState as CompletedExceptionally).makeHandled()
        }
        // Process state updates for the final state before the state of the Job is actually set to the final state
        // to avoid races where outside observer may see the job in the final state, yet exception is not handled yet.
        if (!wasCancelling) onCancelling(finalException)
        onCompletionInternal(finalState)
        // Then CAS to completed state -> it must succeed
        val casSuccess = _state.compareAndSet(state, finalState.boxIncomplete())
        assert { casSuccess }
        // And process all post-completion actions
        completeStateFinalization(state, finalState)
        return finalState
    }

    private fun getFinalRootCause(state: Finishing, exceptions: List<Throwable>): Throwable? {
        // A case of no exceptions
        if (exceptions.isEmpty()) {
            // materialize cancellation exception if it was not materialized yet
            if (state.isCancelling) return defaultCancellationException()
            return null
        }
        /*
         * 1) If we have non-CE, use it as root cause
         * 2) If our original cause was TCE, use *non-original* TCE because of the special nature of TCE
         *    - It is a CE, so it's not reported by children
         *    - The first instance (cancellation cause) is created by timeout coroutine and has no meaningful stacktrace
         *    - The potential second instance is thrown by withTimeout lexical block itself, then it has recovered stacktrace
         * 3) Just return the very first CE
         */
        val firstNonCancellation = exceptions.firstOrNull { it !is CancellationException }
        if (firstNonCancellation != null) return firstNonCancellation
        val first = exceptions[0]
        if (first is TimeoutCancellationException) {
            val detailedTimeoutException = exceptions.firstOrNull { it !== first && it is TimeoutCancellationException }
            if (detailedTimeoutException != null) return detailedTimeoutException
        }
        return first
    }

    private fun addSuppressedExceptions(rootCause: Throwable, exceptions: List<Throwable>) {
        if (exceptions.size <= 1) return // nothing more to do here
        val seenExceptions = identitySet<Throwable>(exceptions.size)
        /*
         * Note that root cause may be a recovered exception as well.
         * To avoid cycles we unwrap the root cause and check for self-suppression against unwrapped cause,
         * but add suppressed exceptions to the recovered root cause (as it is our final exception)
         */
        val unwrappedCause = unwrap(rootCause)
        for (exception in exceptions) {
            val unwrapped = unwrap(exception)
            if (unwrapped !== rootCause && unwrapped !== unwrappedCause &&
                unwrapped !is CancellationException && seenExceptions.add(unwrapped)) {
                rootCause.addSuppressed(unwrapped)
            }
        }
    }

    // fast-path method to finalize normally completed coroutines without children
    // returns true if complete, and afterCompletion(update) shall be called
    private fun tryFinalizeSimpleState(state: Incomplete, update: Any?): Boolean {
        assert { state is Empty || state is JobNode } // only simple state without lists where children can concurrently add
        assert { update !is CompletedExceptionally } // only for normal completion
        if (!_state.compareAndSet(state, update.boxIncomplete())) return false
        onCancelling(null) // simple state is not a failure
        onCompletionInternal(update)
        completeStateFinalization(state, update)
        return true
    }

    // suppressed == true when any exceptions were suppressed while building the final completion cause
    private fun completeStateFinalization(state: Incomplete, update: Any?) {
        /*
         * Now the job in THE FINAL state. We need to properly handle the resulting state.
         * Order of various invocations here is important.
         *
         * 1) Unregister from parent job.
         */
        parentHandle?.let {
            it.dispose() // volatile read parentHandle _after_ state was updated
            parentHandle = NonDisposableHandle // release it just in case, to aid GC
        }
        val cause = (update as? CompletedExceptionally)?.cause
        /*
         * 2) Invoke completion handlers: .join(), callbacks etc.
         *    It's important to invoke them only AFTER exception handling and everything else, see #208
         */
        if (state is JobNode) { // SINGLE/SINGLE+ state -- one completion handler (common case)
            try {
                state.invoke(cause)
            } catch (ex: Throwable) {
                handleOnCompletionException(CompletionHandlerException("Exception in completion handler $state for $this", ex))
            }
        } else {
            state.list?.notifyCompletion(cause)
        }
    }

    private fun notifyCancelling(list: NodeList, cause: Throwable) {
        // first cancel our own children
        onCancelling(cause)
        list.close(LIST_CANCELLATION_PERMISSION)
        notifyHandlers(list, cause) { it.onCancelling }
        // then cancel parent
        cancelParent(cause) // tentative cancellation -- does not matter if there is no parent
    }

    /**
     * The method that is invoked when the job is cancelled to possibly propagate cancellation to the parent.
     * Returns `true` if the parent is responsible for handling the exception, `false` otherwise.
     *
     * Invariant: never returns `false` for instances of [CancellationException], otherwise such exception
     * may leak to the [CoroutineExceptionHandler].
     */
    private fun cancelParent(cause: Throwable): Boolean {
        // Is scoped coroutine -- don't propagate, will be rethrown
        if (isScopedCoroutine) return true

        /* CancellationException is considered "normal" and parent usually is not cancelled when child produces it.
         * This allow parent to cancel its children (normally) without being cancelled itself, unless
         * child crashes and produce some other exception during its completion.
         */
        val isCancellation = cause is CancellationException
        val parent = parentHandle
        // No parent -- ignore CE, report other exceptions.
        if (parent === null || parent === NonDisposableHandle) {
            return isCancellation
        }

        // Notify parent but don't forget to check cancellation
        return parent.childCancelled(cause) || isCancellation
    }

    private fun NodeList.notifyCompletion(cause: Throwable?) {
        close(LIST_ON_COMPLETION_PERMISSION)
        notifyHandlers(this, cause) { true }
    }

    private inline fun notifyHandlers(list: NodeList, cause: Throwable?, predicate: (JobNode) -> Boolean) {
        var exception: Throwable? = null
        list.forEach { node ->
            if (node is JobNode && predicate(node)) {
                try {
                    node.invoke(cause)
                } catch (ex: Throwable) {
                    exception?.apply { addSuppressed(ex) } ?: run {
                        exception = CompletionHandlerException("Exception in completion handler $node for $this", ex)
                    }
                }
            }
        }
        exception?.let { handleOnCompletionException(it) }
    }

    public final override fun start(): Boolean {
        loopOnState { state ->
            when (startInternal(state)) {
                FALSE -> return false
                TRUE -> return true
            }
        }
    }

    // returns: RETRY/FALSE/TRUE:
    //   FALSE when not new,
    //   TRUE  when started
    //   RETRY when need to retry
    private fun startInternal(state: Any?): Int {
        when (state) {
            is Empty -> { // EMPTY_X state -- no completion handlers
                if (state.isActive) return FALSE // already active
                if (!_state.compareAndSet(state, EMPTY_ACTIVE)) return RETRY
                onStart()
                return TRUE
            }
            is InactiveNodeList -> { // LIST state -- inactive with a list of completion handlers
                if (!_state.compareAndSet(state, state.list)) return RETRY
                onStart()
                return TRUE
            }
            else -> return FALSE // not a new state
        }
    }

    /**
     * Override to provide the actual [start] action.
     * This function is invoked exactly once when non-active coroutine is [started][start].
     */
    protected open fun onStart() {}

    public final override fun getCancellationException(): CancellationException =
        when (val state = this.state) {
            is Finishing -> state.rootCause?.toCancellationException("$classSimpleName is cancelling")
                ?: error("Job is still new or active: $this")
            is Incomplete -> error("Job is still new or active: $this")
            is CompletedExceptionally -> state.cause.toCancellationException()
            else -> JobCancellationException("$classSimpleName has completed normally", null, this)
        }

    protected fun Throwable.toCancellationException(message: String? = null): CancellationException =
        this as? CancellationException ?: defaultCancellationException(message, this)

    /**
     * Returns the cause that signals the completion of this job -- it returns the original
     * [cancel] cause, [CancellationException] or **`null` if this job had completed normally**.
     * This function throws [IllegalStateException] when invoked for an job that has not [completed][isCompleted] nor
     * is being cancelled yet.
     */
    protected val completionCause: Throwable?
        get() = when (val state = state) {
            is Finishing -> state.rootCause
                ?: error("Job is still new or active: $this")
            is Incomplete -> error("Job is still new or active: $this")
            is CompletedExceptionally -> state.cause
            else -> null
        }

    /**
     * Returns `true` when [completionCause] exception was handled by parent coroutine.
     */
    protected val completionCauseHandled: Boolean
        get() = state.let { it is CompletedExceptionally && it.handled }

    public final override fun invokeOnCompletion(handler: CompletionHandler): DisposableHandle =
        invokeOnCompletionInternal(
            invokeImmediately = true,
            node = InvokeOnCompletion(handler),
        )

    public final override fun invokeOnCompletion(onCancelling: Boolean, invokeImmediately: Boolean, handler: CompletionHandler): DisposableHandle =
        invokeOnCompletionInternal(
            invokeImmediately = invokeImmediately,
            node = if (onCancelling) {
                InvokeOnCancelling(handler)
            } else {
                InvokeOnCompletion(handler)
            }
        )

    internal fun invokeOnCompletionInternal(
        invokeImmediately: Boolean,
        node: JobNode
    ): DisposableHandle {
        node.job = this
        // Create node upfront -- for common cases it just initializes JobNode.job field,
        // for user-defined handlers it allocates a JobNode object that we might not need, but this is Ok.
        val added = tryPutNodeIntoList(node) { state, list ->
            if (node.onCancelling) {
                /**
                 * We are querying whether the job was already cancelled when we entered this block.
                 * We can't naively attempt to add the node to the list, because a lot of time could pass between
                 * notifying the cancellation handlers (and thus closing the list, forcing us to retry)
                 * and reaching a final state.
                 *
                 * Alternatively, we could also try to add the node to the list first and then read the latest state
                 * to check for an exception, but that logic would need to manually handle the final state, which is
                 * less straightforward.
                 */
                val rootCause = (state as? Finishing)?.rootCause
                if (rootCause == null) {
                    /**
                     * There is no known root cause yet, so we can add the node to the list of state handlers.
                     *
                     * If this call fails, because of the bitmask, this means one of the two happened:
                     * - [notifyCancelling] was already called.
                     *   This means that the job is already being cancelled: otherwise, with what exception would we
                     *   notify the handler?
                     *   So, we can retry the operation: either the state is already final, or the `rootCause` check
                     *   above will give a different result.
                     * - [notifyCompletion] was already called.
                     *   This means that the job is already complete.
                     *   We can retry the operation and will observe the final state.
                     */
                    list.addLast(node, LIST_CANCELLATION_PERMISSION or LIST_ON_COMPLETION_PERMISSION)
                } else {
                    /**
                     * The root cause is known, so we can invoke the handler immediately and avoid adding it.
                     */
                    if (invokeImmediately) node.invoke(rootCause)
                    return NonDisposableHandle
                }
            } else {
                /**
                 * The non-[onCancelling]-handlers are interested in completions only, so it's safe to add them at
                 * any time before [notifyCompletion] is called (which closes the list).
                 *
                 * If the list *is* closed, on a retry, we'll observe the final state, as [notifyCompletion] is only
                 * called after the state transition.
                 */
                list.addLast(node, LIST_ON_COMPLETION_PERMISSION)
            }
        }
        when {
            added -> return node
            invokeImmediately -> node.invoke((state as? CompletedExceptionally)?.cause)
        }
        return NonDisposableHandle
    }

    /**
     * Puts [node] into the current state's list of completion handlers.
     *
     * Returns `false` if the state is already complete and doesn't accept new handlers.
     * Returns `true` if the handler was successfully added to the list.
     *
     * [tryAdd] is invoked when the state is [Incomplete] and the list is not `null`, to decide on the specific
     * behavior in this case. It must return
     * - `true` if the element was successfully added to the list
     * - `false` if the operation needs to be retried
     */
    private inline fun tryPutNodeIntoList(
        node: JobNode,
        tryAdd: (Incomplete, NodeList) -> Boolean
    ): Boolean {
        loopOnState { state ->
            when (state) {
                is Empty -> { // EMPTY_X state -- no completion handlers
                    if (state.isActive) {
                        // try to move to the SINGLE state
                        if (_state.compareAndSet(state, node)) return true
                    } else
                        promoteEmptyToNodeList(state) // that way we can add listener for non-active coroutine
                }
                is Incomplete -> when (val list = state.list) {
                    null -> promoteSingleToNodeList(state as JobNode)
                    else -> if (tryAdd(state, list)) return true
                }
                else -> return false
            }
        }
    }

    private fun promoteEmptyToNodeList(state: Empty) {
        // try to promote it to LIST state with the corresponding state
        val list = NodeList()
        val update = if (state.isActive) list else InactiveNodeList(list)
        _state.compareAndSet(state, update)
    }

    private fun promoteSingleToNodeList(state: JobNode) {
        // try to promote it to list (SINGLE+ state)
        state.addOneIfEmpty(NodeList())
        // it must be in SINGLE+ state or state has changed (node could have need removed from state)
        val list = state.nextNode // either our NodeList or somebody else won the race, updated state
        // just attempt converting it to list if state is still the same, then we'll continue lock-free loop
        _state.compareAndSet(state, list)
    }

    public final override suspend fun join() {
        if (!joinInternal()) { // fast-path no wait
            coroutineContext.ensureActive()
            return // do not suspend
        }
        return joinSuspend() // slow-path wait
    }

    private fun joinInternal(): Boolean {
        loopOnState { state ->
            if (state !is Incomplete) return false // not active anymore (complete) -- no need to wait
            if (startInternal(state) >= 0) return true // wait unless need to retry
        }
    }

    private suspend fun joinSuspend() = suspendCancellableCoroutine<Unit> { cont ->
        // We have to invoke join() handler only on cancellation, on completion we will be resumed regularly without handlers
        cont.disposeOnCancellation(invokeOnCompletion(handler = ResumeOnCompletion(cont)))
    }

    @Suppress("UNCHECKED_CAST")
    public final override val onJoin: SelectClause0
        get() = SelectClause0Impl(
            clauseObject = this@JobSupport,
            regFunc = JobSupport::registerSelectForOnJoin as RegistrationFunction
        )

    @Suppress("UNUSED_PARAMETER")
    private fun registerSelectForOnJoin(select: SelectInstance<*>, ignoredParam: Any?) {
        if (!joinInternal()) {
            select.selectInRegistrationPhase(Unit)
            return
        }
        val disposableHandle = invokeOnCompletion(handler = SelectOnJoinCompletionHandler(select))
        select.disposeOnCompletion(disposableHandle)
    }

    private inner class SelectOnJoinCompletionHandler(
        private val select: SelectInstance<*>
    ) : JobNode() {
        override val onCancelling: Boolean get() = false
        override fun invoke(cause: Throwable?) {
            select.trySelect(this@JobSupport, Unit)
        }
    }

    /**
     * @suppress **This is unstable API and it is subject to change.**
     */
    internal fun removeNode(node: JobNode) {
        // remove logic depends on the state of the job
        loopOnState { state ->
            when (state) {
                is JobNode -> { // SINGE/SINGLE+ state -- one completion handler
                    if (state !== node) return // a different job node --> we were already removed
                    // try remove and revert back to empty state
                    if (_state.compareAndSet(state, EMPTY_ACTIVE)) return
                }
                is Incomplete -> { // may have a list of completion handlers
                    // remove node from the list if there is a list
                    if (state.list != null) node.remove()
                    return
                }
                else -> return // it is complete and does not have any completion handlers
            }
        }
    }

    /**
     * Returns `true` for job that do not have "body block" to complete and should immediately go into
     * completing state and start waiting for children.
     *
     * @suppress **This is unstable API and it is subject to change.**
     */
    internal open val onCancelComplete: Boolean get() = false

    // external cancel with cause, never invoked implicitly from internal machinery
    public override fun cancel(cause: CancellationException?) {
        cancelInternal(cause ?: defaultCancellationException())
    }

    protected open fun cancellationExceptionMessage(): String = "Job was cancelled"

    // HIDDEN in Job interface. Invoked only by legacy compiled code.
    // external cancel with (optional) cause, never invoked implicitly from internal machinery
    @Deprecated(level = DeprecationLevel.HIDDEN, message = "Added since 1.2.0 for binary compatibility with versions <= 1.1.x")
    public override fun cancel(cause: Throwable?): Boolean {
        cancelInternal(cause?.toCancellationException() ?: defaultCancellationException())
        return true
    }

    // It is overridden in channel-linked implementation
    public open fun cancelInternal(cause: Throwable) {
        cancelImpl(cause)
    }

    // Parent is cancelling child
    public final override fun parentCancelled(parentJob: ParentJob) {
        cancelImpl(parentJob)
    }

    /**
     * Child was cancelled with a cause.
     * In this method parent decides whether it cancels itself (e.g. on a critical failure) and whether it handles the exception of the child.
     * It is overridden in supervisor implementations to completely ignore any child cancellation.
     * Returns `true` if exception is handled, `false` otherwise (then caller is responsible for handling an exception)
     *
     * Invariant: never returns `false` for instances of [CancellationException], otherwise such exception
     * may leak to the [CoroutineExceptionHandler].
     */
    public open fun childCancelled(cause: Throwable): Boolean {
        if (cause is CancellationException) return true
        return cancelImpl(cause) && handlesException
    }

    /**
     * Makes this [Job] cancelled with a specified [cause].
     * It is used in [AbstractCoroutine]-derived classes when there is an internal failure.
     */
    public fun cancelCoroutine(cause: Throwable?): Boolean = cancelImpl(cause)

    // cause is Throwable or ParentJob when cancelChild was invoked
    // returns true is exception was handled, false otherwise
    internal fun cancelImpl(cause: Any?): Boolean {
        var finalState: Any? = COMPLETING_ALREADY
        if (onCancelComplete) {
            // make sure it is completing, if cancelMakeCompleting returns state it means it had make it
            // completing and had recorded exception
            finalState = cancelMakeCompleting(cause)
            if (finalState === COMPLETING_WAITING_CHILDREN) return true
        }
        if (finalState === COMPLETING_ALREADY) {
            finalState = makeCancelling(cause)
        }
        return when {
            finalState === COMPLETING_ALREADY -> true
            finalState === COMPLETING_WAITING_CHILDREN -> true
            finalState === TOO_LATE_TO_CANCEL -> false
            else -> {
                afterCompletion(finalState)
                true
            }
        }
    }

    // cause is Throwable or ParentJob when cancelChild was invoked
    // It contains a loop and never returns COMPLETING_RETRY, can return
    // COMPLETING_ALREADY -- if already completed/completing
    // COMPLETING_WAITING_CHILDREN -- if started waiting for children
    // final state -- when completed, for call to afterCompletion
    private fun cancelMakeCompleting(cause: Any?): Any? {
        loopOnState { state ->
            if (state !is Incomplete || state is Finishing && state.isCompleting) {
                // already completed/completing, do not even create exception to propose update
                return COMPLETING_ALREADY
            }
            val proposedUpdate = CompletedExceptionally(createCauseException(cause))
            val finalState = tryMakeCompleting(state, proposedUpdate)
            if (finalState !== COMPLETING_RETRY) return finalState
        }
    }

    @Suppress("NOTHING_TO_INLINE") // Save a stack frame
    internal inline fun defaultCancellationException(message: String? = null, cause: Throwable? = null) =
        JobCancellationException(message ?: cancellationExceptionMessage(), cause, this)

    override fun getChildJobCancellationCause(): CancellationException {
        // determine root cancellation cause of this job (why is it cancelling its children?)
        val state = this.state
        val rootCause = when (state) {
            is Finishing -> state.rootCause
            is CompletedExceptionally -> state.cause
            is Incomplete -> error("Cannot be cancelling child in this state: $state")
            else -> null // create exception with the below code on normal completion
        }
        return (rootCause as? CancellationException) ?: JobCancellationException("Parent job is ${stateString(state)}", rootCause, this)
    }

    // cause is Throwable or ParentJob when cancelChild was invoked
    private fun createCauseException(cause: Any?): Throwable = when (cause) {
        is Throwable? -> cause ?: defaultCancellationException()
        else -> (cause as ParentJob).getChildJobCancellationCause()
    }

    // transitions to Cancelling state
    // cause is Throwable or ParentJob when cancelChild was invoked
    // It contains a loop and never returns COMPLETING_RETRY, can return
    // COMPLETING_ALREADY -- if already completing or successfully made cancelling, added exception
    // COMPLETING_WAITING_CHILDREN -- if started waiting for children, added exception
    // TOO_LATE_TO_CANCEL -- too late to cancel, did not add exception
    // final state -- when completed, for call to afterCompletion
    private fun makeCancelling(cause: Any?): Any? {
        var causeExceptionCache: Throwable? = null // lazily init result of createCauseException(cause)
        loopOnState { state ->
            when (state) {
                is Finishing -> { // already finishing -- collect exceptions
                    val notifyRootCause = synchronized(state) {
                        if (state.isSealed) return TOO_LATE_TO_CANCEL // already sealed -- cannot add exception nor mark cancelled
                        // add exception, do nothing is parent is cancelling child that is already being cancelled
                        val wasCancelling = state.isCancelling // will notify if was not cancelling
                        // Materialize missing exception if it is the first exception (otherwise -- don't)
                        if (cause != null || !wasCancelling) {
                            val causeException = causeExceptionCache ?: createCauseException(cause).also { causeExceptionCache = it }
                            state.addExceptionLocked(causeException)
                        }
                        // take cause for notification if was not in cancelling state before
                        state.rootCause.takeIf { !wasCancelling }
                    }
                    notifyRootCause?.let { notifyCancelling(state.list, it) }
                    return COMPLETING_ALREADY
                }
                is Incomplete -> {
                    // Not yet finishing -- try to make it cancelling
                    val causeException = causeExceptionCache ?: createCauseException(cause).also { causeExceptionCache = it }
                    if (state.isActive) {
                        // active state becomes cancelling
                        if (tryMakeCancelling(state, causeException)) return COMPLETING_ALREADY
                    } else {
                        // non active state starts completing
                        val finalState = tryMakeCompleting(state, CompletedExceptionally(causeException))
                        when {
                            finalState === COMPLETING_ALREADY -> error("Cannot happen in $state")
                            finalState === COMPLETING_RETRY -> return@loopOnState
                            else -> return finalState
                        }
                    }
                }
                else -> return TOO_LATE_TO_CANCEL // already complete
            }
        }
    }

    // Performs promotion of incomplete coroutine state to NodeList for the purpose of
    // converting coroutine state to Cancelling, returns null when need to retry
    private fun getOrPromoteCancellingList(state: Incomplete): NodeList? = state.list ?:
        when (state) {
            is Empty -> NodeList() // we can allocate new empty list that'll get integrated into Cancelling state
            is JobNode -> {
                // SINGLE/SINGLE+ must be promoted to NodeList first, because otherwise we cannot
                // correctly capture a reference to it
                promoteSingleToNodeList(state)
                null // retry
            }
            else -> error("State should have list: $state")
        }

    // try make new Cancelling state on the condition that we're still in the expected state
    private fun tryMakeCancelling(state: Incomplete, rootCause: Throwable): Boolean {
        assert { state !is Finishing } // only for non-finishing states
        assert { state.isActive } // only for active states
        // get state's list or else promote to list to correctly operate on child lists
        val list = getOrPromoteCancellingList(state) ?: return false
        // Create cancelling state (with rootCause!)
        val cancelling = Finishing(list, false, rootCause)
        if (!_state.compareAndSet(state, cancelling)) return false
        // Notify listeners
        notifyCancelling(list, rootCause)
        return true
    }

    /**
     * Completes this job. Used by [CompletableDeferred.complete] (and exceptionally)
     * and by [JobImpl.cancel]. It returns `false` on repeated invocation
     * (when this job is already completing).
     */
    internal fun makeCompleting(proposedUpdate: Any?): Boolean {
        loopOnState { state ->
            val finalState = tryMakeCompleting(state, proposedUpdate)
            when {
                finalState === COMPLETING_ALREADY -> return false
                finalState === COMPLETING_WAITING_CHILDREN -> return true
                finalState === COMPLETING_RETRY -> return@loopOnState
                else -> {
                    afterCompletion(finalState)
                    return true
                }
            }
        }
    } 

    /**
     * Completes this job. Used by [AbstractCoroutine.resume].
     * It throws [IllegalStateException] on repeated invocation (when this job is already completing).
     * Returns:
     * - [COMPLETING_WAITING_CHILDREN] if started waiting for children.
     * - Final state otherwise (caller should do [afterCompletion])
     */
    internal fun makeCompletingOnce(proposedUpdate: Any?): Any? {
        loopOnState { state ->
            val finalState = tryMakeCompleting(state, proposedUpdate)
            when {
                finalState === COMPLETING_ALREADY ->
                    throw IllegalStateException(
                        "Job $this is already complete or completing, " +
                            "but is being completed with $proposedUpdate", proposedUpdate.exceptionOrNull
                    )
                finalState === COMPLETING_RETRY -> return@loopOnState
                else -> return finalState // COMPLETING_WAITING_CHILDREN or final state
            }
        }
    }

    // Returns one of COMPLETING symbols or final state:
    // COMPLETING_ALREADY -- when already complete or completing
    // COMPLETING_RETRY -- when need to retry due to interference
    // COMPLETING_WAITING_CHILDREN -- when made completing and is waiting for children
    // final state -- when completed, for call to afterCompletion
    private fun tryMakeCompleting(state: Any?, proposedUpdate: Any?): Any? {
        if (state !is Incomplete)
            return COMPLETING_ALREADY
        /*
         * FAST PATH -- no children to wait for && simple state (no list) && not cancelling => can complete immediately
         * Cancellation (failures) always have to go through Finishing state to serialize exception handling.
         * Otherwise, there can be a race between (completed state -> handled exception and newly attached child/join)
         * which may miss unhandled exception.
         */
        if ((state is Empty || state is JobNode) && state !is ChildHandleNode && proposedUpdate !is CompletedExceptionally) {
            if (tryFinalizeSimpleState(state, proposedUpdate)) {
                // Completed successfully on fast path -- return updated state
                return proposedUpdate
            }
            return COMPLETING_RETRY
        }
        // The separate slow-path function to simplify profiling
        return tryMakeCompletingSlowPath(state, proposedUpdate)
    }

    // Returns one of COMPLETING symbols or final state:
    // COMPLETING_ALREADY -- when already complete or completing
    // COMPLETING_RETRY -- when need to retry due to interference
    // COMPLETING_WAITING_CHILDREN -- when made completing and is waiting for children
    // final state -- when completed, for call to afterCompletion
    private fun tryMakeCompletingSlowPath(state: Incomplete, proposedUpdate: Any?): Any? {
        // get state's list or else promote to list to correctly operate on child lists
        val list = getOrPromoteCancellingList(state) ?: return COMPLETING_RETRY
        // promote to Finishing state if we are not in it yet
        // This promotion has to be atomic w.r.t to state change, so that a coroutine that is not active yet
        // atomically transition to finishing & completing state
        val finishing = state as? Finishing ?: Finishing(list, false, null)
        // must synchronize updates to finishing state
        val notifyRootCause: Throwable?
        synchronized(finishing) {
            // check if this state is already completing
            if (finishing.isCompleting) return COMPLETING_ALREADY
            // mark as completing
            finishing.isCompleting = true
            // if we need to promote to finishing, then atomically do it here.
            // We do it as early is possible while still holding the lock. This ensures that we cancelImpl asap
            // (if somebody else is faster) and we synchronize all the threads on this finishing lock asap.
            if (finishing !== state) {
                if (!_state.compareAndSet(state, finishing)) return COMPLETING_RETRY
            }
            // ## IMPORTANT INVARIANT: Only one thread (that had set isCompleting) can go past this point
            assert { !finishing.isSealed } // cannot be sealed
            // add new proposed exception to the finishing state
            val wasCancelling = finishing.isCancelling
            (proposedUpdate as? CompletedExceptionally)?.let { finishing.addExceptionLocked(it.cause) }
            // If it just becomes cancelling --> must process cancelling notifications
            notifyRootCause = finishing.rootCause.takeIf { !wasCancelling }
        }
        // process cancelling notification here -- it cancels all the children _before_ we start to wait them (sic!!!)
        notifyRootCause?.let { notifyCancelling(list, it) }
        // now wait for children
        // we can't close the list yet: while there are active children, adding new ones is still allowed.
        val child = list.nextChild()
        if (child != null && tryWaitForChild(finishing, child, proposedUpdate))
            return COMPLETING_WAITING_CHILDREN
        // turns out, there are no children to await, so we close the list.
        list.close(LIST_CHILD_PERMISSION)
        // some children could have sneaked into the list, so we try waiting for them again.
        // it would be more correct to re-open the list (otherwise, we get non-linearizable behavior),
        // but it's too difficult with the current lock-free list implementation.
        val anotherChild = list.nextChild()
        if (anotherChild != null && tryWaitForChild(finishing, anotherChild, proposedUpdate))
            return COMPLETING_WAITING_CHILDREN
        // otherwise -- we have not children left (all were already cancelled?)
        return finalizeFinishingState(finishing, proposedUpdate)
    }

    private val Any?.exceptionOrNull: Throwable?
        get() = (this as? CompletedExceptionally)?.cause

    // return false when there is no more incomplete children to wait
    // ## IMPORTANT INVARIANT: Only one thread can be concurrently invoking this method.
    private tailrec fun tryWaitForChild(state: Finishing, child: ChildHandleNode, proposedUpdate: Any?): Boolean {
        val handle = child.childJob.invokeOnCompletion(
            invokeImmediately = false,
            handler = ChildCompletion(this, state, child, proposedUpdate)
        )
        if (handle !== NonDisposableHandle) return true // child is not complete and we've started waiting for it
        val nextChild = child.nextChild() ?: return false
        return tryWaitForChild(state, nextChild, proposedUpdate)
    }

    // ## IMPORTANT INVARIANT: Only one thread can be concurrently invoking this method.
    private fun continueCompleting(state: Finishing, lastChild: ChildHandleNode, proposedUpdate: Any?) {
        assert { this.state === state } // consistency check -- it cannot change while we are waiting for children
        // figure out if we need to wait for the next child
        val waitChild = lastChild.nextChild()
        // try to wait for the next child
        if (waitChild != null && tryWaitForChild(state, waitChild, proposedUpdate)) return // waiting for next child
        // no more children to await, so *maybe* we can complete the job; for that, we stop accepting new children.
        // potentially, the list can be closed for children more than once: if we detect that there are no more
        // children, attempt to close the list, and then new children sneak in, this whole logic will be
        // repeated, including closing the list.
        state.list.close(LIST_CHILD_PERMISSION)
        // did any new children sneak in?
        val waitChildAgain = lastChild.nextChild()
        if (waitChildAgain != null && tryWaitForChild(state, waitChildAgain, proposedUpdate)) {
            // yes, so now we have to wait for them!
            // ideally, we should re-open the list,
            // but it's too difficult with the current lock-free list implementation,
            // so we'll live with non-linearizable behavior for now.
            return
        }
        // no more children, now we are sure; try to update the state
        val finalState = finalizeFinishingState(state, proposedUpdate)
        afterCompletion(finalState)
    }

    private fun LockFreeLinkedListNode.nextChild(): ChildHandleNode? {
        var cur = this
        while (cur.isRemoved) cur = cur.prevNode // rollback to prev non-removed (or list head)
        while (true) {
            cur = cur.nextNode
            if (cur.isRemoved) continue
            if (cur is ChildHandleNode) return cur
            if (cur is NodeList) return null // checked all -- no more children
        }
    }

    public final override val children: Sequence<Job> get() = sequence {
        when (val state = this@JobSupport.state) {
            is ChildHandleNode -> yield(state.childJob)
            is Incomplete -> state.list?.let { list ->
                list.forEach { if (it is ChildHandleNode) yield(it.childJob) }
            }
        }
    }

    @Suppress("OverridingDeprecatedMember")
    public final override fun attachChild(child: ChildJob): ChildHandle {
        /*
         * Note: This function attaches a special ChildHandleNode node object. This node object
         * is handled in a special way on completion on the coroutine (we wait for all of them) and also
         * can't be added simply with `invokeOnCompletionInternal` -- we add this node to the list even
         * if the job is already cancelling.
         * It's required to properly await all children before completion and provide a linearizable hierarchy view:
         * If the child is attached when the job is already being cancelled, such a child will receive
         * an immediate notification on cancellation,
         * but the parent *will* wait for that child before completion and will handle its exception.
         */
        val node = ChildHandleNode(child).also { it.job = this }
        val added = tryPutNodeIntoList(node) { _, list ->
            // First, try to add a child along the cancellation handlers
            val addedBeforeCancellation = list.addLast(
                node,
                LIST_ON_COMPLETION_PERMISSION or LIST_CHILD_PERMISSION or LIST_CANCELLATION_PERMISSION
            )
            if (addedBeforeCancellation) {
                // The child managed to be added before the parent started to cancel or complete. Success.
                true
            } else {
                /* Either cancellation or completion already happened, the child was not added.
                 * Now we need to try adding it just for completion. */
                val addedBeforeCompletion = list.addLast(
                    node,
                    LIST_CHILD_PERMISSION or LIST_ON_COMPLETION_PERMISSION
                )
                /*
                 * Whether or not we managed to add the child before the parent completed, we need to investigate:
                 * why didn't we manage to add it before cancellation?
                 * If it's because cancellation happened in the meantime, we need to notify the child about it.
                 * We check the latest state because the original state with which we started may not have had
                 * the information about the cancellation yet.
                 */
                val rootCause = when (val latestState = this.state) {
                    is Finishing -> {
                        // The state is still incomplete, so we need to notify the child about the completion cause.
                        latestState.rootCause
                    }
                    else -> {
                        /** Since the list is already closed for [onCancelling], the job is either Finishing or
                         * already completed. We need to notify the child about the completion cause. */
                        assert { latestState !is Incomplete }
                        (latestState as? CompletedExceptionally)?.cause
                    }
                }
                /**
                 * We must cancel the child if the parent was cancelled already, even if we successfully attached,
                 * as this child didn't make it before [notifyCancelling] and won't be notified that it should be
                 * cancelled.
                 *
                 * And if the parent wasn't cancelled and the previous [LockFreeLinkedListNode.addLast] failed because
                 * the job is in its final state already, we won't be able to attach anyway, so we must just invoke
                 * the handler and return.
                 */
                node.invoke(rootCause)
                if (addedBeforeCompletion) {
                    /** The root cause can't be null: since the earlier addition to the list failed, this means that
                     * the job was already cancelled or completed. */
                    assert { rootCause != null }
                    true
                } else {
                    /** No sense in retrying: we know it won't succeed, and we already invoked the handler. */
                    return NonDisposableHandle
                }
            }
        }
        if (added) return node
        /** We can only end up here if [tryPutNodeIntoList] detected a final state. */
        node.invoke((state as? CompletedExceptionally)?.cause)
        return NonDisposableHandle
    }

    /**
     * Override to process any exceptions that were encountered while invoking completion handlers
     * installed via [invokeOnCompletion].
     *
     * @suppress **This is unstable API and it is subject to change.**
     */
    internal open fun handleOnCompletionException(exception: Throwable) {
        throw exception
    }

    /**
     * This function is invoked once as soon as this job is being cancelled for any reason or completes,
     * similarly to [invokeOnCompletion] with `onCancelling` set to `true`.
     *
     * The meaning of [cause] parameter:
     * - Cause is `null` when the job has completed normally.
     * - Cause is an instance of [CancellationException] when the job was cancelled _normally_.
     *   **It should not be treated as an error**. In particular, it should not be reported to error logs.
     * - Otherwise, the job had been cancelled or failed with exception.
     *
     * The specified [cause] is not the final cancellation cause of this job.
     * A job may produce other exceptions while it is failing and the final cause might be different.
     *
     * @suppress **This is unstable API and it is subject to change.*
     */
    protected open fun onCancelling(cause: Throwable?) {}

    /**
     * Returns `true` for scoped coroutines.
     * Scoped coroutine is a coroutine that is executed sequentially within the enclosing scope without any concurrency.
     * Scoped coroutines always handle any exception happened within -- they just rethrow it to the enclosing scope.
     * Examples of scoped coroutines are `coroutineScope`, `withTimeout` and `runBlocking`.
     */
    protected open val isScopedCoroutine: Boolean get() = false

    /**
     * Returns `true` for jobs that handle their exceptions or integrate them into the job's result via [onCompletionInternal].
     * A valid implementation of this getter should recursively check parent as well before returning `false`.
     *
     * The only instance of the [Job] that does not handle its exceptions is [JobImpl] and its subclass [SupervisorJobImpl].
     * @suppress **This is unstable API and it is subject to change.*
     */
    internal open val handlesException: Boolean get() = true

    /**
     * Handles the final job [exception] that was not handled by the parent coroutine.
     * Returns `true` if it handles exception (so handling at later stages is not needed).
     * It is designed to be overridden by launch-like coroutines
     * (`StandaloneCoroutine` and `ActorCoroutine`) that don't have a result type
     * that can represent exceptions.
     *
     * This method is invoked **exactly once** when the final exception of the job is determined
     * and before it becomes complete. At the moment of invocation the job and all its children are complete.
     */
    protected open fun handleJobException(exception: Throwable): Boolean = false

    /**
     * Override for completion actions that need to update some external object depending on job's state,
     * right before all the waiters for coroutine's completion are notified.
     *
     * @param state the final state.
     *
     * @suppress **This is unstable API and it is subject to change.**
     */
    protected open fun onCompletionInternal(state: Any?) {}

    /**
     * Override for the very last action on job's completion to resume the rest of the code in
     * scoped coroutines. It is called when this job is externally completed in an unknown
     * context and thus should resume with a default mode.
     *
     * @suppress **This is unstable API and it is subject to change.**
     */
    protected open fun afterCompletion(state: Any?) {}

    // for nicer debugging
    public override fun toString(): String =
        "${toDebugString()}@$hexAddress"

    @InternalCoroutinesApi
    public fun toDebugString(): String = "${nameString()}{${stateString(state)}}"

    /**
     * @suppress **This is unstable API and it is subject to change.**
     */
    internal open fun nameString(): String = classSimpleName

    private fun stateString(state: Any?): String = when (state) {
        is Finishing -> when {
            state.isCancelling -> "Cancelling"
            state.isCompleting -> "Completing"
            else -> "Active"
        }
        is Incomplete -> if (state.isActive) "Active" else "New"
        is CompletedExceptionally -> "Cancelled"
        else -> "Completed"
    }

    // Completing & Cancelling states,
    // All updates are guarded by synchronized(this), reads are volatile
    @Suppress("UNCHECKED_CAST")
    private class Finishing(
        override val list: NodeList,
        isCompleting: Boolean,
        rootCause: Throwable?
    ) : SynchronizedObject(), Incomplete {
        private val _isCompleting = atomic(isCompleting)
        var isCompleting: Boolean
            get() = _isCompleting.value
            set(value) { _isCompleting.value = value }

        private val _rootCause = atomic(rootCause)
        var rootCause: Throwable? // NOTE: rootCause is kept even when SEALED
            get() = _rootCause.value
            set(value) { _rootCause.value = value }

        private val _exceptionsHolder = atomic<Any?>(null)
        private var exceptionsHolder: Any? // Contains null | Throwable | ArrayList | SEALED
            get() = _exceptionsHolder.value
            set(value) { _exceptionsHolder.value = value }

        // Note: cannot be modified when sealed
        val isSealed: Boolean get() = exceptionsHolder === SEALED
        val isCancelling: Boolean get() = rootCause != null
        override val isActive: Boolean get() = rootCause == null // !isCancelling

        // Seals current state and returns list of exceptions
        // guarded by `synchronized(this)`
        fun sealLocked(proposedException: Throwable?): List<Throwable> {
            val list = when(val eh = exceptionsHolder) { // volatile read
                null -> allocateList()
                is Throwable -> allocateList().also { it.add(eh) }
                is ArrayList<*> -> eh as ArrayList<Throwable>
                else -> error("State is $eh") // already sealed -- cannot happen
            }
            val rootCause = this.rootCause // volatile read
            rootCause?.let { list.add(0, it) } // note -- rootCause goes to the beginning
            if (proposedException != null && proposedException != rootCause) list.add(proposedException)
            exceptionsHolder = SEALED
            return list
        }

        // guarded by `synchronized(this)`
        fun addExceptionLocked(exception: Throwable) {
            val rootCause = this.rootCause // volatile read
            if (rootCause == null) {
                this.rootCause = exception
                return
            }
            if (exception === rootCause) return // nothing to do
            when (val eh = exceptionsHolder) { // volatile read
                null -> exceptionsHolder = exception
                is Throwable -> {
                    if (exception === eh) return // nothing to do
                    exceptionsHolder = allocateList().apply {
                        add(eh)
                        add(exception)

                    }
                }
                is ArrayList<*> -> (eh as ArrayList<Throwable>).add(exception)
                else -> error("State is $eh") // already sealed -- cannot happen
            }
        }

        private fun allocateList() = ArrayList<Throwable>(4)

        override fun toString(): String =
            "Finishing[cancelling=$isCancelling, completing=$isCompleting, rootCause=$rootCause, exceptions=$exceptionsHolder, list=$list]"
    }

    private val Incomplete.isCancelling: Boolean
        get() = this is Finishing && isCancelling

    // Used by parent that is waiting for child completion
    private class ChildCompletion(
        private val parent: JobSupport,
        private val state: Finishing,
        private val child: ChildHandleNode,
        private val proposedUpdate: Any?
    ) : JobNode() {
        override val onCancelling get() = false
        override fun invoke(cause: Throwable?) {
            parent.continueCompleting(state, child, proposedUpdate)
        }
    }

    private class AwaitContinuation<T>(
        delegate: Continuation<T>,
        private val job: JobSupport
    ) : CancellableContinuationImpl<T>(delegate, MODE_CANCELLABLE) {
        override fun getContinuationCancellationCause(parent: Job): Throwable {
            val state = job.state
            /*
             * When the job we are waiting for had already completely completed exceptionally or
             * is failing, we shall use its root/completion cause for await's result.
             */
            if (state is Finishing) state.rootCause?.let { return it }
            if (state is CompletedExceptionally) return state.cause
            return parent.getCancellationException()
        }

        override fun nameString(): String =
            "AwaitContinuation"
    }

    /*
     * =================================================================================================
     * This is ready-to-use implementation for Deferred interface.
     * However, it is not type-safe. Conceptually it just exposes the value of the underlying
     * completed state as `Any?`
     * =================================================================================================
     */

    public val isCompletedExceptionally: Boolean get() = state is CompletedExceptionally

    public fun getCompletionExceptionOrNull(): Throwable? {
        val state = this.state
        check(state !is Incomplete) { "This job has not completed yet" }
        return state.exceptionOrNull
    }

    /**
     * @suppress **This is unstable API and it is subject to change.**
     */
    internal fun getCompletedInternal(): Any? {
        val state = this.state
        check(state !is Incomplete) { "This job has not completed yet" }
        if (state is CompletedExceptionally) throw state.cause
        return state.unboxState()
    }

    /**
     * @suppress **This is unstable API and it is subject to change.**
     */
    protected suspend fun awaitInternal(): Any? {
        // fast-path -- check state (avoid extra object creation)
        while (true) { // lock-free loop on state
            val state = this.state
            if (state !is Incomplete) {
                // already complete -- just return result
                if (state is CompletedExceptionally) { // Slow path to recover stacktrace
                    recoverAndThrow(state.cause)
                }
                return state.unboxState()

            }
            if (startInternal(state) >= 0) break // break unless needs to retry
        }
        return awaitSuspend() // slow-path
    }

    private suspend fun awaitSuspend(): Any? = suspendCoroutineUninterceptedOrReturn { uCont ->
        /*
         * Custom code here, so that parent coroutine that is using await
         * on its child deferred (async) coroutine would throw the exception that this child had
         * thrown and not a JobCancellationException.
         */
        val cont = AwaitContinuation(uCont.intercepted(), this)
        // we are mimicking suspendCancellableCoroutine here and call initCancellability, too.
        cont.initCancellability()
        cont.disposeOnCancellation(invokeOnCompletion(handler = ResumeAwaitOnCompletion(cont)))
        cont.getResult()
    }

    @Suppress("UNCHECKED_CAST")
    protected val onAwaitInternal: SelectClause1<*> get() = SelectClause1Impl<Any?>(
        clauseObject = this@JobSupport,
        regFunc = JobSupport::onAwaitInternalRegFunc as RegistrationFunction,
        processResFunc = JobSupport::onAwaitInternalProcessResFunc as ProcessResultFunction
    )

    @Suppress("UNUSED_PARAMETER")
    private fun onAwaitInternalRegFunc(select: SelectInstance<*>, ignoredParam: Any?) {
        while (true) {
            val state = this.state
            if (state !is Incomplete) {
                val result = if (state is CompletedExceptionally) state else state.unboxState()
                select.selectInRegistrationPhase(result)
                return
            }
            if (startInternal(state) >= 0) break // break unless needs to retry
        }
        val disposableHandle = invokeOnCompletion(handler = SelectOnAwaitCompletionHandler(select))
        select.disposeOnCompletion(disposableHandle)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onAwaitInternalProcessResFunc(ignoredParam: Any?, result: Any?): Any? {
        if (result is CompletedExceptionally) throw result.cause
        return result
    }

    private inner class SelectOnAwaitCompletionHandler(
        private val select: SelectInstance<*>
    ) : JobNode() {
        override val onCancelling get() = false
        override fun invoke(cause: Throwable?) {
            val state = this@JobSupport.state
            val result = if (state is CompletedExceptionally) state else state.unboxState()
            select.trySelect(this@JobSupport, result)
        }
    }
}

/*
 * Class to represent object as the final state of the Job
 */
private class IncompleteStateBox(@JvmField val state: Incomplete)
internal fun Any?.boxIncomplete(): Any? = if (this is Incomplete) IncompleteStateBox(this) else this
internal fun Any?.unboxState(): Any? = (this as? IncompleteStateBox)?.state ?: this

// --------------- helper classes & constants for job implementation

private val COMPLETING_ALREADY = Symbol("COMPLETING_ALREADY")
@JvmField
internal val COMPLETING_WAITING_CHILDREN = Symbol("COMPLETING_WAITING_CHILDREN")
private val COMPLETING_RETRY = Symbol("COMPLETING_RETRY")
private val TOO_LATE_TO_CANCEL = Symbol("TOO_LATE_TO_CANCEL")

private const val RETRY = -1
private const val FALSE = 0
private const val TRUE = 1

private val SEALED = Symbol("SEALED")
private val EMPTY_NEW = Empty(false)
private val EMPTY_ACTIVE = Empty(true)

// bit mask
private const val LIST_ON_COMPLETION_PERMISSION = 1
private const val LIST_CHILD_PERMISSION = 2
private const val LIST_CANCELLATION_PERMISSION = 4

private class Empty(override val isActive: Boolean) : Incomplete {
    override val list: NodeList? get() = null
    override fun toString(): String = "Empty{${if (isActive) "Active" else "New" }}"
}

@OptIn(InternalForInheritanceCoroutinesApi::class)
@PublishedApi // for a custom job in the test module
internal open class JobImpl(parent: Job?) : JobSupport(true), CompletableJob {
    init { initParentJob(parent) }
    override val onCancelComplete get() = true
    /*
     * Check whether parent is able to handle exceptions as well.
     * With this check, an exception in that pattern will be handled once:
     * ```
     * launch {
     *     val child = Job(coroutineContext[Job])
     *     launch(child) { throw ... }
     * }
     * ```
     */
    override val handlesException: Boolean = handlesException()
    override fun complete() = makeCompleting(Unit)
    override fun completeExceptionally(exception: Throwable): Boolean =
        makeCompleting(CompletedExceptionally(exception))

    @JsName("handlesExceptionF")
    private fun handlesException(): Boolean {
        var parentJob = (parentHandle as? ChildHandleNode)?.job ?: return false
        while (true) {
            if (parentJob.handlesException) return true
            parentJob = (parentJob.parentHandle as? ChildHandleNode)?.job ?: return false
        }
    }
}

// -------- invokeOnCompletion nodes

internal interface Incomplete {
    val isActive: Boolean
    val list: NodeList? // is null only for Empty and JobNode incomplete state objects
}

internal abstract class JobNode : LockFreeLinkedListNode(), DisposableHandle, Incomplete {
    /**
     * Initialized by [JobSupport.invokeOnCompletionInternal].
     */
    lateinit var job: JobSupport

    /**
     * If `false`, [invoke] will be called once the job is cancelled or is complete.
     * If `true`, [invoke] is invoked as soon as the job becomes _cancelling_ instead, and if that doesn't happen,
     * it will be called once the job is cancelled or is complete.
     */
    abstract val onCancelling: Boolean
    override val isActive: Boolean get() = true
    override val list: NodeList? get() = null

    override fun dispose() = job.removeNode(this)
    override fun toString() = "$classSimpleName@$hexAddress[job@${job.hexAddress}]"
    /**
     * Signals completion.
     *
     * This function:
     * - Does not throw any exceptions.
     *   For [Job] instances that are coroutines, exceptions thrown by this function will be caught, wrapped into
     *   [CompletionHandlerException], and passed to [handleCoroutineException], but for those that are not coroutines,
     *   they will just be rethrown, potentially crashing unrelated code.
     * - Is fast, non-blocking, and thread-safe.
     * - Can be invoked concurrently with the surrounding code.
     * - Can be invoked from any context.
     *
     * The meaning of `cause` that is passed to the handler is:
     * - It is `null` if the job has completed normally.
     * - It is an instance of [CancellationException] if the job was cancelled _normally_.
     *   **It should not be treated as an error**. In particular, it should not be reported to error logs.
     * - Otherwise, the job had _failed_.
     *
     * [CompletionHandler] is the user-visible interface for supplying custom implementations of [invoke]
     * (see [InvokeOnCompletion] and [InvokeOnCancelling]).
     */
    abstract fun invoke(cause: Throwable?)
}

internal class NodeList : LockFreeLinkedListHead(), Incomplete {
    override val isActive: Boolean get() = true
    override val list: NodeList get() = this

    fun getString(state: String) = buildString {
        append("List{")
        append(state)
        append("}[")
        var first = true
        this@NodeList.forEach { node ->
            if (node is JobNode) {
                if (first) first = false else append(", ")
                append(node)
            }
        }
        append("]")
    }

    override fun toString(): String =
        if (DEBUG) getString("Active") else super.toString()
}

private class InactiveNodeList(
    override val list: NodeList
) : Incomplete {
    override val isActive: Boolean get() = false
    override fun toString(): String = if (DEBUG) list.getString("New") else super.toString()
}

private class InvokeOnCompletion(
    private val handler: CompletionHandler
) : JobNode()  {
    override val onCancelling get() = false
    override fun invoke(cause: Throwable?) = handler.invoke(cause)
}

private class ResumeOnCompletion(
    private val continuation: Continuation<Unit>
) : JobNode() {
    override val onCancelling get() = false
    override fun invoke(cause: Throwable?) = continuation.resume(Unit)
}

private class ResumeAwaitOnCompletion<T>(
    private val continuation: CancellableContinuationImpl<T>
) : JobNode() {
    override val onCancelling get() = false
    override fun invoke(cause: Throwable?) {
        val state = job.state
        assert { state !is Incomplete }
        if (state is CompletedExceptionally) {
            // Resume with with the corresponding exception to preserve it
            continuation.resumeWithException(state.cause)
        } else {
            // Resuming with value in a cancellable way (AwaitContinuation is configured for this mode).
            @Suppress("UNCHECKED_CAST")
            continuation.resume(state.unboxState() as T)
        }
    }
}

// -------- invokeOnCancellation nodes

private class InvokeOnCancelling(
    private val handler: CompletionHandler
) : JobNode()  {
    // delegate handler shall be invoked at most once, so here is an additional flag
    private val _invoked = atomic(false)
    override val onCancelling get() = true
    override fun invoke(cause: Throwable?) {
        if (_invoked.compareAndSet(expect = false, update = true)) handler.invoke(cause)
    }
}

private class ChildHandleNode(
    @JvmField val childJob: ChildJob
) : JobNode(), ChildHandle {
    override val parent: Job get() = job
    override val onCancelling: Boolean get() = true
    override fun invoke(cause: Throwable?) = childJob.parentCancelled(job)
    override fun childCancelled(cause: Throwable): Boolean = job.childCancelled(cause)
}
