package com.paveltyapkin.phrasoteca.data

import com.paveltyapkin.phrasoteca.model.CardCollection
import com.paveltyapkin.phrasoteca.model.PhraseCard

object DemoCardRepository {

    fun getCollection(): CardCollection {

        val cards = listOf(

            PhraseCard(
                id = 1L,
                frontText = "Здравствуйте! Я живу в квартире 509.",
                backText = "¡Hola! Vivo en el departamento 509.",
                frontLanguage = "RUSSIAN",
                backLanguage = "ESPAÑOL"
            ),

            PhraseCard(
                id = 2L,
                frontText = "Где находится ближайшая станция метро?",
                backText = "¿Dónde está la estación de metro más cercana?",
                frontLanguage = "RUSSIAN",
                backLanguage = "ESPAÑOL"
            ),

            PhraseCard(
                id = 3L,
                frontText = "Сколько это стоит?",
                backText = "¿Cuánto cuesta?",
                frontLanguage = "RUSSIAN",
                backLanguage = "ESPAÑOL"
            ),

            PhraseCard(
                id = 4L,
                frontText = "Можно оплатить банковской картой?",
                backText = "¿Puedo pagar con tarjeta?",
                frontLanguage = "RUSSIAN",
                backLanguage = "ESPAÑOL"
            ),

            PhraseCard(
                id = 5L,
                frontText = "Извините, я пока плохо говорю по-испански.",
                backText = "Disculpe, todavía no hablo muy bien español.",
                frontLanguage = "RUSSIAN",
                backLanguage = "ESPAÑOL"
            )
        )

        return CardCollection(
            id = 1L,
            name = "Basic phrases",
            cards = cards
        )
    }
}
