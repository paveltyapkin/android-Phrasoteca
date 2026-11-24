package com.paveltyapkin.phrasoteca.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
    // GENERAL, LANGUAGE or CHECKLIST. VAULT is reserved for a separate encrypted store.
    @ColumnInfo(defaultValue = "'LANGUAGE'") val defaultCardType: String = "LANGUAGE",
    @ColumnInfo(defaultValue = "'Front'") val frontLabel: String = "Front",
    @ColumnInfo(defaultValue = "'Back'") val backLabel: String = "Back",
    @ColumnInfo(defaultValue = "'#F2F4FD'") val frontColor: String = "#F2F4FD",
    @ColumnInfo(defaultValue = "'#E5F3ED'") val backColor: String = "#E5F3ED",
    @ColumnInfo(defaultValue = "'TWO_SIDED'") val sideMode: String = "TWO_SIDED",
    // Empty means legacy automatic TTS detection from the side label.
    @ColumnInfo(defaultValue = "''") val frontLanguageTag: String = "",
    @ColumnInfo(defaultValue = "''") val backLanguageTag: String = "",
    @ColumnInfo(defaultValue = "''") val iconKey: String = ""
)
