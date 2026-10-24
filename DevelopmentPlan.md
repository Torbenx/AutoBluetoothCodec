1. Complete app features

   * Consider using CompanionDeviceService with startObservingDevicePresence instead of broadcast receiver. (Done)
   * Target SDK version 36 (required for Play Store)
   * Add Log.
     Log should accesible via the action bar probably.
     The history of the log should be limited to the last N events.
     When viewed in the UI the log should be show in a human friendly way with configurable level of detail.
     The Log should be stored in json and contain:
     * Event type, either SetCodecConfig or GetCodecConfig or a UI/system trigger as well as error conditions
     * Time information
     * Our own and the android model for the codec.
     * Target device
     * Trigger of the event. For example Ui update, device connected, validate device config, ...+
   * Add open source licences somewhere

2. Polish UI

   * Review color palette and add dark mode
   * Improve MainActivity UI
     * Probably don't use a plain top bar
     * The devices should probably be centered vertically
     * Validate the UI for different screen sizes
     * Show a fun placeholder graphic when no devices are connected
   * Review other activities as necessary
   * Design an App icon

3. Prepare to publish
   * Write README.md, LICENCE.md for Github repo
   * Optionally setup CI using GitHub actions
   * Write App description for Play Store
   * Write description for Personal website