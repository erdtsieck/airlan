package io.github.erdtsieck.airlan.wfrac

/**
 * One air conditioner, as the app reads and writes it. [WfRacClient] talks to a real module
 * over the network; [DemoClient] simulates one in memory.
 */
interface AirconClient {
    /** The transport the client found on first contact, for the caller to persist; null until known, or when there is none. */
    val scheme: Scheme?

    /** The HTTPS certificate pinned on first contact, for the caller to persist. */
    val certificatePin: String?

    suspend fun getStat(airconId: String): AirconState

    /** Writes a full state frame and returns the state the unit reports back. */
    suspend fun setStat(airconId: String, state: AirconState): AirconState

    /** Writes require a registered account (4 slots per unit). Re-registering the same id is idempotent. */
    suspend fun registerAccount(airconId: String, timezone: String)
}
