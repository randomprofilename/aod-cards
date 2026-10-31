package com.aodcards

/** Pure card transformations shared by notification refreshes and regression tests. */
object CardGrouper {
    fun prepare(parsed: List<NotificationCard>, ongoingMode: Int): List<NotificationCard> {
        val parsedCards = collapseSystemGroups(parsed)
        val permanent = parsedCards.filter { it.ongoing }
        val regular = parsedCards.filterNot { it.ongoing }
        val ongoingCards = when (ongoingMode) {
            0 -> permanent
            2 -> listOfNotNull(groupOngoing(permanent))
            else -> emptyList()
        }
        return (regular + ongoingCards)
            .sortedWith(
                compareByDescending<NotificationCard> { it.ongoing || it.isOngoingGroup }
                    .thenBy { it.systemRank }
                    .thenByDescending { it.postTime }
            )
    }

    fun collapseSystemGroups(cards: List<NotificationCard>): List<NotificationCard> {
        val grouped = cards.filter { it.systemGroupKey != null }.groupBy { "${it.packageName}|${it.systemGroupKey}" }
        val handled = grouped.values.flatten().mapTo(hashSetOf()) { it.key }
        val result = cards.filterNot { it.key in handled }.toMutableList()
        grouped.values.forEach { group ->
            val summary = group.firstOrNull { it.isSystemGroupSummary }
            val children = group.filterNot { it.isSystemGroupSummary }
            if (summary == null && children.size > 1) {
                // Some apps publish a summary without visible text. Parsing may discard
                // that summary, but the children still carry a valid system group key.
                children.groupBy { it.ongoing }.values.forEach { members ->
                    val newest = withSenderImage(members)
                    result += if (members.size == 1) newest else newest.copy(
                        groupCount = members.sumOf { it.groupCount.coerceAtLeast(1) },
                        systemRank = members.minOf { it.systemRank },
                        isCollapsedGroup = !newest.isConversation
                    )
                }
                return@forEach
            }
            if (summary != null && children.isNotEmpty()) {
                // Android's automatic summary can be ONGOING even when its children
                // are ordinary messages. The summary is a container, not a child.
                // Keep mixed groups separate so hiding ongoing items cannot hide messages.
                children.groupBy { it.ongoing }.values.forEach { members ->
                    val newest = withSenderImage(members)
                    result += newest.copy(
                        groupCount = members.sumOf { it.groupCount.coerceAtLeast(1) },
                        isCollapsedGroup = members.size > 1 && !newest.isConversation,
                        isSystemGroupSummary = false,
                        systemRank = minOf(summary.systemRank, members.minOf { it.systemRank })
                    )
                }
            } else {
                result += group.filterNot { it.isSystemGroupSummary }
                if (summary != null && children.isEmpty()) result += summary
            }
        }
        return result
    }

    private fun withSenderImage(members: List<NotificationCard>): NotificationCard {
        val newest = members.maxBy { it.postTime }
        if (newest.senderImage != null || newest.senderName.isNullOrBlank()) return newest
        val image = members.asSequence()
            .filter { it.senderName == newest.senderName && it.senderImage != null }
            .maxByOrNull { it.postTime }?.senderImage
        return if (image == null) newest else newest.copy(senderImage = image)
    }

    fun groupOngoing(cards: List<NotificationCard>): NotificationCard? {
        if (cards.isEmpty()) return null
        val sorted = cards.sortedByDescending { it.postTime }
        val newest = sorted.first()
        fun detail(card: NotificationCard) = card.title.takeIf { it.isNotBlank() }
            ?: card.text.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: card.appName.takeIf { card.isCustomContent }
        val summaries = sorted.mapNotNull(::detail).distinct()
        return newest.copy(
            appName = "",
            senderName = null,
            title = "",
            text = summaries.joinToString("\n"),
            ongoing = true,
            previewImage = null,
            senderImage = null,
            groupCount = sorted.size,
            isOngoingGroup = true,
            systemRank = sorted.minOf { it.systemRank }
        )
    }


}

/** Preserve the app's conversation identity even when the system bundles the app. */
object NotificationGrouping {
    fun groupKey(isConversation: Boolean, appGroup: String?, systemGroup: String?, isSystemGroup: Boolean): String? =
        if (isConversation) appGroup?.takeIf { it.isNotBlank() }?.let { "conversation:$it" }
        else systemGroup?.takeIf { isSystemGroup && it.isNotBlank() }
}
