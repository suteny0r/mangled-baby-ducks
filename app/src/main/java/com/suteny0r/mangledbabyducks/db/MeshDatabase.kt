package com.suteny0r.mangledbabyducks.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    ],
    version = 2,
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

    companion object {
        fun build(context: Context): MeshDatabase =
            Room.databaseBuilder(context, MeshDatabase::class.java, "mesh.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
