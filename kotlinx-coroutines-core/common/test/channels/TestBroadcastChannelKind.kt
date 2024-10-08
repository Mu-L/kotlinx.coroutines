package kotlinx.coroutines.channels

@Suppress("DEPRECATION_ERROR")
enum class TestBroadcastChannelKind {
    ARRAY_1 {
        override fun <T> create(): BroadcastChannel<T> = BroadcastChannel(1)
        override fun toString(): String = "BufferedBroadcastChannel(1)"
    },
    ARRAY_10 {
        override fun <T> create(): BroadcastChannel<T> = BroadcastChannel(10)
        override fun toString(): String = "BufferedBroadcastChannel(10)"
    },
    CONFLATED {
        override fun <T> create(): BroadcastChannel<T> = ConflatedBroadcastChannel()
        override fun toString(): String = "ConflatedBroadcastChannel"
        override val isConflated: Boolean get() = true
    }
    ;

    abstract fun <T> create(): BroadcastChannel<T>
    open val isConflated: Boolean get() = false
}
