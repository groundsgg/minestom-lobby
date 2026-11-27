# Lobby Guide translator context

The persistent Guide welcomes players at the lobby spawn. Use a friendly, concise tone and address one player. English and German are the current graph languages. The English curly apostrophes and German umlauts are intentional Unicode characters.

The opening choices lead to games, travel, orientation or farewell, in that order. Games explains the compass in the first hotbar slot and offers the Duel menu. Travel offers the Navigator for selecting another lobby or region. Orientation explains `/spawn`. Back returns to the opening choices. A rejected or unavailable menu shows the same recoverable explanation. Successful menu actions finish the conversation.

Keep `welcome`, `games`, `travel`, `orientation`, `unavailable`, `farewell` and the `.choice` keys stable. Choices should be short enough to scan. Preserve `/spawn`, Grounds and the actual game/navigation meaning. There are no available text arguments; escape literal braces using the SDK syntax described in the parent authoring guide.

The host authorizes only `grounds:lobby/open_navigator` and `grounds:lobby/open_duel_menu`, with empty argument maps and no durable effects. The context file documents that contract; it cannot extend it. NPC placement, the Guide voice and menu implementation are outside this project.
