/*
    Ring Keypad Gen 2 - HSM Driver

    Copyright 2020 -> 2021 Hubitat Inc.  All Rights Reserved
    Special Thanks to Bryan Copeland (@bcopeland) for writing and releasing this code to the community!

    Note: This fork of the community driver only supports HSM integration. The keypad will **not** change
          state on its own, it expects callbacks from HSM to correctly perform state transitions.

    1.4.0 - 10/28/25 - Format code, improve comments and debug logging, improve function naming and
                       variable names. Improve HSM integration and fix misc. bugs. Add chime capability. 
                       Improve readability and fetching power status. - @tsums
    1.3.1 - 05/13/25 - Fix motion event parsing, fix debug logging in NotificationReport parse
    1.3.0 - 04/13/25 - Update to eliminate manual hex parsing (for ZWaveJS compatibility) @jtp10181
    1.2.5 - 08/02/22 - Rework Driver to allow options to use Subscription to armingIn device status for apps that
                       support it @mavrrick58
    1.2.4 - 08/01/22 - Rollback Changes
    1.2.3 - 07/31/22 - remove Redundent calls causing multiple events in HSM. Added Additional Logging. @mavrrick58
    1.2.2 - 06/09/22 - @dkilgore90 add "validCode" attribute and "validateCheck" preference
    1.2.1 - 04/14/22 - Bug hunting
    1.2.0 - 04/04/22 - Fixed Tones
    ---
    1.0.0 - 11/11/21 - Initial Community Release
*/

import groovy.json.JsonOutput
import groovy.transform.Field
import static hubitat.zwave.commands.batteryv2.BatteryReport.CHARGING_STATUS_CHARGING
import static hubitat.zwave.commands.batteryv2.BatteryReport.CHARGING_STATUS_DISCHARGING
import static hubitat.zwave.commands.batteryv2.BatteryReport.CHARGING_STATUS_MAINTAINING
import static hubitat.zwave.commands.entrycontrolv1.EntryControlNotification.EVENT_TYPE_ALERT_MEDICAL
import static hubitat.zwave.commands.entrycontrolv1.EntryControlNotification.EVENT_TYPE_ARM_AWAY
import static hubitat.zwave.commands.entrycontrolv1.EntryControlNotification.EVENT_TYPE_ARM_HOME
import static hubitat.zwave.commands.entrycontrolv1.EntryControlNotification.EVENT_TYPE_CACHED_KEYS
import static hubitat.zwave.commands.entrycontrolv1.EntryControlNotification.EVENT_TYPE_CACHING
import static hubitat.zwave.commands.entrycontrolv1.EntryControlNotification.EVENT_TYPE_DISARM_ALL
import static hubitat.zwave.commands.entrycontrolv1.EntryControlNotification.EVENT_TYPE_ENTER
import static hubitat.zwave.commands.entrycontrolv1.EntryControlNotification.EVENT_TYPE_FIRE
import static hubitat.zwave.commands.entrycontrolv1.EntryControlNotification.EVENT_TYPE_POLICE
import static hubitat.zwave.commands.indicatorv3.IndicatorSet.INDICATOR_TYPE_ALARM
import static hubitat.zwave.commands.indicatorv3.IndicatorSet.INDICATOR_TYPE_ALARM_CO
import static hubitat.zwave.commands.indicatorv3.IndicatorSet.INDICATOR_TYPE_ALARM_SMOKE
import static hubitat.zwave.commands.indicatorv3.IndicatorSet.INDICATOR_TYPE_ARMED_AWAY
import static hubitat.zwave.commands.indicatorv3.IndicatorSet.INDICATOR_TYPE_ARMED_STAY
import static hubitat.zwave.commands.indicatorv3.IndicatorSet.INDICATOR_TYPE_CODE_REJECTED
import static hubitat.zwave.commands.indicatorv3.IndicatorSet.INDICATOR_TYPE_DISARMED
import static hubitat.zwave.commands.indicatorv3.IndicatorSet.INDICATOR_TYPE_ENTRY_DELAY
import static hubitat.zwave.commands.indicatorv3.IndicatorSet.INDICATOR_TYPE_EXIT_DELAY
import static hubitat.zwave.commands.notificationv8.NotificationGet.NOTIFICATION_TYPE_BURGLAR 
import static hubitat.zwave.commands.notificationv8.NotificationGet.NOTIFICATION_TYPE_POWER_MANAGEMENT
import static hubitat.zwave.commands.notificationv8.NotificationGet.NOTIFICATION_TYPE_SYSTEM 
import static hubitat.zwave.commands.supervisionv1.SupervisionReport.SUCCESS as SUPERVISION_SUCCESS

@Field static Integer AC_MAINS_DISCONNECTED = 0x02
@Field static Integer AC_MAINS_RECONNECTED = 0x03
@Field static Integer BATTERY_CHARGING = 0x0C
@Field static Integer BATTERY_FULL = 0x0D

@Field static Integer MOTION_DETECTION = 0x08
@Field static Integer STATE_IDLE = 0x00

@Field static Integer SYSTEM_SOFTWARE_FAILURE = 0x04

@Field static String SECURITY_KEYPAD_ARMED_AWAY = "armed away"
@Field static String SECURITY_KEYPAD_ARMED_HOME = "armed home"
@Field static String SECURITY_KEYPAD_ARMED_NIGHT = "armed night"
@Field static String SECURITY_KEYPAD_DISARMED = "disarmed"
@Field static String SECURITY_KEYPAD_EXIT_DELAY = "exit delay"

// TODO: The driver is using these for the 'alarm' attribute,
// but that should really just be ["strobe", "off", "both", "siren"].
// Maybe we should move these into state information instead?
@Field static String ALARM_STATUS_ARMING_HOME = "armingHome"
@Field static String ALARM_STATUS_ARMING_AWAY = "armingAway"

def version() {
    return '1.4.0'
}

metadata {
    definition(name: 'Ring Alarm Keypad G2 - HSM', namespace: 'tsums', author: 'tsums') {
        capability 'Actuator'
        capability 'Sensor'
        capability 'Configuration'
        capability 'SecurityKeypad'
        capability 'Battery'
        capability 'Alarm'
        capability 'Chime'
        capability 'PowerSource'
        capability 'Motion Sensor'
        capability 'PushableButton'
        capability 'HoldableButton'

        command 'refresh'
        command 'entry'
        command 'setArmNightDelay', ['number']
        command 'setArmAwayDelay', ['number']
        command 'setArmHomeDelay', ['number']
        command 'setPartialFunction'
        command 'playTone', [[name: 'Play Tone', type: 'STRING', description: 'Tone_1, Tone_2, etc.']]
        command 'volAnnouncement', [[name:'Announcement Volume', type:'NUMBER', description: 'Volume level (1-10)']]
        command 'volKeytone', [[name:'Keytone Volume', type:'NUMBER', description: 'Volume level (1-10)']]
        command 'volSiren', [[name:'Chime Tone Volume', type:'NUMBER', description: 'Volume level (1-10)']]

        attribute 'alarmStatusChangeTime', 'STRING'
        attribute 'alarmStatusChangeEpochms', 'NUMBER'
        attribute 'armingIn', 'NUMBER'
        attribute 'armAwayDelay', 'NUMBER'
        attribute 'armHomeDelay', 'NUMBER'
        attribute 'armNightDelay', 'NUMBER'
        attribute 'batteryStatus', 'ENUM', ['charging', 'discharging', 'maintaining']
        attribute 'lastCodeName', 'STRING'
        attribute 'lastCodeTime', 'STRING'
        attribute 'lastCodeEpochms', 'NUMBER'
        attribute 'motion', 'STRING'
        attribute 'soundEffects', 'STRING'
        attribute 'validCode', 'ENUM', ['true', 'false']
        attribute 'volAnnouncement', 'NUMBER'
        attribute 'volKeytone', 'NUMBER'
        attribute 'volSiren', 'NUMBER'

        fingerprint mfr:'0346', prod:'0101', deviceId:'0301', inClusters:'0x5E,0x98,0x9F,0x6C,0x55', deviceJoinName: 'Ring Alarm Keypad G2'
    }
    preferences {
        input name: 'about', type: 'paragraph', element: 'paragraph', title: 'Ring Alarm Keypad G2 HSM Driver', description: "${version()}<br>Note:<br>The first 3 Tones are alarm sounds (Siren, Smoke Alarm, CO Alarm) and will flash the indicator bar. The remaining sounds are chime sounds. The invalid code sound also flashes the keypad numbers."
        configParams.each { input it.value.input }
        input name: 'theTone', type: 'enum', title: 'Default Chime Tone', options: [
            ['Tone_1':'(Tone_1) Siren (default)'],
            ['Tone_2':'(Tone_2) Smoke Alarm'],
            ['Tone_3':'(Tone_3) CO Alarm'],
            ['Tone_4':'(Tone_4) Navi'],
            ['Tone_5':'(Tone_5) Guitar'],
            ['Tone_6':'(Tone_6) Windchimes'],
            ['Tone_7':'(Tone_7) DoorBell 1'],
            ['Tone_8':'(Tone_8) DoorBell 2'],
            ['Tone_9':'(Tone_9) Invalid Code Sound'],
        ], defaultValue: 'Tone_1', description: 'Default tone for playback.'
        input name: 'instantArming', type: 'bool', title: 'Enable Codeless Arming', defaultValue: false, description: 'If enabled, system can be armed without a valid code.'
        input name: 'validateCheck', type: 'bool', title: 'Validate codes submitted with checkmark', defaultValue: false, description: 'Allow valid code submission with the check button.'
        input name: 'optEncrypt', type: 'bool', title: 'Enable lock code encryption', defaultValue: false, description: 'Encrypt lock codes inside the driver.'
        input name: 'logEnable', type: 'bool', title: 'Enable debug logging', defaultValue: true
        input name: 'txtEnable', type: 'bool', title: 'Enable descriptionText logging', defaultValue: true
    }
}

// Configuration Parameters
// There are PDFs online, but they appear to have some incorrect parameter numbers.
// The most accurate source was from zwae.eu, archived: https://archive.is/WbF5G
@Field static Map configParams = [
    15: [input: [name: 'configParam15', type: 'bool', title: 'Proximity Sensor', description: 'Controls the proximity sensor and accompanying motion reports.', defaultValue: true], parameterSize:1],
    7: [input: [name: 'configParam7', type: 'number', title: 'Long press Emergency Duration', description:'', defaultValue: 3, range:'2..5'], parameterSize:1],
    8: [input: [name: 'configParam8', type: 'number', title: 'Long press Number pad Duration', description:'', defaultValue: 3, range:'2..5'], parameterSize:1],
    10: [input: [name: 'configParam10', type: 'number', title: 'Button Press Display Timeout', description:'Timeout in seconds when any button is pressed', defaultValue: 5, range:'0..30'], parameterSize:1],
    11: [input: [name: 'configParam11', type: 'number', title: 'Status Change Display Timeout', description:'Timeout in seconds when indicator command is received from the hub tochange status', defaultValue: 5, range:'0..30'], parameterSize:1],
    12: [input: [name: 'configParam12', type: 'number', title: 'Security Mode Brightness', description:'', defaultValue: 100, range:'0..100'], parameterSize:1],
    13: [input: [name: 'configParam13', type: 'number', title: 'Key Backlight Brightness', description:'', defaultValue: 100, range:'0..100'], parameterSize:1],
    20: [input: [name: 'configParam20', type: 'number', title: 'System Security Mode Blink Duration', description:'The number of seconds the security mode indicator stays lit when configured to blink periodically via the "System Security Mode Display" configuration parameter.', defaultValue: 2, range:'1..60'], parameterSize:2],
    22: [input: [name: 'configParam22', type: 'number', title: 'System Security Mode Display', description:'Controls the current security mode indicators: 601 = Always On, 1 - 600 = periodic interval, 0 = Always Off, except activity', defaultValue: 0, range:'0..601'], parameterSize:2],
    1: [input: [name: 'configParam1', type: 'number', title: 'Heartbeat Interval', description:'Number of minutes in between battery reports.', defaultValue: 70, range:'1..70'], parameterSize:1],
    21: [input: [name: 'configParam21', type: 'number', title: 'Supervisory Report Retry Timeout', description:'The number of milliseconds waiting for a Supervisory Report response to a Supervisory Get encapsulated command from the device before attempting a retry.', defaultValue: 10000, range:'500..30000'], parameterSize:2],
    2: [input: [name: 'configParam2', type: 'number', title: 'Application Level Retries', description:'Number of application level retries attempted for messages either not ACKed or messages encapsulated via supervision get that did not receive a report.', defaultValue: 1, range:'0..5'], parameterSize:1],
    3: [input: [name: 'configParam3', type: 'number', title: 'Application Level Retry Base Wait Time Period', description:'The number base seconds used in the calculation for sleeping between retry messages.', defaultValue: 5, range:'1..60'], parameterSize:1],
]
@Field static Map armingStates = [
    // TODO figure out why I can't reference e.g. SECURITY_KEYPAD_ARMED_AWAY here - something with static initialization order?
    (INDICATOR_TYPE_DISARMED): [securityKeypadState: 'disarmed', hsmCmd: 'disarm'],
    (INDICATOR_TYPE_ARMED_STAY): [securityKeypadState: 'armed home', hsmCmd: 'armHome'],
    (INDICATOR_TYPE_ARMED_AWAY): [securityKeypadState: 'armed away', hsmCmd: 'armAway'],
]
@Field static Map CMD_CLASS_VERS = [
    0x20: 1, // Basic V1
    0x6F: 1, // Entry Control V1
    0x70: 1, // Configuration V1
    0x71: 8, // Notification V8
    0x80: 2, // Battery V2
    0x85: 2, // Association V2
    0x86: 3, // Version V3
    0x87: 3, // Indicator V3
    0x98: 1  // Security V1
]
// These are factory sounds that can't be changed, so just emit them as the supported sound effects.
@Field static String SOUND_EFFECTS = '{"1":"siren", "2":"smoke alarm", "3":"co alarm", "4":"navi", "5":"guitar", "6":"windchimes", "7":"doorbell 1", "8":"doorbell 2", "9":"invalid code"}'
@Field static Map SOUND_EFFECTS_TO_INDICATOR_ID = [
    1: INDICATOR_TYPE_ALARM, // Siren
    2: INDICATOR_TYPE_ALARM_SMOKE, // Smoke Alarm
    3: INDICATOR_TYPE_ALARM_CO, // CO Alarm
    4: 0x60, // Navi
    5: 0x61, // Guitar
    6: 0x62, // Windchimes
    7: 0x63, // Doorbell 1
    8: 0x64, // Doorbell 2
    9: INDICATOR_TYPE_CODE_REJECTED  // Invalid Code
]
// Depending on the notification code we're sending, we need to raise different
// properties. Property 2 is "on", Property 9 is "volume", Property 1 is "level".
@Field static Map INDICATOR_ID_TO_PROPERTY_ID = [
    (INDICATOR_TYPE_ALARM): 2, // Siren
    (INDICATOR_TYPE_ALARM_SMOKE): 2, // Smoke Alarm
    (INDICATOR_TYPE_ALARM_CO): 2, // CO Alarm
    0x60: 0x09, // Navi
    0x61: 0x09, // Guitar
    0x62: 0x09, // Windchimes
    0x63: 0x09, // Doorbell 1
    0x64: 0x09, // Doorbell 2
    (INDICATOR_TYPE_CODE_REJECTED): 0x01  // Invalid Code
]

void logsOff() {
    log.warn 'debug logging disabled...'
    device.updateSetting('logEnable', [value:'false', type:'bool'])
}

void updated() {
    log.info 'updated...'
    log.info "debug logging is: ${logEnable == true}"
    log.info "description logging is: ${txtEnable == true}"
    log.info "encryption is: ${optEncrypt == true}"
    unschedule()
    if (logEnable) {
        runIn(3600, logsOff)
    }
    sendToDevice(runConfigs())
    updateEncryption()
    volAnnouncement()
    volKeytone()
    volSiren()
}

void installed() {
    initializeVars()
    sendToDevice(setDefaultAssociation())
}

void uninstalled() {}

void initializeVars() {
    // first run only
    sendEvent(name:'codeLength', value: 4)
    sendEvent(name:'maxCodes', value: 100)
    sendEvent(name:'lockCodes', value: '')
    sendEvent(name:'armHomeDelay', value: 0)
    sendEvent(name:'armAwayDelay', value: 15)
    sendEvent(name:'armNightDelay', value: 0)
    sendEvent(name:'volAnnouncement', value: 8)
    sendEvent(name:'volKeytone', value: 8)
    sendEvent(name:'volSiren', value: 8)
    sendEvent(name:'securityKeypad', value: 'disarmed')
    sendEvent(name:'soundEffects', value: SOUND_EFFECTS)
    state.keypadConfig = [entryDelay:5, exitDelay: 5, armNightDelay:5, armAwayDelay:5, armHomeDelay: 5, codeLength: 4, partialFunction: 'armHome']
    state.keypadStatus = INDICATOR_TYPE_DISARMED
    state.initialized = true
}

void configure() {
    logDebug('configure()')
    if (!state.initialized || !state.keypadConfig) {
        initializeVars()
    }
    keypadUpdateStatus(state.keypadStatus, state.type, state.code)
    runIn(5, pollDeviceData)
}

void refresh() {
    logDebug('refresh()')
    // Both of these send a lot of commands with 300 ms in between.
    pollDeviceData()
    runIn(15, pollConfigs)
}

void pollDeviceData() {
    List<String> cmds = []
    cmds.add(zwave.versionV3.versionGet().format())
    cmds.add(zwave.batteryV2.batteryGet().format())
    // Ask for the serial number.
    cmds.add(zwave.manufacturerSpecificV2.deviceSpecificGet(deviceIdType: 1).format())
    // Poll for power status / events.
    cmds.add(zwave.notificationV8.notificationGet(notificationType: NOTIFICATION_TYPE_POWER_MANAGEMENT, event: AC_MAINS_DISCONNECTED).format())
    cmds.add(zwave.notificationV8.notificationGet(notificationType: NOTIFICATION_TYPE_POWER_MANAGEMENT, event: AC_MAINS_RECONNECTED).format())
    cmds.add(zwave.notificationV8.notificationGet(notificationType: NOTIFICATION_TYPE_POWER_MANAGEMENT, event: BATTERY_CHARGING).format())
    cmds.add(zwave.notificationV8.notificationGet(notificationType: NOTIFICATION_TYPE_POWER_MANAGEMENT, event: BATTERY_FULL).format())
    cmds.add(zwave.notificationV8.notificationGet(notificationType: NOTIFICATION_TYPE_BURGLAR, event: 0).format())
    // TODO: Trying to figure out if it will tell us the state of the indicators, but it seems like ... no?
    // All of the reports that come back are missing property 2...
    cmds.add(zwave.indicatorV3.indicatorGet(indicatorId: INDICATOR_TYPE_ALARM).format())
    cmds.add(zwave.indicatorV3.indicatorGet(indicatorId: INDICATOR_TYPE_DISARMED).format())
    cmds.add(zwave.indicatorV3.indicatorGet(indicatorId: INDICATOR_TYPE_ARMED_AWAY).format())
    cmds.add(zwave.indicatorV3.indicatorGet(indicatorId: INDICATOR_TYPE_ARMED_STAY).format())
    cmds.add(zwave.indicatorV3.indicatorSupportedGet(indicatorId: INDICATOR_TYPE_DISARMED).format())
    sendToDevice(cmds)
}

// Updates the keypad indicator status. This will result in the keypad announcing the status e.g.
// "Armed and Home", "Disarmed".
// It would be better to only update the status if we knew it was different, but it seems like
// The keypad doesn't let us poll for indicator status, it seems - All come back missing property 0x02
// from the indicator report.
private void keypadUpdateStatus(Integer status, String type='digital', String code) {
    logDebug("keypadUpdateStatus | status: ${status} type: ${type}")
    sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount: 1, value: 0, indicatorValues: [[indicatorId:status, propertyId:2, value:0xFF]]).format())
    state.keypadStatus = status
    if (state.code != '') {
        type = 'physical'
    }
    // TODO figure out if I can remove the Short cast??? Maps are annoying.
    eventProcess(name: 'securityKeypad', value: armingStates[status as Short].securityKeypadState, type: type, data: state.code)
    state.code = ''
    state.type = 'digital'
}

// -- Configuration functions. --

void setEntryDelay(delay) {
    logDebug("In setEntryDelay (${version()}) - delay: ${delay}")
    state.keypadConfig.entryDelay = delay != null ? delay.toInteger() : 0
}

void setExitDelay(Map delays) {
    logDebug("In setExitDelay (${version()}) - delay: ${delays}")
    state.keypadConfig.exitDelay = (delays?.awayDelay ?: 0).toInteger()
    state.keypadConfig.armNightDelay = (delays?.nightDelay ?: 0).toInteger()
    state.keypadConfig.armHomeDelay = (delays?.homeDelay ?: 0).toInteger()
    state.keypadConfig.armAwayDelay = (delays?.awayDelay ?: 0).toInteger()
}

void setExitDelay(delay) {
    logDebug("In setExitDelay (${version()}) - delay: ${delay}")
    state.keypadConfig.exitDelay = delay != null ? delay.toInteger() : 0
}

void setArmNightDelay(delay) {
    logDebug("In setArmNightDelay (${version()}) - delay: ${delay}")
    state.keypadConfig.armNightDelay = delay != null ? delay.toInteger() : 0
}

void setArmAwayDelay(delay) {
    logDebug("In setArmAwayDelay (${version()}) - delay: ${delay}")
    sendEvent(name:'armAwayDelay', value: delay)
    state.keypadConfig.armAwayDelay = delay != null ? delay.toInteger() : 0
}

void setArmHomeDelay(delay) {
    logDebug("In setArmHomeDelay (${version()}) - delay: ${delay}")
    sendEvent(name:'armHomeDelay', value: delay)
    state.keypadConfig.armHomeDelay = delay != null ? delay.toInteger() : 0
}

void setCodeLength(pincodelength) {
    logDebug("In setCodeLength (${version()}) - pincodelength: ${pincodelength}")
    eventProcess(name:'codeLength', value: pincodelength, descriptionText: "${device.displayName} codeLength set to ${pincodelength}")
    state.keypadConfig.codeLength = pincodelength
    // set zwave entry code key buffer
    // 6F06XX10
    sendToDevice('6F06' + hubitat.helper.HexUtils.integerToHexString(pincodelength.toInteger() + 1, 1).padLeft(2, '0') + '0F')
}

// Used by HSM to configure the partial button arming function.
void setPartialFunction(mode = null) {
    logDebug("In setPartialFucntion (${version()}) - mode: ${mode}")
    if (!(mode in ['armHome', 'armNight'])) {
        log.warn "Custom command used by HSM: ${mode}"
    } else if (mode in ['armHome', 'armNight']) {
        state.keypadConfig.partialFunction = mode
    }
}

// -- Security Keypad Capaibility --

void armNight(delay=state.keypadConfig.armNightDelay) {
    logDebug("In armNight (${version()}) - delay: ${delay}")
    def sk = device.currentValue('securityKeypad')
    if (sk != 'armed night') {
        if (delay > 0 ) {
            exitDelay(delay)
            runIn(delay, armNightEnd)
        } else {
            armNightEnd()
        }
        } else {
        logDebug("In armNight - securityKeypad already set to 'armed night', so skipping.")
    }
}

void armNightEnd() {
    if (!state.code) {
        state.code = ''
    }
    if (!state.type) {
        state.type = 'physical'
    }
    def sk = device.currentValue('securityKeypad')
    if (sk != 'armed night') {
        alarmStatusChangeNow()
    }
}

// armAway is intended to be called by HSM in response to an armingIn event change.
// It is also possible to directly invoke a digital armAway command on the keypad.
// This method needs to determine whether to invoke HSM, or if it was invoked _by_ HSM,
// in order to avoid duplicate events.
void armAway(delay=state.keypadConfig.armAwayDelay) {
    def sk = device.currentValue('securityKeypad')
    def al = device.currentValue('alarm')
    logDebug("armAway | Delay: ${delay}")
    logDebug("armAway | Current SK Status: ${sk}")
    if (sk != SECURITY_KEYPAD_ARMED_AWAY) {
        if (delay > 0) {
            // If we're already arming Away, don't dispatch any events. This happens when
            // HSM sends a duplicate event during delayed arming.
                if (al == ALARM_STATUS_ARMING_AWAY) {
                logDebug("armAway | Already ${ALARM_STATUS_ARMING_AWAY}, not dispatching any events.")
            } else {
                logDebug("armAway | alarm: ${al} - Proceeding with arming away.")
                state.armingIn = delay
                // Change status to avoid looping.
                changeStatus(ALARM_STATUS_ARMING_AWAY)
                if (state.type == 'digital') {
                    // If this was a digital event, then we didn't trigger HSM during the processing
                    // of the EntryControlNotification. Trigger HSM to begin arming.
                    sendEvent(name:'armingIn', value: state.keypadConfig.armAwayDelay, data:[armMode: armingStates[INDICATOR_TYPE_ARMED_AWAY].securityKeypadState, armCmd: armingStates[INDICATOR_TYPE_ARMED_AWAY].hsmCmd], isStateChange:true)
                }
                logDebug("armAway | armingIn: ${state.armingIn}")
                exitDelay(delay)
                runIn(delay, armAwayEnd)
            }
        } else {
            armAwayEnd()
        }
    } else {
        logDebug("armAway | securityKeypad already set to ${SECURITY_KEYPAD_ARMED_AWAY}, skipping.")
    }
}

void armAwayEnd() {
    if (!state.code) {
        state.code = ''
    }
    if (!state.type) {
        state.type = 'physical'
    }
    def sk = device.currentValue('securityKeypad')
    logDebug("armAwayEnd | sk: ${sk} code: ${state.code} type: ${state.type} delay: ${delay}")
    if (sk != SECURITY_KEYPAD_ARMED_AWAY) {
        logDebug("armAwayEnd | arming now.")
        keypadUpdateStatus(INDICATOR_TYPE_ARMED_AWAY, state.type, state.code)
        alarmStatusChangeNow()
        changeStatus('set')
        state.armingIn = 0

        if (state.type == 'digital') {
            // Send the HSM event indicating immediate arming.
            // TODO: Do we need this? It seems redundant, maybe it wouldn't have sent in armAway if digital and no delay?
            sendEvent(name:'armingIn', value: state.keypadConfig.armAwayDelay, data:[armMode: armingStates[INDICATOR_TYPE_ARMED_AWAY].securityKeypadState, armCmd: armingStates[INDICATOR_TYPE_ARMED_AWAY].hsmCmd], isStateChange:true)
        }
    }
}

// armHome is intended to be called by HSM in response to an armingIn event change.
// It is also possible to directly invoke a digital armHome command on the keypad.
// This method needs to determine whether to invoke HSM, or if it was invoked _by_ HSM,
// in order to avoid duplicate events.
void armHome(delay = state.keypadConfig.armHomeDelay) {
    def sk = device.currentValue('securityKeypad')
    def al = device.currentValue('alarm')
    logDebug("armHome | delay: ${delay}")
    logDebug("armHome | Current SK Status: ${sk}")
    if (sk != SECURITY_KEYPAD_ARMED_HOME) {
        if (delay > 0) {
            // If we're already arming home, don't dispatch any events. This happens when
            // HSM sends a duplicate event during delayed arming.
            if (al == ALARM_STATUS_ARMING_HOME) {
                logDebug("armHome | Already ${ALARM_STATUS_ARMING_HOME}, not dispatching any events.")
                return
            } else {
                logDebug("armHome | alarm: ${al} - Proceeding with arming home.")
                state.armingIn = delay
                // Change status to avoid looping.
                changeStatus(ALARM_STATUS_ARMING_HOME)
                logDebug("armHome | - armingIn: ${state.armingIn}")
                if (state.type == 'digital') {
                    logDebug("armHome | Digital arming triggered, sending armingIn event to HSM.")
                    // If this was a digital event, then we didn't trigger HSM during the processing
                    // of the EntryControlNotification. Trigger HSM to begin arming.
                    sendEvent(name:'armingIn', value: delay, data:[armMode: armingStates[INDICATOR_TYPE_ARMED_STAY].securityKeypadState, armCmd: armingStates[INDICATOR_TYPE_ARMED_STAY].hsmCmd], isStateChange:true)
                }
                exitDelay(delay)
                runIn(delay, armHomeEnd)
            }
        } else {
            // No delay, so immediately proceed to armHomeEnd()
            armHomeEnd()
        }
    } else {
        logDebug("armHome | securityKeypad already set to ${SECURITY_KEYPAD_ARMED_HOME}, skipping.")
    }
}

void armHomeEnd() {
    if (!state.code) {
        state.code = ''
    }
    if (!state.type) {
        state.type = 'physical'
    }
    def sk = device.currentValue('securityKeypad')
    logDebug("armHomeEnd | sk: ${sk} code: ${state.code} type: ${state.type} delay: ${delay}")
    if (sk != SECURITY_KEYPAD_ARMED_HOME) {
        logDebug("armHomeEnd | Finishing arming.")
        keypadUpdateStatus(INDICATOR_TYPE_ARMED_STAY, state.type, state.code)
        alarmStatusChangeNow()
        changeStatus('set')
        state.armingIn = 0
        if (state.type == 'digital') {
            // Send the HSM event indicating immediate arming.
            // TODO: Do we need this? It seems redundant, maybe it wouldn't have sent in armAway if digital and no delay?
            sendEvent(name:'armingIn', value: state.keypadConfig.armHomeDelay, data:[armMode: armingStates[INDICATOR_TYPE_ARMED_STAY].securityKeypadState, armCmd: armingStates[INDICATOR_TYPE_ARMED_STAY].hsmCmd], isStateChange:true)
        }
    }
}

void disarm(delay=0) {
    def sk = device.currentValue('securityKeypad')
    logDebug("disarm | delay: ${delay}")
    logDebug("disarm | sk: ${sk}")
    if (sk != SECURITY_KEYPAD_DISARMED) {
        if (!state.code) {
            state.code = ''
        }
        if (!state.type) {
            state.type = 'physical'
        }

        logDebug("disarm | disarming HSM and clearing keypad state")
        sendLocationEvent(name: 'hsmSetArm', value: 'disarm') // Disarm HSM
        keypadUpdateStatus(INDICATOR_TYPE_DISARMED, state.type, state.code)  // Sends status to Keypad
        alarmStatusChangeNow() // Record alarm state change.

        // Clear state, unschedule anything that may have been scheduled due to an arming delay
        // in case this is a canceled arming.
        changeStatus('off')
        state.armingIn = 0
        unschedule(armHomeEnd)
        unschedule(armAwayEnd)
        unschedule(changeStatus)
    } else {
        logDebug("disarm | securityKeypad already set to ${SECURITY_KEYPAD_DISARMED} skipping.")
    }
}

void exitDelay(delay) {
    logDebug("exitDelay | delay: ${delay}")
    if (delay) {
        sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:INDICATOR_TYPE_EXIT_DELAY, propertyId:7, value:delay.toInteger()]]).format())
        // update state so that a disarm command during the exit delay resets the indicator lights
        state.keypadStatus = INDICATOR_TYPE_EXIT_DELAY
        type = state.code != '' ? 'physical' : 'digital'
        eventProcess(name: 'securityKeypad', value: SECURITY_KEYPAD_EXIT_DELAY, type: type, data: state.code)
        logDebug("exitDelay | type: ${type}")
    }
}

private void changeStatus(status) {
    logDebug("changeStatus | alarm: ${status}")
    sendEvent(name: 'alarm', value: status, isStateChange: true)
}

// Used by HSM to trigger an entry event.
void entry() {
    int intDelay = state.keypadConfig.entryDelay ? state.keypadConfig.entryDelay.toInteger() : 0
    if (intDelay) {
        entry(intDelay)
    }
}

void entry(entranceDelay) {
    logDebug("In entry - delay: ${entranceDelay}")
    if (entranceDelay) {
        sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:INDICATOR_TYPE_ENTRY_DELAY, propertyId:7, value:entranceDelay.toInteger()]]).format())
    }
}

// -- Chime Capability Commands

void playSound(soundnumber) {
    logDebug("playSound | sound: ${soundnumber}")
    volSiren()

    if (SOUND_EFFECTS_TO_INDICATOR_ID[soundnumber.intValue()]) {
        // Chime uses the siren volume. Maybe should use the announcement volume?
        int playVolume = device.currentValue('volSiren') * 10
        logDebug("playSound | ${soundnumber} at volume ${playVolume}")
        sendSoundCommand(SOUND_EFFECTS_TO_INDICATOR_ID[soundnumber.intValue()], playVolume)
    } else {
        log.warn "playSound | sound ${soundnumnber} unsupported."
    }
    
}

void stop() {
    off()
}

// -- Alarm Capability Commands --

void off() {
    changeStatus('off')
    sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:state.keypadStatus, propertyId:2, value:0xFF]]).format())
}

void both() {
    siren()
}

void siren() {
    changeStatus('siren')
    sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:INDICATOR_TYPE_ALARM, propertyId:2, value:0xFF]]).format())
}

void strobe() {
    // The keypad doesn't support strobing without siren. So we'll just use siren() here.
    siren()
}

// -- Button Handling - partial code entry ends up as a security event, we send the button presses here. --

void handleButtons(String code) {
    List<String> buttons = code.split('')
    for (String btn : buttons) {
        try {
            int val = Integer.parseInt(btn)
            sendEvent(name: 'pushed', value: val, isStateChange: true)
        } catch (NumberFormatException e) {
            // Handle button holds here
            char ch = btn
            char a = 'A'
            int pos = ch - a + 1
            sendEvent(name: 'held', value: pos, isStateChange: true)
        }
    }
}

void push(btn) {
    state.type = 'digital'
    sendEvent(name: 'pushed', value: btn, isStateChange: true)
}

void hold(btn) {
    state.type = 'digital'
    sendEvent(name: 'held', value: btn, isStateChange: true)
}

void getCodes() {
    logDebug('getCodes |')
    updateEncryption()
}

private updateEncryption() {
    String lockCodes = device.currentValue('lockCodes') // encrypted or decrypted
    if (lockCodes) {
        if (optEncrypt && lockCodes[0] == '{') {    // resend encrypted
            sendEvent(name:'lockCodes', value: encrypt(lockCodes), isStateChange: true)
        } else if (!optEncrypt && lockCodes[0] != '{') {    // resend decrypted
            sendEvent(name:'lockCodes', value: decrypt(lockCodes), isStateChange: true)
        } else {
            sendEvent(name:'lockCodes', value: lockCodes, isStateChange: true)
        }
    }
}

private Boolean validatePin(String pincode) {
    boolean validCode = false
    Map lockcodes = [:]

    String configCodes = optEncrypt ? decrypt(device.currentValue('lockCodes')) : device.currentValue('lockCodes')
    try {
        lockcodes = parseJson(configCodes)
    } catch (e) {
        log.warn 'validatePin | No lock codes found.'
    }

    if (lockcodes) {
        lockcodes.each {
            if (it.value['code'] == pincode) {
                Date now = new Date()
                
                sendEvent(name:'validCode', value: 'true', isStateChange: true)
                sendEvent(name:'lastCodeName', value: "${it.value['name']}", isStateChange: true)
                sendEvent(name:'lastCodeTime', value: "${now}", isStateChange: true)
                sendEvent(name:'lastCodeEpochms', value: "${now.getTime()}", isStateChange: true)
                
                validCode = true
                String code = JsonOutput.toJson(["${it.key}":['name': "${it.value.name}", 'code': "${it.value.code}", 'isInitiator': true]])
                state.code = optEncrypt ? encrypt(code) : code
            }
        }
    }

    if (!validCode) {
        sendEvent(name:'validCode', value: 'false', isStateChange: true)
    }

    return validCode
}

void setCode(codeposition, pincode, name) {
    logDebug("setCode | pos: ${codeposition}, code: ${pincode}, name: ${name})")
    boolean newCode = true
    Map lockcodes = [:]
    if (device.currentValue('lockCodes') != null) {
        if (optEncrypt) {
            lockcodes = parseJson(decrypt(device.currentValue('lockCodes')))
        } else {
            lockcodes = parseJson(device.currentValue('lockCodes'))
        }
    }
    if (lockcodes["${codeposition}"]) {
        newCode = false
    }
    lockcodes["${codeposition}"] = ['code': "${pincode}", 'name': "${name}"]
    if (optEncrypt) {
        sendEvent(name: 'lockCodes', value: encrypt(JsonOutput.toJson(lockcodes)))
    } else {
        sendEvent(name: 'lockCodes', value: JsonOutput.toJson(lockcodes), isStateChange: true)
    }
    if (newCode) {
        sendEvent(name: 'codeChanged', value:'added')
    } else {
        sendEvent(name: 'codeChanged', value: 'changed')
    }
}

void deleteCode(codeposition) {
    logDebug("deleteCode | code : ${codeposition}")
    Map lockcodes = [:]
    if (device.currentValue('lockCodes') != null) {
        if (optEncrypt) {
            lockcodes = parseJson(decrypt(device.currentValue('lockCodes')))
        } else {
            lockcodes = parseJson(device.currentValue('lockCodes'))
        }
    }
    lockcodes["${codeposition}"] = [:]
    lockcodes.remove("${codeposition}")
    if (optEncrypt) {
        sendEvent(name: 'lockCodes', value: encrypt(JsonOutput.toJson(lockcodes)))
    } else {
        sendEvent(name: 'lockCodes', value: JsonOutput.toJson(lockcodes), isStateChange: true)
    }
    sendEvent(name: 'codeChanged', value: 'deleted')
}

List<String> runConfigs() {
    List<String> cmds = []
    configParams.each { param, data ->
        if (settings[data.input.name]) {
            logDebug("runConfigs | Set parameter: ${param} to ${settings[data.input.name]}")
            cmds.addAll(configCmd(param, data.parameterSize, settings[data.input.name]))
        }
    }
    return cmds
}

List<String> pollConfigs() {
    logDebug("pollConfigs |")
    List<String> cmds = []
    configParams.each { param, data ->
        cmds.add(zwave.configurationV1.configurationGet(parameterNumber: param.toInteger()).format())
    }
    sendToDevice(cmds)
}

List<String> configCmd(parameterNumber, size, scaledConfigurationValue) {
    List<String> cmds = []
    cmds.add(zwave.configurationV1.configurationSet(parameterNumber: parameterNumber.toInteger(), size: size.toInteger(), scaledConfigurationValue: scaledConfigurationValue.toInteger()).format())
    cmds.add(zwave.configurationV1.configurationGet(parameterNumber: parameterNumber.toInteger()).format())
    return cmds
}

// Z-Wave Event Handling

void zwaveEvent(hubitat.zwave.commands.configurationv1.ConfigurationReport cmd) {
    logDebug("ConfigurationReport | ${cmd}")
    if (configParams[cmd.parameterNumber.toInteger()]) {
        Map configParam = configParams[cmd.parameterNumber.toInteger()]
        int scaledValue
        cmd.configurationValue.reverse().eachWithIndex { v, index ->
            scaledValue = scaledValue | v << (8 * index)
        }
        logDebug("ConfigurationReport: ${configParam.input.name} is [${scaledValue}]")
        device.updateSetting(configParam.input.name, [value: "${scaledValue}", type: configParam.input.type])
    }
}

@Field static Map BATTERY_STATUS_MAP = [
    (CHARGING_STATUS_CHARGING): 'charging',
    (CHARGING_STATUS_MAINTAINING): 'maintaining',
    (CHARGING_STATUS_DISCHARGING): 'discharging'
]

void zwaveEvent(hubitat.zwave.commands.batteryv2.BatteryReport cmd) {
    logDebug("BatteryReport | ${cmd}")

    Map levelEvt = [name: 'battery', unit: '%', isStateChange: true]
    if (cmd.batteryLevel == 0xFF) {
        levelEvt.descriptionText = "${device.displayName} has a low battery"
        levelEvt.value = 1
    } else {
        levelEvt.value = cmd.batteryLevel
        levelEvt.descriptionText = "${device.displayName} battery is ${levelEvt.value}${levelEvt.unit}"
    }
    eventProcess(levelEvt)

    Map chargingEvt = [name: 'batteryStatus', value: BATTERY_STATUS_MAP[cmd.chargingStatus], descriptionText: "${device.displayName} battery is ${BATTERY_STATUS_MAP[cmd.chargingStatus]}", isStateChange: true]
    eventProcess(chargingEvt)
}

void zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.DeviceSpecificReport cmd) {
    logDebug("DeviceSpecificReport | ${cmd}")
    logDebug("DeviceSpecificReport | DeviceIdType: ${cmd.deviceIdType}, DeviceIdFormat: ${cmd.deviceIdDataFormat}, Data: ${cmd.deviceIdData}")
    if (cmd.deviceIdType == 1) {
        String serialNumber = ''
        if (cmd.deviceIdDataFormat == 1) {
            cmd.deviceIdData.each { serialNumber += hubitat.helper.HexUtils.integerToHexString(it & 0xff, 1).padLeft(2, '0') }
        } else {
            cmd.deviceIdData.each { serialNumber += (char) it }
        }
        device.updateDataValue('serialNumber', serialNumber)
    }
}

void zwaveEvent(hubitat.zwave.commands.versionv3.VersionReport cmd) {
    Double firmware0Version = cmd.firmware0Version + (cmd.firmware0SubVersion / 100)
    Double protocolVersion = cmd.zWaveProtocolVersion + (cmd.zWaveProtocolSubVersion / 100)
    logDebug("VersionReport | ${cmd}")
    logDebug("VersionReport | FirmwareVersion: ${firmware0Version}, ProtocolVersion: ${protocolVersion}, HardwareVersion: ${cmd.hardwareVersion}")
    device.updateDataValue('firmwareVersion', "${firmware0Version}")
    device.updateDataValue('protocolVersion', "${protocolVersion}")
    device.updateDataValue('hardwareVersion', "${cmd.hardwareVersion}")
    if (cmd.firmwareTargets > 0) {
        cmd.targetVersions.each { target ->
            Double targetVersion = target.version + (target.subVersion / 100)
            device.updateDataValue("firmware${target.target}Version", "${targetVersion}")
        }
    }
}

void zwaveEvent(hubitat.zwave.commands.notificationv8.NotificationReport cmd) {
    logDebug("NotificationReport | ${cmd}")
    Map evt = [:]
    if (cmd.notificationType == NOTIFICATION_TYPE_POWER_MANAGEMENT) {
        switch (cmd.event) {
            case AC_MAINS_DISCONNECTED:
                evt.name = 'powerSource'
                evt.value = 'battery'
                evt.descriptionText = "${device.displayName} AC mains is disconnected"
                eventProcess(evt)
                break
            case AC_MAINS_RECONNECTED:
                evt.name = 'powerSource'
                evt.value = 'mains'
                evt.descriptionText = "${device.displayName} AC mains is re-connected"
                eventProcess(evt)
                break
            case BATTERY_CHARGING:
                logInfo("${device.displayName} Battery is charging")
                break
            case BATTERY_FULL:
                logInfo("${device.displayName} Battery is fully charged")
                break
        }
        // Request an updated battery report whenever we get a power management notification.
        // This ensures we can keep `batteryStatus` up to date.
        sendToDevice(zwave.batteryV2.batteryGet().format())
    }
    else if (cmd.notificationType == NOTIFICATION_TYPE_BURGLAR) {
        if (cmd.event == MOTION_DETECTION) {
            evt.name = 'motion'
            evt.value = 'active'
        } else if (cmd.event == STATE_IDLE && cmd.eventParameter[0] == MOTION_DETECTION) {
            // Motion has cleared.
            evt.name = 'motion'
            evt.value = 'inactive'
        } else if (cmd.event == 0 && cmd.notificationStatus == 255) {
            // According to the manual, this is a "Dropped Frame" notification.
            log.warn "NotificationReport | ${device.displayName} reports a dropped frame!."
        } else if (cmd.event == 0 && cmd.notificationStatus == 0) {
            log.info "NotificationReport | ${device.displayName} reports that dropped frames condition has cleared."
        } else {
            log.warn "NotificationReport | Unhandled (Security): ${cmd}"
        }
        if (evt.name) {
            evt.descriptionText = "${device.displayName} ${evt.name} is ${evt.value}"
            eventProcess(evt)
        }
    } else if (cmd.notificationType == NOTIFICATION_TYPE_SYSTEM && cmd.event == SYSTEM_SOFTWARE_FAILURE) {
        // There are different kinds of faults documented in the manual.
        log.warn "${device.displayName} reports a software fault: ${cmd.eventParameter[0]}: 0x${hubitat.helper.HexUtils.integerToHexString(cmd.eventParameter[0], 1)}."
    }
    else {
        logDebug("NotificationReport | Unhandled: ${cmd}")
    }
}

void zwaveEvent(hubitat.zwave.commands.indicatorv3.IndicatorReport cmd) {
    logDebug("IndicatorReport | ${cmd}")
}

void zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {
    logDebug("BasicReport | ${cmd}")
}

void zwaveEvent(hubitat.zwave.commands.entrycontrolv1.EntryControlNotification cmd) {
    logDebug("EntryControlNotification | ${cmd}")

    Map ecn = [:]
    ecn.sequenceNumber = cmd.sequenceNumber
    ecn.dataType = cmd.dataType
    ecn.eventType = cmd.eventType

    def currentStatus = device.currentValue('securityKeypad')
    def alarmStatus = device.currentValue('alarm')
    
    String code = (cmd.eventData.collect { (char) it }.join() as String)

    logDebug("EntryControlNotification | Code: ${code}")
    switch (ecn.eventType) {
        case EVENT_TYPE_ARM_AWAY:
            logDebug('EntryControlNotification | Away Mode Button')
            if (validatePin(code) || instantArming) {
                logDebug("EntryControlNotification | Code Passed - currentStatus: ${currentStatus}")
                // Currently disarmed, trigger HSM mode change via event.
                if (currentStatus == 'disarmed') {
                    state.type = 'physical'
                    state.keypadConfig.armAwayDelay = state.keypadConfig.armAwayDelay ? state.keypadConfig.armAwayDelay : 0
                    logDebug("EntryControlNotification | Issuing armingIn event with delay ${state.keypadConfig.armAwayDelay}")
                    // Indicate 'armingIn' event to HSM. HSM subscribes to this event and will call armAway() after the delay.
                    // If arming fails (bypass failure etc), HSM will not call armAway and we'll return to normal.
                    sendEvent(name:'armingIn', descriptionText: "Arming AWAY mode in ${state.keypadConfig.armAwayDelay} delay", value: state.keypadConfig.armAwayDelay, data:[armMode: armingStates[INDICATOR_TYPE_ARMED_AWAY].securityKeypadState, armCmd: armingStates[INDICATOR_TYPE_ARMED_AWAY].hsmCmd], isStateChange:true)
                } else {
                    logDebug("EntryControlNotification | Failed - Please Disarm Alarm before changing alarm type - currentStatus: ${currentStatus}")
                }
            } else {
                logDebug("EntryControlNotification | Failed - Invalid PIN - currentStatus: ${currentStatus}")
                notifyInvalidCode()
            }
            break
        case EVENT_TYPE_ARM_HOME:
            logDebug('EntryControlNotification | Home Mode Button')
            if (validatePin(code) || instantArming) {
                logDebug("EntryControlNotification | Code Passed - currentStatus: ${currentStatus}")
                if (currentStatus == 'disarmed') {
                    state.type = 'physical'
                    state.keypadConfig.partialFunction = state.keypadConfig.partialFunctiob ?: 'armHome'
                    if (state.keypadConfig.partialFunction == 'armHome') {
                        logDebug("EntryControlNotification | Arming HOME mode, configured partialFunction: ${state.keypadConfig.partialFunction}")
                        logDebug("EntryControlNotification | Issuing armingIn event with delay ${state.keypadConfig.armHomeDelay}")
                        state.keypadConfig.armHomeDelay = state.keypadConfig.armHomeDelay ? state.keypadConfig.armHomeDelay : 0
                        // Indicate 'armingIn' event to HSM. HSM subscribes to this event and will call armHome() after the delay.
                        // If arming fails (bypass failure etc), HSM will not call armAway and we'll return to normal.
                        sendEvent(name:'armingIn', descriptionText: "Arming HOME mode in ${state.keypadConfig.armHomeDelay} delay", value: state.keypadConfig.armHomeDelay, data:[armMode: armingStates[INDICATOR_TYPE_ARMED_STAY].securityKeypadState, armCmd: armingStates[INDICATOR_TYPE_ARMED_STAY].hsmCmd], isStateChange:true)
                    }
                    // TODO: Fix armNight functionality.
                } else {
                    logDebug("EntryControlNotification | Failed - Please Disarm Alarm before changing alarm type - currentStatus: ${currentStatus}")
                }
            } else {
                logDebug("EntryControlNotification | Failed - Invalid PIN - currentStatus: ${currentStatus}")
                notifyInvalidCode()
            }
            break
        case EVENT_TYPE_DISARM_ALL:
            logDebug('EntryControlNotification | Disarm Mode Button')
            if (validatePin(code)) {
                logDebug('EntryControlNotification | Code Passed')
                logDebug("EntryControlNotification | Issuing armingIn event with delay ${0}")
                state.type = 'physical'
                // Indicate 'armingIn' event to HSM. HSM subscribes to this event and will call disarm().
                sendEvent(name:'armingIn', value: 0, descriptionText: 'Disarming after valid code entry', data:[armMode: armingStates[INDICATOR_TYPE_DISARMED].securityKeypadState, armCmd: armingStates[INDICATOR_TYPE_DISARMED].hsmCmd], isStateChange:true)
            } else {
                logDebug("EntryControlNotification | Failed - Invalid PIN - currentStatus: ${currentStatus}")
                notifyInvalidCode()
            }
            break
        // Code sent after hitting the Check Mark
        case EVENT_TYPE_ENTER:
            logDebug('EntryControlNotification | Generic Code Enter (Check Mark)')
            state.type = 'physical'
            Date now = new Date()
            long ems = now.getTime()
            if (!code) {
                code = 'check mark'
            }
            if (validateCheck) {
                if (validatePin(code)) {
                    logDebug('EntryControlNotification | Generic Code Enter - Code Passed')
                } else {
                    logDebug("EntryControlNotification | Generic Code Enter - Code Failed - Invalid PIN - currentStatus: ${currentStatus}")
                    notifyInvalidCode()
                }
            } else {
                // Just emit the code as the last entered, but no validity specified.
                sendEvent(name:'lastCodeName', value: "${code}", isStateChange:true)
                sendEvent(name:'lastCodeTime', value: "${now}", isStateChange:true)
                sendEvent(name:'lastCodeEpochms', value: "${ems}", isStateChange:true)
            }
            break
        case EVENT_TYPE_POLICE:
            logDebug('EntryControlNotification | Police Button')
            state.type = 'physical'
            Date now = new Date()
            sendEvent(name:'lastCodeName', value: 'police', isStateChange:true)
            sendEvent(name:'lastCodeTime', value: "${now}", isStateChange:true)
            sendEvent(name:'lastCodeEpochms', value: "${now.getTime()}", isStateChange:true)
            sendEvent(name: 'held', value: 11, isStateChange: true)
            break
        case EVENT_TYPE_FIRE:
            logDebug('EntryControlNotification | Fire Button')
            state.type = 'physical'
            Date now = new Date()
            sendEvent(name:'lastCodeName', value: 'fire', isStateChange:true)
            sendEvent(name:'lastCodeTime', value: "${now}", isStateChange:true)
            sendEvent(name:'lastCodeEpochms', value: "${now.getTime()}", isStateChange:true)
            sendEvent(name: 'held', value: 12, isStateChange: true)
            break
        case EVENT_TYPE_ALERT_MEDICAL:
            logDebug('EntryControlNotification | Medical Button')
            state.type = 'physical'
            Date now = new Date()
            sendEvent(name:'lastCodeName', value: 'medical', isStateChange:true)
            sendEvent(name:'lastCodeTime', value: "${now}", isStateChange:true)
            sendEvent(name:'lastCodeEpochms', value: "${now.getTime()}", isStateChange:true)
            sendEvent(name: 'held', value: 13, isStateChange: true)
            break
        // Button pressed or held, idle timeout reached without explicit submission
        case EVENT_TYPE_CACHED_KEYS:
            logDebug('EntryControlNotification | Cached Keys')
            state.type = 'physical'
            handleButtons(code)
            break
        case EVENT_TYPE_CACHING:
            logDebug('EntryControlNotification | Caching - Ignoring event.')
            break
    }
}

void zwaveEvent(hubitat.zwave.commands.associationv2.AssociationReport cmd) {
    List<String> temp = []
    if (cmd.nodeId != []) {
        cmd.nodeId.each {
            temp.add(it.toString().format( '%02x', it.toInteger() ).toUpperCase())
        }
    }
    logDebug("AssociationReport | ${cmd}")
    logDebug("AssociationReport | Group: ${cmd.groupingIdentifier}, Nodes: $temp")
}

void zwaveEvent(hubitat.zwave.Command cmd) {
    logDebug("Command | Unhandled Command: ${cmd}")
}

void zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
    logDebug("SecurityMessageEncapsulation | ${cmd}")
    hubitat.zwave.Command encapsulatedCommand = cmd.encapsulatedCommand(CMD_CLASS_VERS)
    if (encapsulatedCommand) {
        logDebug("SecurityMessageEncapsulation | Processing encapsulated: ${encapsulatedCommand}")
        zwaveEvent(encapsulatedCommand)
    }
}

void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd) {
    logDebug("SupervisionGet | ${cmd}")

    hubitat.zwave.Command encapsulatedCommand = cmd.encapsulatedCommand(CMD_CLASS_VERS)
    if (encapsulatedCommand) {
        logDebug("SupervisionGet | Processing encapsulated: ${encapsulatedCommand}")
        zwaveEvent(encapsulatedCommand)
    }

    logDebug("SupervisionGet | Sending SupervisionReport for sessionID: ${cmd.sessionID}")
    sendToDevice(zwave.supervisionV1.supervisionReport(sessionID: cmd.sessionID, reserved: 0, moreStatusUpdates: false, status: SUPERVISION_SUCCESS, duration: 0).format())
}

void parse(String event) {
    logDebug("parse | ${event}")
    hubitat.zwave.Command cmd = zwave.parse(event, CMD_CLASS_VERS)
    if (cmd) {
        zwaveEvent(cmd)
    }
}

def volAnnouncement(newVol=null) {
    logDebug("volAnnouncement | newVol: ${newVol}")
    if (newVol) {
        def currentVol = device.currentValue('volAnnouncement')
        if (newVol.toString() == currentVol.toString()) {
            logDebug("volAnnouncement | Announcement Volume hasn't changed, so skipping")
        } else {
            logDebug("volAnnouncement | Setting the Announcement Volume to $newVol")
            nVol = newVol.toInteger()
            sendToDevice(new hubitat.zwave.commands.configurationv1.ConfigurationSet(parameterNumber: 4, size: 1, scaledConfigurationValue: nVol).format())
            sendEvent(name:'volAnnouncement', value: newVol, isStateChange:true)
        }
    } else {
        logDebug('volAnnouncement | Announcement value not specified, so skipping')
    }
}

def volKeytone(newVol=null) {
    logDebug("volKeytone | newVol: ${newVol}")
    if (newVol) {
        def currentVol = device.currentValue('volKeytone')
        if (newVol.toString() == currentVol.toString()) {
            logDebug("volKeytone | Keytone Volume hasn't changed, so skipping")
        } else {
            logDebug("volKeytone | Setting the Keytone Volume to $newVol")
            nVol = newVol.toInteger()
            sendToDevice(new hubitat.zwave.commands.configurationv1.ConfigurationSet(parameterNumber: 5, size: 1, scaledConfigurationValue: nVol).format())
            sendEvent(name:'volKeytone', value: newVol, isStateChange:true)
        }
    } else {
        logDebug('volKeytone | Keytone value not specified, so skipping')
    }
}

def volSiren(newVol=null) {
    logDebug("volKeytone | newVol: ${newVol}")
    if (newVol) {
        def currentVol = device.currentValue('volSiren')
        if (newVol.toString() == currentVol.toString()) {
            logDebug("volKeytone | Siren Volume hasn't changed, so skipping")
            def sVol = currentVol.toInteger() * 10
        } else {
            logDebug("volKeytone | Setting the Siren Volume to $newVol")
            sVol = newVol.toInteger() * 10
            sendToDevice(new hubitat.zwave.commands.configurationv1.ConfigurationSet(parameterNumber: 6, size: 1, scaledConfigurationValue: sVol).format())
            sendEvent(name:'volSiren', value: newVol, isStateChange:true)
        }
    } else {
        def currentVol = device.currentValue('volSiren')
        if (currentVol) {
            sVol = currentVol.toInteger() * 10
        } else {
            sVol = 90
        }
    }
    return sVol
}

def playTone(tone=null) {
    volSiren()
    logDebug("playTone | tone: ${tone}, volume: ${sVol}")
    if (!tone) {
        tone = theTone
        logDebug("playTone | No tone specified, using theTone setting: ${tone}")
    }
    if (tone == 'Tone_1') { // Siren
        changeStatus('active')
        sendSoundCommand(INDICATOR_TYPE_ALARM, sVol)
    } else if (tone == 'Tone_2') { // Smoke
        changeStatus('active')
        sendSoundCommand(INDICATOR_TYPE_ALARM_SMOKE, sVol)
    } else if (tone == 'Tone_3') { // CO
        changeStatus('active')
        sendSoundCommand(INDICATOR_TYPE_ALARM_CO, sVol)
    } else if (tone == 'Tone_4') { // Navi
        sendSoundCommand(0x60, sVol)
    } else if (tone == 'Tone_5') { // Guitar
        sendSoundCommand(0x61, sVol)
    } else if (tone == 'Tone_6') { // Windchimes
        sendSoundCommand(0x62, sVol)
    } else if (tone == 'Tone_7') { // Doorbell 1
        sendSoundCommand(0x63, sVol)
    } else if (tone == 'Tone_8') { // Doorbell 2
        sendSoundCommand(0x64, sVol)
    } else if (tone == 'Tone_9') { // Invalid Code Sound
        sendSoundCommand(INDICATOR_TYPE_CODE_REJECTED, sVol)
    }
}

// Common Events

private void sendSoundCommand(soundIndicatorId, volume) {
    logDebug("sendSoundCommand | soundIndicator : ${soundIndicatorId}, volume: ${volume})")
    sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:soundIndicatorId, propertyId:INDICATOR_ID_TO_PROPERTY_ID[soundIndicatorId], value:volume]]).format())
}

private void notifyInvalidCode() {
    logDebug("notifyInvalidCode | raising invalid code indicator")
    sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:INDICATOR_TYPE_CODE_REJECTED, propertyId:2, value:0xFF]]).format())
}

private void alarmStatusChangeNow() {
    Date now = new Date()
    sendEvent(name:'alarmStatusChangeTime', value: "${now}", isStateChange:true)
    sendEvent(name:'alarmStatusChangeEpochms', value: "${now.getTime()}", isStateChange:true)
}

// Helpers to send commands to the devicde.

private void sendToDevice(List<String> cmds, Long delay=300) {
    sendHubCommand(new hubitat.device.HubMultiAction(commands(cmds, delay), hubitat.device.Protocol.ZWAVE))
}

private void sendToDevice(String cmd) {
    sendHubCommand(new hubitat.device.HubAction(zwaveSecureEncap(cmd), hubitat.device.Protocol.ZWAVE))
}

private List<String> commands(List<String> cmds, Long delay=300) {
    return delayBetween(cmds.collect { zwaveSecureEncap(it) }, delay)
}

// General Utility Methods

// Generate association group commands for Group 1 to associate to the hub.
private List<String> setDefaultAssociation() {
    List<String> cmds = []
    cmds.add(zwave.associationV2.associationSet(groupingIdentifier: 1, nodeId: zwaveHubNodeId).format())
    cmds.add(zwave.associationV2.associationGet(groupingIdentifier: 1).format())
    return cmds
}

// Event filter - only emit an event if it represents a change from the current state.
private void eventProcess(Map evt) {
    if (evt.descriptionText) {
        logInfo("${evt.descriptionText}")
    }
    if (device.currentValue(evt.name).toString() != evt.value.toString()) {
        sendEvent(evt)
    }
}

private void logInfo(msg) {
  if (txtEnable) { log.info msg }
}

private void logDebug(msg) {
  if (logEnable) { log.debug msg }
}