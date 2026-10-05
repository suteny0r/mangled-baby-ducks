package com.suteny0r.mangledbabyducks.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NodeEntity::class,
        UserEntity::class,
        MessageEntity::class,
        ChannelEntity::class,
        MyInfoEntity::class,
        PositionEntity::class,
        TelemetryEntity::class,
        ConfigEntity::class,
        TracerouteEntity::class,
        WaypointEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun nodeDao(): NodeDao
    abstract fun userDao(): UserDao
    abstract fun messageDao(): MessageDao
    abstract fun channelDao(): ChannelDao
    abstract fun myInfoDao(): MyInfoDao
    abstract fun positionDao(): PositionDao
    abstract fun telemetryDao(): TelemetryDao
    abstract fun configDao(): ConfigDao
    abstract fun tracerouteDao(): TracerouteDao
    abstract fun waypointDao(): WaypointDao

    companion object {
        /**
         * Adding the relay columns is additive, and the rows they would have cost are the
         * message history: worth a real migration rather than the usual destructive wipe.
         * The fallback stays for every other schema change.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN relayNode INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN relays INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** The per-message security flags, additive like MIGRATION_4_5 and equally cheap. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN pkiEncrypted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN xeddsaSigned INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): MeshDatabase =
            Room.databaseBuilder(context, MeshDatabase::class.java, "mesh.db")
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigration()
                .build()
    }
}
