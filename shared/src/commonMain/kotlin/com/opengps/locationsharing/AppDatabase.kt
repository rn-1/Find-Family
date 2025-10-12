package com.opengps.locationsharing

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

interface ObjectParent {
    val id: ULong
    val name: String

    fun currentPosition(): Coord?
}

@Entity
@Serializable
data class LocationValue(
    @PrimaryKey(autoGenerate = true) val id: ULong = 0uL,
    val userid: ULong,
    val coord: Coord,
    val speed: Float,
    val acc: Float,
    val timestamp: Long,
    val battery: Float,
    val sleep: Boolean = false)

@Entity
@Serializable
data class BluetoothDevice(
    @PrimaryKey
    override val id: ULong = 0uL,
    override val name: String,
    val lastLocationValue: LocationValue? = null,
): ObjectParent {
    override fun currentPosition() = lastLocationValue?.coord
}

@Serializable
enum class RequestStatus {
    MUTUAL_CONNECTION,
    AWAITING_REQUEST,
    AWAITING_RESPONSE
}

@OptIn(ExperimentalTime::class)
class InstantSerializer() : KSerializer<Instant> {
    override fun deserialize(decoder: Decoder) = Instant.fromEpochMilliseconds(decoder.decodeLong())
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeLong(value.toEpochMilliseconds())
}

@Entity
@Serializable
@OptIn(ExperimentalTime::class)
data class User(
    @PrimaryKey(autoGenerate = true) override val id: ULong = 0uL,
    override val name: String,
    val photo: String?,
    var locationName: String,
    var send: Boolean,
    var requestStatus: RequestStatus,
    var lastBatteryLevel: Float?,
    var lastCoord: Coord?,
    @Serializable(with = InstantSerializer::class)
    var lastLocationChangeTime: Instant = Clock.System.now(),
    var lastLocationValue: LocationValue? = null,
    @Serializable(with = InstantSerializer::class)
    var deleteAt: Instant? = null,
    var encryptionKey: String? = null,
): ObjectParent {
    override fun currentPosition() = lastLocationValue?.coord
}

@Entity
@Serializable
data class Waypoint(
    @PrimaryKey(autoGenerate = true) override val id: ULong = 0uL,
    override val name: String,
    val range: Double,
    val coord: Coord,
    val usersInactive: MutableList<ULong>
): ObjectParent {
    override fun currentPosition() = coord
}

@Dao
interface WaypointDao {
    @Query("SELECT * FROM Waypoint")
    suspend fun getAll(): List<Waypoint>
    @Upsert
    suspend fun upsert(wp: Waypoint)
    @Delete
    suspend fun delete(waypoint: Waypoint)
    @Query("DELETE FROM Waypoint")
    suspend fun clear()
    @Insert
    suspend fun insertAll(waypoints: List<Waypoint>)
    @Transaction
    suspend fun setAll(waypoints: List<Waypoint>) {
        clear()
        insertAll(waypoints)
    }
}

@OptIn(ExperimentalTime::class)
@Dao
interface UsersDao {
    @Query("SELECT * FROM User")
    suspend fun getAll(): List<User>
    @Query("SELECT * FROM User WHERE id = :id")
    suspend fun getByID(id: ULong): User?
    @Upsert
    suspend fun upsert(user: User)
    @Delete
    suspend fun delete(user: User)
    @Query("DELETE FROM User")
    suspend fun clear()
    @Insert
    suspend fun insertAll(users: List<User>)
    @Transaction
    suspend fun setAll(users: List<User>) {
        clear()
        insertAll(users)
    }
}

object UsersCached {
    private var usersMap: Map<ULong, User> = mapOf()
    private val users: List<User> get() = usersMap.values.toList()
    suspend fun init() {
        usersMap = platform.database.usersDao().getAll().associateBy { it.id }
    }
    suspend fun save() {
        platform.database.usersDao().setAll(users)
    }

    fun getAll() = users
    fun filter(predicate: (User) -> Boolean) {
        usersMap = usersMap.filter({ predicate(it.value) })
    }
    fun updateByID(id: ULong, update: (User) -> User) {
        usersMap = usersMap + (id to update(usersMap[id]!!))
    }
    fun getByID(id: ULong) = usersMap[id]
    fun upsert(user: User) {
        usersMap = usersMap + (user.id to user)
    }
    fun delete(user: User) {
        usersMap = usersMap - user.id
    }
    fun delete(id: ULong) {
        usersMap = usersMap - id
    }
    fun clear() {
        usersMap = mapOf()
    }
    fun insertAll(users: List<User>) {
        this.usersMap = this.usersMap + users.associateBy { it.id }
    }
    fun setAll(users: List<User>) {
        clear()
        insertAll(users)
    }
}

@Dao
interface LocationValueDao {
    @Query("SELECT * FROM LocationValue")
    suspend fun getAll(): List<LocationValue>
    @Query("SELECT * FROM LocationValue WHERE timestamp > :timestamp")
    suspend fun getSince(timestamp: Long): List<LocationValue>
    @Query("DELETE FROM LocationValue WHERE timestamp < :timestamp")
    suspend fun clearBefore(timestamp: Long)
    @Query("SELECT * FROM LocationValue WHERE userid = :id")
    suspend fun getForID(id: ULong): List<LocationValue>
    @Upsert
    suspend fun upsert(locationValue: LocationValue)
    @Upsert
    suspend fun upsertAll(locationValue: List<LocationValue>)
    @Delete
    suspend fun delete(locationValue: LocationValue)
}

@Dao
interface BluetoothDeviceDao {
    @Query("SELECT * FROM BluetoothDevice")
    suspend fun getAll(): List<BluetoothDevice>
    @Upsert
    suspend fun upsert(bluetoothDevice: BluetoothDevice)
    @Query("SELECT * FROM BluetoothDevice WHERE name = :name")
    suspend fun getFromName(name: String): BluetoothDevice?
    @Delete
    suspend fun delete(bluetoothDevice: BluetoothDevice)
}

@OptIn(ExperimentalTime::class)
class TC {
    @TypeConverter fun fromULong(value: ULong) = value.toLong()
    @TypeConverter fun toULong(value: Long) = value.toULong()
    @TypeConverter fun fromInstant(value: Instant) = value.toEpochMilliseconds()
    @TypeConverter fun toInstant(value: Long) = Instant.fromEpochMilliseconds(value)
    @TypeConverter fun fromLocationValue(value: LocationValue?) = Json.encodeToString(value)
    @TypeConverter fun toLocationValue(value: String) = Json.decodeFromString<LocationValue?>(value)
    @TypeConverter fun fromUlonglist(value: MutableList<ULong>?) = Json.encodeToString(value)
    @TypeConverter fun toUlonglist(value: String) = Json.decodeFromString<MutableList<ULong>?>(value)
    @TypeConverter fun fromCoord(value: Coord?) = Json.encodeToString(value)
    @TypeConverter fun toCoord(value: String) = Json.decodeFromString<Coord?>(value)
}
@Database(entities = [Waypoint::class, User::class, LocationValue::class, BluetoothDevice::class], version = 9)
@TypeConverters(TC::class)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun waypointDao(): WaypointDao
    abstract fun usersDao(): UsersDao
    abstract fun locationValueDao(): LocationValueDao
    abstract fun bluetoothDeviceDao(): BluetoothDeviceDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
