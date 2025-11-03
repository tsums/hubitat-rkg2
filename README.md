# Ring Keypad Gen 2 HSM Driver for Hubitat

This driver is forked from https://github.com/Mavrrick/Hubitat-by-Mavrrick, which was originally forked from https://github.com/jkister/hubitat. General improvements to code organization and documentation have been made based on my experience with my Ring Keypad Gen 2.

Improvements over other drivers:
* Power source reporting and battery state changes (upgraded to battery v2).
* Implemented Chime capability with 9 sounds. The keypad can now be used as a siren or sound chime in basic rules more easily.
* Added additional config parameters for display timeout, heartbeat interval, supervision retries, etc.
* Added logging for Ring's reported "software fault" and "dropped frame" notifications.

Note: This fork of the community driver **only supports HSM integration**. The keypad will **not** change state on its own, it expects callbacks from HSM to correctly perform state transitions.

### Known Issues
* Arming via digital commands will trigger keypad state changes even if HSM declines to arm due to e.g. an open sensor.
