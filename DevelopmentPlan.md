1. Complete app features

   * Consider using CompanionDeviceService with startObservingDevicePresence instead of broadcast receiver. (Done)
   * Target SDK version 36 (required for Play Store) (Done)
   * Add Log. (Done)
     Log should accesible via the action bar probably.
     The history of the log should be limited to the last N events.
     When viewed in the UI the log should be show in a human friendly way with configurable level of detail.
     The Log should be stored in json and contain:
     * Event type: SetCodecConfig, GetCodecConfig, UI/system events, error states (for example timeouts), test labels
     * Time information
     * Our own and the android model for the codec.
     * Target device
     * Trigger of the event. For example Ui update, device connected, validate device config, ...
   * Profile Log Activity (Done)
   * Setup testing using Mobly (Done)
   * Add open source licences somewhere (Done)
   * There is potential issue with applying the codec after a restart when A2DP connects before the first unlock and the app nevers sees the broadcast.
     C14 and C15 in mobly_tests/tests/reboot_test.py show it and fail until it's fixed.

2. Polish UI

   * Review color palette and add dark mode
   * Improve MainActivity UI
     * Probably don't use a plain top bar
     * The devices should probably be centered vertically
     * Validate the UI for different screen sizes
     * Show a fun placeholder graphic when no devices are connected
   * Improve LogActivity UI
     * LogActivity share mode selection should come up from the bottom and have its UI improved
   * Design an App icon
   * Add screenshot tests (light and dark, font scales, landscape, screen sizes) once the UI is polished, see TestConcept.md (V12)

3. Prepare to publish
   * Write README.md, LICENCE.md for Github repo
   * Optionally setup CI using GitHub actions
   * Write App description for Play Store
   * Write description for Personal website