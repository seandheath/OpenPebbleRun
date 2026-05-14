package run.openpebble.companion.pebble

/**
 * AppMessage numeric keys. Mirrors `watchapp/src/c/app_message.h` per spec §7.
 *
 * The watchapp side is authoritative — when adding new keys, update the C
 * header first, then this file. Numbers are pinned; do not renumber.
 *
 * Note on type promotion: per `BasePebbleListenerService.onMessageReceived`
 * docs, **all received numbers arrive as UInt32 or Int32 regardless of the
 * wire size the watch used**. So `data[KEY_CMD_STOP]` will be a
 * `PebbleDictionaryItem.UInt32` even though the watch wrote it as uint8.
 * When sending the other direction, we use the requested wire size so the
 * watch sees the correct type via `dict_find(iter, key)->value->uint16` etc.
 */
object Keys {
    // Wire keys are UInt on the Kotlin side (PebbleDictionary = Map<UInt, …>).

    // Watch → Companion. (Key 1 / CMD_START was retired together with the
    // pre-run screen — runs are now initiated from MainActivity. Numbers
    // are pinned; don't reuse 1.)
    const val CMD_STOP:  UInt = 2u

    // Companion → Watch. (Key 111 / RUN_FAILED was retired with the
    // pre-run start-error path; don't reuse 111.)
    const val RUN_STARTED:        UInt = 110u
    const val PACE_CURRENT:       UInt = 120u  // uint16, sec/mi capped 3600
    const val TIME:               UInt = 122u  // uint32, seconds
    const val DISTANCE:           UInt = 123u  // uint32, hundredths of a mile

    // Reserved (spec §7.2): 113 HR_SOURCE_EXTERNAL, 114 HR_SOURCE_INTERNAL,
    // 124 HR_EXTERNAL — held back for the deferred external-HR feature
    // (docs/log.md 2026-05-13 "Target F-Droid only; defer external HR; …").
}
