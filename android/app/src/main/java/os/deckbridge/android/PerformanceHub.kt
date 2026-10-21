package os.deckbridge.android

import os.deckbridge.logic.PerformanceAction

object PerformanceHub {
    var onAction: ((PerformanceAction) -> Unit)? = null
}
