package io.github.erdtsieck.airlan.wfrac

/** A unit holds at most this many accounts; a full table refuses new ones (it never evicts). */
const val ACCOUNT_SLOTS = 4

/** An applied write locks out every other client for this long. */
const val WRITE_LOCK_S = 60

/**
 * The account every AirLAN client registers with a unit, the server included, so that AirLAN
 * takes one of the unit's four slots however many phones use it. The module has no
 * authentication: an operatorId only marks an account slot, it guards nothing. Writers are
 * told apart by deviceId, which stays per installation (the write lock keys on it).
 */
const val AIRLAN_OPERATOR_ID = "98c7ec9c-1dac-4e5f-8d16-a145f58a38db"

/**
 * What a read tells about a unit besides its state: how long another client's write lock
 * still runs ([lockedForS], 0 when nobody else holds it) and how many of its account slots
 * are taken ([accounts], null when the unit does not say).
 */
data class UnitStatus(val state: AirconState, val lockedForS: Int = 0, val accounts: Int? = null)

/**
 * One air conditioner, as the app reads and writes it. [WfRacClient] talks to a real module
 * over the network; [DemoClient] simulates one in memory.
 */
interface AirconClient {
    /** The transport the client found on first contact, for the caller to persist; null until known, or when there is none. */
    val scheme: Scheme?

    /** The HTTPS certificate pinned on first contact, for the caller to persist. */
    val certificatePin: String?

    /** Reads neither take nor test the write lock. */
    suspend fun getStatus(airconId: String): UnitStatus

    suspend fun getStat(airconId: String): AirconState = getStatus(airconId).state

    /** Writes a full state frame and returns the state the unit reports back. */
    suspend fun setStat(airconId: String, state: AirconState): AirconState

    /**
     * Writes require a registered account ([ACCOUNT_SLOTS] per unit). Re-registering the same
     * id is idempotent. Throws [WfRacException.NO_ACCOUNT_SLOT] when the unit is full.
     */
    suspend fun registerAccount(airconId: String, timezone: String)

    /**
     * Moves from the account of its own that AirLAN 0.1.1 and older registered per
     * installation ([legacyOperatorId]) to the shared [AIRLAN_OPERATOR_ID].
     */
    suspend fun migrateAccount(airconId: String, legacyOperatorId: String, timezone: String)
}
