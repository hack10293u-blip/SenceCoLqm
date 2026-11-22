package vn.codex.skinlab

internal fun needsResourceFix(connected: Boolean, writable: Boolean, previouslyFixed: Boolean, versionPresent: Boolean?): Boolean =
    connected && !writable && (!previouslyFixed || versionPresent == false)
