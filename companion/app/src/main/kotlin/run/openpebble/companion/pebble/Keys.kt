package run.openpebble.companion.pebble

/**
 * AppMessage numeric keys. Numbers match `watchapp/src/c/app_message.h`.
 *
 * Per `BasePebbleListenerService.onMessageReceived`, **all received numbers
 * arrive as UInt32 or Int32 regardless of the wire size the watch used**, so
 * `data[KEY_CMD_STOP]` is a `PebbleDictionaryItem.UInt32` even though the
 * watch wrote it as uint8. When sending the other way we use the requested
 * wire size so the watch reads via `dict_find(iter, key)->value->uint16` etc.
 */
object Keys {
    // Wire keys are UInt on the Kotlin side (PebbleDictionary = Map<UInt, …>).

    // Watch → Companion
    const val CMD_STOP:     UInt = 2u

    // Companion → Watch
    const val RUN_STARTED:  UInt = 110u
    const val RUN_STOPPED:  UInt = 111u
    const val PACE_CURRENT: UInt = 120u  // uint16, sec/mi capped 3600
    const val TIME:         UInt = 122u  // uint32, seconds
    const val DISTANCE:     UInt = 123u  // uint32, hundredths of a mile
}
