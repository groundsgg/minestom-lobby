package gg.grounds.minestom.lobby.scene

import gg.grounds.dialogue.core.definition.DialogueDefinition.Action
import gg.grounds.dialogue.core.definition.DialogueDefinition.End
import gg.grounds.dialogue.core.definition.DialogueDefinition.Message
import gg.grounds.lobby.scene.LobbySceneCatalogs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LobbyGuideContentTest {
    @Test
    fun `validated content supports German and English with complete navigation`() {
        val validated = LobbyGuideContent.validated("guide-test")
        val definition = validated.definition()

        assertEquals(LobbyGuideContent.DIALOGUE_ID, definition.id())
        assertTrue(definition.locales().supported().containsAll(listOf("en", "de")))
        assertTrue(
            LobbyGuideContent.texts.locales().keys.containsAll(definition.locales().supported())
        )
        assertEquals(setOf(LobbyGuideContent.SPEAKER_ROLE), validated.catalog().speakers())

        val root = definition.nodes()[definition.entry()] as Message
        assertEquals(
            setOf("games", "travel", "orientation", "goodbye"),
            root.choices().map { it.id }.toSet(),
        )
        root.choices().forEach { assertNotNull(definition.nodes()[it.to]) }

        val backChoices =
            definition.nodes().values.filterIsInstance<Message>().flatMap { message ->
                message.choices().filter { it.id == "back" }
            }
        assertTrue(backChoices.isNotEmpty())
        assertTrue(backChoices.all { it.to == definition.entry() })
        assertTrue(definition.nodes().values.filterIsInstance<End>().isNotEmpty())
    }

    @Test
    fun `menu actions are explicit non-durable providers and every outcome is handled`() {
        val validated = LobbyGuideContent.validated("guide-test")
        val actions = validated.catalog().actions()

        assertEquals(
            setOf(LobbyGuideContent.NAVIGATOR_ACTION, LobbyGuideContent.DUELS_ACTION),
            actions.keys,
        )
        assertTrue(actions.values.all { it.arguments().isEmpty() && !it.durable() })

        val definition = validated.definition()
        val actionNodes = definition.nodes().values.filterIsInstance<Action>()
        assertEquals(2, actionNodes.size)
        actionNodes.forEach { action ->
            assertTrue(action.arguments().isEmpty())
            assertEquals("menu_unavailable", action.onRejected())
            assertEquals("menu_unavailable", action.onUnavailable())
            assertEquals("menu_unavailable", action.onUncertain())
            assertEquals("end", action.onSuccess())
            assertTrue(action.key() in actions.keys)
        }

        assertEquals(LobbySceneCatalogs.OPEN_NAVIGATOR.value, LobbyGuideContent.NAVIGATOR_ACTION)
        assertEquals(LobbySceneCatalogs.OPEN_DUEL_MENU.value, LobbyGuideContent.DUELS_ACTION)
        assertTrue(definition.nodes()["end"] is End)
        assertEquals("end", (definition.nodes()["farewell"] as Message).next())
    }
}
