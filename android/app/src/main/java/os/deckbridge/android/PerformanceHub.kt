package os.deckbridge.android

import os.deckbridge.logic.PerformanceAction
import os.deckbridge.logic.TempoAssembler

object PerformanceHub {
    var onAction: ((PerformanceAction) -> Unit)? = null
    var tempo: TempoAssembler? = null
}
