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
    @Query("SELECT * FROM nodes ORDER BY favorite DESC, lastHeard DESC, num DESC")
    fun nodesWithUsers(): Flow<List<NodeWithUser>>

    @Transaction
    @Query("SELECT * FROM nodes WHERE num = :num")
    fun nodeWithUserFlow(num: Long): Flow<NodeWithUser?>

    @Query("SELECT COUNT(*) FROM nodes")
    fun count(): Flow<Int>

    @Query("DELETE FROM nodes")
    suspend fun clear()

    @Query("DELETE FROM nodes WHERE num = :num")
    suspend fun delete(num: Long)

    @Query("UPDATE nodes SET favorite = :favorite WHERE num = :num")
    suspend fun setFavorite(num: Long, favorite: Boolean)

    @Query("UPDATE nodes SET ignored = :ignored WHERE num = :num")
    suspend fun setIgnored(num: Long, ignored: Boolean)
}

@Dao
interface UserDao {
    @Upsert
    suspend fun upsert(user: UserEntity)

    @Query("SELECT * FROM users WHERE num = :num")
    suspend fun get(num: Long): UserEntity?

    @Query("SELECT * FROM users WHERE num = :num")
    fun userFlow(num: Long): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE lastMessage IS NOT NULL ORDER BY lastMessage DESC")
    fun dmContacts(): Flow<List<UserEntity>>

    @Query("UPDATE users SET lastMessage = :time WHERE num = :num")
    suspend fun touchLastMessage(num: Long, time: Long)

    @Query("UPDATE users SET mute = :mute WHERE num = :num")
    suspend fun setMute(num: Long, mute: Boolean)

    /**
     * Promote the refused inbound key (see PacketIngest.upsertUser) to the trusted one.
     * Returns 0 when there was nothing to accept.
     */
    @Query(
        "UPDATE users SET publicKey = newPublicKey, keyMatch = 1, newPublicKey = NULL " +
            "WHERE num = :num AND newPublicKey IS NOT NULL"
    )
    suspend fun acceptNewKey(num: Long): Int

    @Query("DELETE FROM users WHERE num = :num")
    suspend fun delete(num: Long)
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

    @Query("SELECT * FROM messages WHERE toNum IS NULL AND channel = :channel AND isEmoji = 1")
    fun channelTapbacks(channel: Int): Flow<List<MessageEntity>>

    @Query(
        "SELECT * FROM messages WHERE isEmoji = 1 AND ((fromNum = :peer AND toNum = :myNum) " +
            "OR (fromNum = :myNum AND toNum = :peer))"
    )
    fun directTapbacks(myNum: Long, peer: Long): Flow<List<MessageEntity>>

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

    @Query("SELECT COUNT(*) FROM messages WHERE read = 0 AND isEmoji = 0 AND toNum IS NULL")
    fun unreadChannelCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE read = 0 AND isEmoji = 0 AND toNum IS NOT NULL")
    fun unreadDirectCount(): Flow<Int>

    /** Newest text message per channel (ChannelList.swift row preview + time). */
    @Query(
        "SELECT * FROM messages m WHERE toNum IS NULL AND isEmoji = 0 AND timestamp = " +
            "(SELECT MAX(timestamp) FROM messages WHERE toNum IS NULL AND isEmoji = 0 AND channel = m.channel)"
    )
    fun channelPreviews(): Flow<List<MessageEntity>>

    /** Newest text message per DM peer, either direction (UserList.swift row preview). */
    @Query(
        "SELECT * FROM messages m WHERE toNum IS NOT NULL AND isEmoji = 0 AND timestamp = " +
            "(SELECT MAX(timestamp) FROM messages WHERE toNum IS NOT NULL AND isEmoji = 0 AND " +
            "((fromNum = m.fromNum AND toNum = m.toNum) OR (fromNum = m.toNum AND toNum = m.fromNum)))"
    )
    fun dmPreviews(): Flow<List<MessageEntity>>

    @Query("SELECT DISTINCT channel FROM messages WHERE read = 0 AND isEmoji = 0 AND toNum IS NULL")
    fun unreadChannelIndexes(): Flow<List<Int>>

    @Query("SELECT DISTINCT fromNum FROM messages WHERE read = 0 AND isEmoji = 0 AND toNum IS NOT NULL")
    fun unreadDmPeers(): Flow<List<Long>>

    @Query("UPDATE messages SET read = 1 WHERE toNum IS NULL AND channel = :channel")
    suspend fun markChannelRead(channel: Int)

    @Query("UPDATE messages SET read = 1 WHERE fromNum = :peer AND toNum = :myNum")
    suspend fun markDmRead(myNum: Long, peer: Long)

    @Query("SELECT * FROM messages WHERE toNum IS NULL AND channel = :channel ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastChannelMessage(channel: Int): MessageEntity?

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    suspend fun get(messageId: Long): MessageEntity?

    /** Retry drops the stuck row before re-sending the text as a new packet. */
    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun delete(messageId: Long)

    /** ChannelList "Delete Messages": this channel's local history, tapbacks included. */
    @Query("DELETE FROM messages WHERE toNum IS NULL AND channel = :channel")
    suspend fun deleteChannelMessages(channel: Int)

    /** UserList "Delete Messages": every message to or from this node, tapbacks included. */
    @Query("DELETE FROM messages WHERE toNum IS NOT NULL AND (fromNum = :peer OR toNum = :peer)")
    suspend fun deleteDirectMessages(peer: Long)
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

    @Query("DELETE FROM my_info")
    suspend fun clear()
}

@Dao
interface PositionDao {
    @Insert
    suspend fun insert(position: PositionEntity)

    @Query("UPDATE positions SET latest = 0 WHERE nodeNum = :nodeNum")
    suspend fun clearLatest(nodeNum: Long)

    @Query("SELECT * FROM positions WHERE nodeNum = :nodeNum AND latest = 1 LIMIT 1")
    suspend fun latest(nodeNum: Long): PositionEntity?

    @Query("SELECT * FROM positions WHERE nodeNum = :nodeNum AND latest = 1 LIMIT 1")
    fun latestFlow(nodeNum: Long): Flow<PositionEntity?>

    @Query("SELECT * FROM positions WHERE latest = 1")
    fun latestPositions(): Flow<List<PositionEntity>>

    /** Position Log: every retained fix for one node, newest first. */
    @Query("SELECT * FROM positions WHERE nodeNum = :nodeNum ORDER BY time DESC LIMIT 200")
    fun history(nodeNum: Long): Flow<List<PositionEntity>>

    @Query("DELETE FROM positions WHERE latest = 0 AND time < :cutoff")
    suspend fun prune(cutoff: Long)

    /**
     * Positions outlive their node when the node table is wiped (new radio) or a node is
     * removed; iOS keeps positions as a relationship so they go with the node. Dropping
     * the orphans keeps stale fixes, including a precision-reduced null island from a
     * node the current radio does not even know, off the map.
     */
    @Query("DELETE FROM positions WHERE nodeNum NOT IN (SELECT num FROM nodes)")
    suspend fun pruneOrphans()

    @Query("DELETE FROM positions WHERE nodeNum = :nodeNum")
    suspend fun deleteFor(nodeNum: Long)

    @Query("DELETE FROM positions")
    suspend fun clear()

    @Query(
        "SELECT p.nodeNum AS nodeNum, p.latitudeI AS latitudeI, p.longitudeI AS longitudeI, " +
            "p.time AS time, u.shortName AS shortName, u.longName AS longName " +
            "FROM positions p JOIN nodes n ON n.num = p.nodeNum " +
            "LEFT JOIN users u ON u.num = p.nodeNum WHERE p.latest = 1"
    )
    fun mapNodes(): Flow<List<MapNode>>

    /** Latest positions + user names for a set of node nums (the route nodes with snapshots). */
    @Query(
        "SELECT p.nodeNum AS nodeNum, p.latitudeI AS latitudeI, p.longitudeI AS longitudeI, " +
            "u.shortName AS shortName, u.longName AS longName " +
            "FROM positions p JOIN nodes n ON n.num = p.nodeNum " +
            "LEFT JOIN users u ON u.num = p.nodeNum " +
            "WHERE p.latest = 1 AND p.nodeNum IN (:nums)"
    )
    fun latestByNums(nums: List<Long>): Flow<List<RoutePoint>>
}

@Dao
interface ConfigDao {
    @Upsert
    suspend fun upsert(config: ConfigEntity)

    @Query("SELECT * FROM configs WHERE type = :type")
    fun config(type: String): Flow<ConfigEntity?>

    @Query("DELETE FROM configs")
    suspend fun clear()
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

    /** Latest battery reading per node: rows in time-desc order, first per node wins. */
    @Query(
        "SELECT * FROM telemetry WHERE nodeNum IN (:nums) AND metricsType = 0 " +
            "AND batteryLevel IS NOT NULL ORDER BY time DESC"
    )
    fun batteryByNums(nums: List<Long>): Flow<List<TelemetryEntity>>

    @Query(
        "SELECT * FROM telemetry WHERE nodeNum = :nodeNum AND metricsType = :type " +
            "AND time > :since ORDER BY time ASC"
    )
    fun history(nodeNum: Long, type: Int, since: Long): Flow<List<TelemetryEntity>>

    @Query("DELETE FROM telemetry WHERE time < :cutoff")
    suspend fun prune(cutoff: Long)

    @Query("DELETE FROM telemetry WHERE nodeNum NOT IN (SELECT num FROM nodes)")
    suspend fun pruneOrphans()

    @Query("DELETE FROM telemetry WHERE nodeNum = :nodeNum")
    suspend fun deleteFor(nodeNum: Long)

    @Query("DELETE FROM telemetry")
    suspend fun clear()
}

@Dao
interface TracerouteDao {
    @Insert
    suspend fun insert(traceroute: TracerouteEntity): Long

    @Query(
        "UPDATE traceroutes SET response = 1, routeTowards = :routeTowards, snrTowards = :snrTowards, " +
            "routeBack = :routeBack, snrBack = :snrBack WHERE id = :id"
    )
    suspend fun applyResponse(
        id: Long,
        routeTowards: String,
        snrTowards: String,
        routeBack: String,
        snrBack: String,
    )

    @Query("SELECT * FROM traceroutes WHERE toNum = :toNum ORDER BY time DESC LIMIT 10")
    fun forNode(toNum: Long): Flow<List<TracerouteEntity>>

    @Query("SELECT * FROM traceroutes WHERE toNum = :toNum AND response = 0 ORDER BY time DESC LIMIT 1")
    suspend fun latestPending(toNum: Long): TracerouteEntity?
}

@Dao
interface WaypointDao {
    @Upsert
    suspend fun upsert(waypoint: WaypointEntity)

    @Query("SELECT * FROM waypoints WHERE expire = 0 OR expire > :nowSec")
    fun active(nowSec: Long): Flow<List<WaypointEntity>>

    @Query("DELETE FROM waypoints WHERE id = :id")
    suspend fun delete(id: Long)
}
