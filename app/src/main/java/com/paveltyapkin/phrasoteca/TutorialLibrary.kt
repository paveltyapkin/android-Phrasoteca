package com.paveltyapkin.phrasoteca

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import com.paveltyapkin.phrasoteca.data.local.CardEntity
import com.paveltyapkin.phrasoteca.data.local.CollectionEntity
import com.paveltyapkin.phrasoteca.data.local.PhrasotecaDatabase

/** Bundled, one-sided tutorial collection; does not count toward manual-card achievements. */
object TutorialLibrary {
    const val VERSION_MARKER = "x-phrasoteca-tutorial-v1"
    private const val PREF_INSTALLED = "phrasoteca_tutorial_initialized_v1"
    const val TOTAL_CARDS = 21
    const val REQUIRED_CARDS = 16 // ceil(75% of 21)

    private data class TutorialCard(
        val index: Int, val ruTitle: String, val ruBody: String,
        val enTitle: String, val enBody: String
    )
    private val cards = listOf(
        TutorialCard(1, """Добро пожаловать""", """Phrasoteca хранит фразы, заметки и карточки. Листай их и показывай собеседнику. Эта коллекция — краткое руководство.""", """Welcome""", """Phrasoteca keeps phrases, notes and cards. Browse them or show them to another person. This collection is a quick guide."""),
        TutorialCard(2, """Коллекции""", """Создавай отдельные коллекции для поездок, языков или повседневных задач. Каждая коллекция может иметь свои настройки карточек.""", """Collections""", """Create separate collections for trips, languages or daily tasks. Each collection has its own card settings."""),
        TutorialCard(3, """Создание карточки""", """Открой коллекцию и нажми кнопку добавления. Введи текст на лицевой стороне, а при необходимости — на обороте.""", """Creating a card""", """Open a collection and use the add button. Enter the front-side text and, when needed, the back-side text."""),
        TutorialCard(4, """Две стороны""", """У двусторонней карточки можно нажать на поверхность, чтобы перевернуть её. Односторонние заметки не переворачиваются.""", """Two sides""", """Tap a two-sided card to flip it. One-sided notes do not flip."""),
        TutorialCard(5, """Перелистывание""", """Переключай карточки горизонтальными свайпами. Если свайпы отключены, используй кнопки навигации.""", """Browsing cards""", """Swipe horizontally to switch cards. If swipe navigation is disabled, use the navigation buttons."""),
        TutorialCard(6, """Папки""", """Большие коллекции можно организовывать по папкам. Так будет легче найти нужную фразу в нужной ситуации.""", """Folders""", """Organize larger collections into folders to find the right phrase for each situation."""),
        TutorialCard(7, """Поиск""", """Поиск помогает находить нужные карточки по тексту. При поиске внутри коллекции проверяются обе стороны карточки.""", """Search""", """Search helps you find cards by text. A search within a collection checks both sides of each card."""),
        TutorialCard(8, """Клонирование""", """Если нужна похожая карточка, клонируй существующую и отредактируй копию: исходная останется без изменений.""", """Cloning""", """Clone a card and edit the copy when you need a similar one. The original stays unchanged."""),
        TutorialCard(9, """Текст в буфер обмена""", """Через меню карточки можно скопировать её текущую сторону или весь текст сразу.""", """Copying text""", """Use the card menu to copy the current side or both sides to the clipboard."""),
        TutorialCard(10, """Озвучивание TTS""", """У языковых карточек доступно озвучивание текста. Качество и наличие голосов зависят от TTS-движка Android.""", """Text-to-speech""", """Language cards can speak their text. Voice availability and quality depend on the Android TTS engine."""),
        TutorialCard(11, """Ответы Да / Нет""", """В карточках YES_NO можно быстро вывести утвердительный или отрицательный ответ на языке оборотной стороны.""", """Yes / No answers""", """YES_NO cards can display a positive or negative answer in the language used on the back of the card."""),
        TutorialCard(12, """Числовые значения""", """Карточки QUANTITY позволяют временно указать число и единицу измерения. Исходный текст карточки не меняется.""", """Numbers and quantities""", """QUANTITY cards let you temporarily enter a number and a unit. The original card text is not changed."""),
        TutorialCard(13, """Show Queue""", """Отметь несколько карточек квадратом выбора и запусти Show Queue. В режиме показа будут перелистываться только выбранные карточки.""", """Show Queue""", """Check several cards and start Show Queue. Only selected cards are shown in that session."""),
        TutorialCard(14, """Завершение показа""", """После завершения Show Queue выбор очищается. Для следующего разговора можно собрать новую подборку.""", """Ending a session""", """When you finish Show Queue, the selection is cleared. You can prepare a fresh set for the next conversation."""),
        TutorialCard(15, """Idiom Challenge""", """Тренируй понимание английских идиом: выбирай верное значение из четырёх вариантов. Результат появляется в конце игры.""", """Idiom Challenge""", """Practice English idioms by choosing the correct meaning from four options. See your score at the end."""),
        TutorialCard(16, """Phrase Challenge DEMO""", """Демонстрационная игра тренирует практические фразы. Иногда ответ можно угадать по форме; алгоритм подбора вариантов ещё экспериментальный.""", """Phrase Challenge DEMO""", """This demo practices everyday phrases. Some answers may be easy to guess; its distractor-selection algorithm is experimental."""),
        TutorialCard(17, """Обмен через QR""", """Карточкой или коллекцией можно поделиться через QR-код. Проверяй содержимое перед отправкой другим людям.""", """QR sharing""", """You can share a card or collection with a QR code. Review the contents before sharing them."""),
        TutorialCard(18, """Коллекции-сейфы""", """Сейф предназначен для закрытых данных. Запоминай пароль: многократные неверные попытки могут привести к необратимой потере содержимого сейфа.""", """Vault collections""", """A vault is for private data. Remember its password: repeated wrong attempts can irreversibly destroy vault contents."""),
        TutorialCard(19, """Резервные копии""", """Регулярно экспортируй полную JSON-копию, особенно перед обновлением или сменой устройства. Сохраняй файл в надёжном месте.""", """Backups""", """Export a full JSON backup regularly, especially before updating or changing devices. Keep the file somewhere safe."""),
        TutorialCard(20, """Достижения""", """Награды открываются за использование возможностей приложения. Просмотри не менее 75% карточек этой коллекции, чтобы получить достижение «Прилежный ученик».""", """Achievements""", """Earn achievements by using the app. Read at least 75% of these tutorial cards to unlock the Diligent Student achievement."""),
        TutorialCard(21, """Обучение завершено""", """На этом краткое знакомство с Phrasoteca завершено. Надеемся, приложение будет полезно вам каждый день! Чтобы выйти из руководства, нажмите «Назад» или название коллекции в верхней панели. Если руководство больше не нужно, удерживайте его название на главном экране и выберите «Удалить». Позже его можно снова добавить из шаблонов коллекций.""", """You're all set!""", """You've reached the end of this quick introduction to Phrasoteca. We hope the app makes your everyday life a little easier! To leave the guide, tap Back or the collection name in the top bar. If you no longer need the guide, press and hold its collection on the home screen, then choose Delete. You can add it again later from the collection templates.""")
    )

    /** First install only. If deleted, reinstallation requires an explicit menu action. */
    suspend fun initializeIfNeeded(context: Context, db: PhrasotecaDatabase, prefs: SharedPreferences) {
        if (!prefs.getBoolean(PREF_INSTALLED, false)) {
            db.withTransaction {
                if (db.collectionDao().getAll().none { it.frontLanguageTag == VERSION_MARKER }) {
                    insertCollection(context, db)
                }
            }
            prefs.edit().putBoolean(PREF_INSTALLED, true).apply()
        }
        // One-time content upgrade for users who already installed the 20-card guide.
        // Never recreate a collection deliberately deleted by the user.
        val upgradeKey = "phrasoteca_tutorial_card21_upgraded_v1"
        if (!prefs.getBoolean(upgradeKey, false)) {
            db.withTransaction {
                val existing = db.collectionDao().getAll().firstOrNull { it.frontLanguageTag == VERSION_MARKER }
                if (existing != null) {
                    val currentCards = db.cardDao().getByCollection(existing.id)
                    if (currentCards.none { it.frontLanguage == "tutorial:21" }) {
                        insertCard(context, db, existing.id, cards.last())
                    }
                }
            }
            prefs.edit().putBoolean(upgradeKey, true).apply()
        }
    }

    /** Open existing tutorial or create a new copy after deliberate removal. */
    suspend fun getOrInstall(context: Context, db: PhrasotecaDatabase, prefs: SharedPreferences): Long {
        val id = db.withTransaction {
            db.collectionDao().getAll().firstOrNull { it.frontLanguageTag == VERSION_MARKER }?.id
                ?: insertCollection(context, db)
        }
        prefs.edit().putBoolean(PREF_INSTALLED, true).apply()
        return id
    }

    private suspend fun insertCollection(context: Context, db: PhrasotecaDatabase): Long {
        val collectionId = db.collectionDao().insert(CollectionEntity(
            name = context.getString(R.string.tutorial_collection_title),
            sortOrder = db.collectionDao().nextSortOrder(),
            defaultCardType = "GENERAL",
            frontLabel = context.getString(R.string.tutorial_card_label),
            backLabel = "",
            sideMode = "ONE_SIDED",
            frontLanguageTag = VERSION_MARKER,
            frontColor = "#F2F4FD"
        ))
        cards.forEach { card -> insertCard(context, db, collectionId, card) }
        return collectionId
    }

    private suspend fun insertCard(context: Context, db: PhrasotecaDatabase, collectionId: Long, card: TutorialCard) {
        val russian = context.resources.configuration.locales[0].language == "ru"
        val title = if (russian) card.ruTitle else card.enTitle
        val body = if (russian) card.ruBody else card.enBody
        db.cardDao().insert(CardEntity(
            collectionId = collectionId,
            frontText = "$title\n\n$body",
            backText = "",
            frontLanguage = "tutorial:%02d".format(java.util.Locale.ROOT, card.index),
            backLanguage = "",
            sortOrder = card.index,
            cardType = "GENERAL"
        ))
    }

    fun cardIndex(collection: CollectionEntity?, card: CardEntity?): Int? {
        if (collection?.frontLanguageTag != VERSION_MARKER || card == null) return null
        val index = card.frontLanguage.removePrefix("tutorial:").toIntOrNull() ?: return null
        return index.takeIf { card.frontLanguage == "tutorial:%02d".format(java.util.Locale.ROOT, it) && it in 1..TOTAL_CARDS }
    }
}
