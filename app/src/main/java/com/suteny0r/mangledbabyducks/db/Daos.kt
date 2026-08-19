package com.suteny0r.mangledbabyducks.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NodeDao {
    @Upsert
    suspend fun upsert(node: NodeEntity)

    @Query("SELECT * FROM nodes WHERE num = :num")
    suspend fun get(num: Long): NodeEntity?

    @Transaction
    @Query("SELECT * FROM nodes ORDER BY favorite DESC, lastHeard DESC")
    fun nodesWithUsers(): Flow<List<NodeWithUser>>

    @Query("SELECT COUNT(*) FROM nodes")
    fun count(): Flow<Int>

    @Query("DELETE FROM nodes")
    suspend fun clear()

    @Query("UPDATE nodes SET favorite = :favorite WHERE num = :num")
    suspend fun setFavorite(num: Long, favorite: Boolean)
}

@Dao
interface UserDao {
    @Upsert
    suspend fun upsert(user: UserEntity)

    @Query("SELECT * FROM users WHERE num = :num")
    suspend fun get(num: Long): UserEntity?

    @Query("SELECT * FROM users WHERE lastMessage IS NOT NULL ORDER BY lastMessage DESC")
    fun dmContacts(): Flow<List<UserEntity>>

    @Query("UPDATE users SET lastMessage = :time WHERE num = :num")
    suspend fun touchLastMessage(num: Long, time: Long)
}

@Dao
interface MessageDao {
    /** IGNORE, not REPLACE: the radio echoes our own sends and a replace would reset ack/read state. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE toNum IS NULL AND channel = :channel AND isEmoji = 0 ORDER BY timestamp ASC")
    fun channelMessages(channel: Int): Flow<List<MessageEntity>>

    @Query(
        "SELECT * FROM messages WHERE isEmoji = 0 AND ((fromNum = :peer AND toNum = :myNum) " +
            "OR (fromNum = :myNum AND toNum = :peer)) ORDER BY timestamp ASC"
    )
    fun directMessages(myNum: Long, peer: Long): Flow<List<MessageEntity>>

    @Query(
        "UPDATE messages SET receivedAck = :receivedAck, realAck = :realAck, ackError = :ackError, " +
            "ackSnr = :ackSnr, ackTimestamp = :ackTimestamp WHERE messageId = :messageId"
    )
    suspend fun applyAck(
        messageId: Long,
        receivedAck: Boolean,
        realAck: Boolean,
        ackError: Int,
        ackSnr: Float,
        ackTimestamp: Long,
    )

    @Query("SELECT COUNT(*) FROM messages WHERE read = 0 AND isEmoji = 0")
    fun unreadCount(): Flow<Int>

    @Query("UPDATE messages SET read = 1 WHERE toNum IS NULL AND channel = :channel")
    suspend fun markChannelRead(channel: Int)

    @Query("UPDATE messages SET read = 1 WHERE fromNum = :peer AND toNum = :myNum")
    suspend fun markDmRead(myNum: Long, peer: Long)

    @Query("SELECT * FROM messages WHERE toNum IS NULL AND channel = :channel ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastChannelMessage(channel: Int): MessageEntity?
}

@Dao
interface ChannelDao {
    @Upsert
    suspend fun upsert(channel: ChannelEntity)

    @Query("SELECT * FROM channels WHERE role != 0 ORDER BY `index` ASC")
    fun activeChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE `index` = :index")
    suspend fun get(index: Int): ChannelEntity?

    @Query("DELETE FROM channels")
    suspend fun clear()
}

@Dao
interface MyInfoDao {
    @Upsert
    suspend fun upsert(myInfo: MyInfoEntity)

    @Query("SELECT * FROM my_info LIMIT 1")
    fun myInfo(): Flow<MyInfoEntity?>

    @Query("SELECT * FROM my_info LIMIT 1")
    suspend fun myInfoOnce(): MyInfoEntity?
}

@Dao
interface PositionDao {
    @Insert
    suspend fun insert(position: PositionEntity)

    @Query("UPDATE positions SET latest = 0 WHERE nodeNum = :nodeNum")
    suspend fun clearLatest(nodeNum: Long)

    @Query("SELECT * FROM positions WHERE nodeNum = :nodeNum AND latest = 1 LIMIT 1")
    suspend fun latest(nodeNum: Long): PositionEntity?

    @Query("SELECT * FROM positions WHERE latest = 1")
    fun latestPositions(): Flow<List<PositionEntity>>
}

@Dao
interface TelemetryDao {
    @Insert
    suspend fun insert(telemetry: TelemetryEntity)

    @Query(
        "SELECT * FROM telemetry WHERE nodeNum = :nodeNum AND metricsType = :type " +
            "ORDER BY time DESC LIMIT 1"
    )
    suspend fun latest(nodeNum: Long, type: Int): TelemetryEntity?

    @Query("SELECT * FROM telemetry WHERE nodeNum = :nodeNum AND metricsType = 0 ORDER BY time DESC LIMIT 1")
    fun latestDeviceMetrics(nodeNum: Long): Flow<TelemetryEntity?>
}
