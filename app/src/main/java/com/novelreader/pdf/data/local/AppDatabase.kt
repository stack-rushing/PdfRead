package com.novelreader.pdf.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.novelreader.pdf.data.local.dao.BookDao
import com.novelreader.pdf.data.local.dao.ChapterDao
import com.novelreader.pdf.data.local.dao.SettingDao
import com.novelreader.pdf.data.local.entity.BookEntity
import com.novelreader.pdf.data.local.entity.ChapterEntity
import com.novelreader.pdf.data.local.entity.SettingEntity

@Database(
    entities = [BookEntity::class, ChapterEntity::class, SettingEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao

    abstract fun chapterDao(): ChapterDao

    abstract fun settingDao(): SettingDao

    companion object {
        private const val DB_NAME = "novel_reader.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                // 个人自用 + 单一设备：目录生成结果可以随时重跑，因此允许破坏性升级，
                // 省掉每次改字段都写 Migration 的成本。要长期保留数据时再补 Migration。
                .fallbackToDestructiveMigration()
                .build()
    }
}
