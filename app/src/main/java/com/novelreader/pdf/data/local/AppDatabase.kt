package com.novelreader.pdf.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.novelreader.pdf.data.local.dao.BookDao
import com.novelreader.pdf.data.local.dao.ChapterDao
import com.novelreader.pdf.data.local.dao.DictDao
import com.novelreader.pdf.data.local.dao.SettingDao
import com.novelreader.pdf.data.local.entity.BookEntity
import com.novelreader.pdf.data.local.entity.ChapterEntity
import com.novelreader.pdf.data.local.entity.DictEntryEntity
import com.novelreader.pdf.data.local.entity.DictFormEntity
import com.novelreader.pdf.data.local.entity.DictionaryEntity
import com.novelreader.pdf.data.local.entity.SettingEntity

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        SettingEntity::class,
        DictionaryEntity::class,
        DictEntryEntity::class,
        DictFormEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao

    abstract fun chapterDao(): ChapterDao

    abstract fun settingDao(): SettingDao

    abstract fun dictDao(): DictDao

    companion object {
        private const val DB_NAME = "novel_reader.db"

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * v1 → v2：新增三张词书表。
         *
         * **为什么要认真写这条迁移**：书架、阅读进度、已生成的目录都在这个库里，
         * 一旦靠 `fallbackToDestructiveMigration()` 兜底，升级就会把用户的资料清空。
         * 下面的建表语句与 Room 生成的建表语句逐字一致（改字段时必须同步改这里）。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dictionaries` (" +
                        "`dict_id` TEXT NOT NULL, `name` TEXT NOT NULL, `format` TEXT NOT NULL, " +
                        "`source_name` TEXT NOT NULL, `entry_count` INTEGER NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, `priority` INTEGER NOT NULL, " +
                        "`added_at` INTEGER NOT NULL, PRIMARY KEY(`dict_id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dict_entries` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dict_id` TEXT NOT NULL, " +
                        "`normalized` TEXT NOT NULL, `word` TEXT NOT NULL, `phonetic` TEXT, " +
                        "`translation` TEXT, `definition` TEXT, `pos` TEXT, `exchange` TEXT, " +
                        "`collins` INTEGER NOT NULL, `oxford` INTEGER NOT NULL, `tag` TEXT, " +
                        "`bnc` INTEGER NOT NULL, `frq` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dict_entries_dict_id_normalized` " +
                        "ON `dict_entries` (`dict_id`, `normalized`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dict_entries_normalized` " +
                        "ON `dict_entries` (`normalized`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dict_forms` (" +
                        "`dict_id` TEXT NOT NULL, `form` TEXT NOT NULL, `entry_id` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`dict_id`, `form`, `entry_id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dict_forms_form` ON `dict_forms` (`form`)",
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2)
                // 仍然保留破坏性升级做兜底：只有"将来新增版本却没写迁移"时才触发，
                // 正常升级路径由上面的 Migration 承担，不会清空书架与进度。
                .fallbackToDestructiveMigration()
                .build()
    }
}
