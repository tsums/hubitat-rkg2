/*
    Ring Keypad Gen 2 - Community Driver

    Copyright 2020 -> 2021 Hubitat Inc.  All Rights Reserved
    Special Thanks to Bryan Copeland (@bcopeland) for writing and releasing this code to the community!

    1.4.0 - 10/28/25 - Fix armNight partial function handling, format code, improve comments and debug logging,
                       improve functin naming and variable names. - @tsums
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

import groovy.transform.Field
import groovy.json.JsonOutput

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
        command 'resetKeypad'
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
        attribute 'lastCodeName', 'STRING'
        attribute 'lastCodeTime', 'STRING'
        attribute 'lastCodeEpochms', 'NUMBER'
        attribute 'motion', 'STRING'
        attribute 'validCode', 'ENUM', ['true', 'false']
        attribute 'volAnnouncement', 'NUMBER'
        attribute 'volKeytone', 'NUMBER'
        attribute 'volSiren', 'NUMBER'

        fingerprint mfr:'0346', prod:'0101', deviceId:'0301', inClusters:'0x5E,0x98,0x9F,0x6C,0x55', deviceJoinName: 'Ring Alarm Keypad G2'
    }
    preferences {
        input name: 'about', type: 'paragraph', element: 'paragraph', title: 'Ring Alarm Keypad G2 HSM Driver', description: "${version()}<br>Note:<br>The first 3 Tones are alarm sounds that also flash the Red Indicator Bar on the keypads. The rest are more pleasant sounds that could be used for a variety of things."
        configParams.each { input it.value.input }
        input name: 'theTone', type: 'enum', title: 'Chime tone', options: [
            ['Tone_1':'(Tone_1) Siren (default)'],
            ['Tone_2':'(Tone_2) 3 Beeps'],
            ['Tone_3':'(Tone_3) 4 Beeps'],
            ['Tone_4':'(Tone_4) Navi'],
            ['Tone_5':'(Tone_5) Guitar'],
            ['Tone_6':'(Tone_6) Windchimes'],
            ['Tone_7':'(Tone_7) DoorBell 1'],
            ['Tone_8':'(Tone_8) DoorBell 2'],
            ['Tone_9':'(Tone_9) Invalid Code Sound'],
        ], defaultValue: 'Tone_1', description: 'Default tone for playback.'
        input name: 'instantArming', type: 'bool', title: 'Enable set alarm without code', defaultValue: false, description: ''
        input name: 'validateCheck', type: 'bool', title: 'Validate codes submitted with checkmark', defaultValue: false, description: ''
        input name: 'proximitySensor', type: 'bool', title: 'Disable the Proximity Sensor', defaultValue: false, description: ''
        input name: 'optEncrypt', type: 'bool', title: 'Enable lockCode encryption', defaultValue: false, description: ''
        input name: 'logEnable', type: 'bool', title: 'Enable debug logging', defaultValue: true
        input name: 'txtEnable', type: 'bool', title: 'Enable descriptionText logging', defaultValue: true
    }
}

@Field static Map configParams = [
        1: [input: [name: 'configParam1', type: 'number', title: 'Heartbeat Interval', description:'Number of minutes in between battery reports.', defaultValue: 70, range:'1..70'], parameterSize:1],
        7: [input: [name: 'configParam7', type: 'number', title: 'Long press Emergency Duration', description:'', defaultValue: 3, range:'2..5'], parameterSize:1],
        8: [input: [name: 'configParam8', type: 'number', title: 'Long press Number pad Duration', description:'', defaultValue: 3, range:'2..5'], parameterSize:1],
        10: [input: [name: 'configParam10', type: 'number', title: 'Button Press Display Timeout', description:'Timeout in seconds when any button is pressed', defaultValue: 5, range:'0..30'], parameterSize:1],
        11: [input: [name: 'configParam11', type: 'number', title: 'Status Change Display Timeout', description:'Timeout in seconds when indicator command is received from the hub tochange status', defaultValue: 5, range:'0..30'], parameterSize:1],
        12: [input: [name: 'configParam12', type: 'number', title: 'Security Mode Brightness', description:'', defaultValue: 100, range:'0..100'], parameterSize:1],
        13: [input: [name: 'configParam13', type: 'number', title: 'Key Backlight Brightness', description:'', defaultValue: 100, range:'0..100'], parameterSize:1],
        22: [input: [name: 'configParam22', type: 'number', title: 'System Security Mode Display', description:'601 = Always On, 1 - 600 = periodic interval, 0 = Always Off, except activity', defaultValue: 0, range:'0..601'], parameterSize:2],
]
@Field static Map armingStates = [
        0x02: [securityKeypadState: 'disarmed', hsmCmd: 'disarm'],
        0x0A: [securityKeypadState: 'armed home', hsmCmd: 'armHome'],
        0x0B: [securityKeypadState: 'armed away', hsmCmd: 'armAway'],
        0x00: [securityKeypadState: 'armed night', hsmCmd: 'armNight'],
]
@Field static Map CMD_CLASS_VERS = [0x70:1, 0x20:1, 0x86:3, 0x6F:1]

void logsOff() {
    log.warn 'debug logging disabled...'
    device.updateSetting('logEnable', [value:'false', type:'bool'])
}

void updated() {
    log.info 'updated...'
    log.warn "debug logging is: ${logEnable == true}"
    log.warn "description logging is: ${txtEnable == true}"
    log.warn "encryption is: ${optEncrypt == true}"
    unschedule()
    if (logEnable) {
        runIn(1800, logsOff)
    }
    sendToDevice(runConfigs())
    updateEncryption()
    proximitySensorHandler()
    volAnnouncement()
    volKeytone()
    volSiren()
}

void installed() {
    initializeVars()
}

void uninstalled() {
}

void initializeVars() {
    // first run only
    sendEvent(name:'codeLength', value: 4)
    sendEvent(name:'maxCodes', value: 100)
    sendEvent(name:'lockCodes', value: '')
    sendEvent(name:'armHomeDelay', value: 5)
    sendEvent(name:'armAwayDelay', value: 5)
    sendEvent(name:'armNightDelay', value: 5)
    sendEvent(name:'volAnnouncement', value: 8)
    sendEvent(name:'volKeytone', value: 8)
    sendEvent(name:'volSiren', value: 8)
    sendEvent(name:'securityKeypad', value:'disarmed')
    state.keypadConfig = [entryDelay:5, exitDelay: 5, armNightDelay:5, armAwayDelay:5, armHomeDelay: 5, codeLength: 4, partialFunction: 'armHome']
    state.keypadStatus = 2
    state.initialized = true
}

void resetKeypad() {
    if (logEnable) {
        log.debug 'resetKeypad()'
    }
    state.initialized = false
    configure()
    getCodes()
}

void configure() {
    if (logEnable) {
        log.debug 'configure()'
    }
    if (!state.initialized || !state.keypadConfig) {
        initializeVars()
    }
    keypadUpdateStatus(state.keypadStatus, state.type, state.code)
    runIn(5, pollDeviceData)
}

void refresh() {
    if (logEnable) {
        log.debug 'refresh()'
    }
    pollDeviceData()
    sendToDevice(pollConfigs())
}

void pollDeviceData() {
    List<String> cmds = []
    cmds.add(zwave.versionV3.versionGet().format())
    cmds.add(zwave.manufacturerSpecificV2.deviceSpecificGet(deviceIdType: 1).format())
    cmds.add(zwave.batteryV1.batteryGet().format())
    cmds.add(zwave.notificationV8.notificationGet(notificationType: 8, event: 0).format())
    cmds.add(zwave.notificationV8.notificationGet(notificationType: 7, event: 0).format())
    cmds.addAll(processAssociations())
    sendToDevice(cmds)
}

void keypadUpdateStatus(Integer status, String type='digital', String code) {
    if (logEnable) {
        log.debug "In keypadUpdateStatus (${version()}) - status: ${status} type: ${type} code: ${code}"
    }
    sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount: 1, value: 0, indicatorValues: [[indicatorId:status, propertyId:2, value:0xFF]]).format())
    state.keypadStatus = status
    if (state.code != '') {
        type = 'physical'
    }
    eventProcess(name: 'securityKeypad', value: armingStates[status].securityKeypadState, type: type, data: state.code)
    state.code = ''
    state.type = 'digital'
}

void armNight(delay=state.keypadConfig.armNightDelay) {
    if (logEnable) {
        log.debug "In armNight (${version()}) - delay: ${delay}"
    }
    def sk = device.currentValue('securityKeypad')
    if (sk != 'armed night') {
        if (delay > 0 ) {
            exitDelay(delay)
            runIn(delay, armNightEnd)
        } else {
            runIn(delay, armNightEnd)
        }
    } else {
        if (logEnable) {
            log.debug "In armNight - securityKeypad already set to 'armed night', so skipping."
        }
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
        Date now = new Date()
        sendLocationEvent(name: 'hsmSetArm', value: 'armNight')
        sendEvent(name:'alarmStatusChangeTime', value: "${now}", isStateChange:true)
        long ems = now.getTime()
        sendEvent(name:'alarmStatusChangeEpochms', value: "${ems}", isStateChange:true)
    }
}

void armAway(delay=state.keypadConfig.armAwayDelay) {
    if (logEnable) {
        log.debug "In armAway (${version()}) - delay: ${delay}"
    }
    def sk = device.currentValue('securityKeypad')
    // Allow compareable variable for added conditions.
    def al = device.currentValue('alarm')
    if (logEnable) {
        log.debug "In armAway (${version()}) - sk: ${sk}"
    }
    if (sk != 'armed away') {
        if (delay > 0 ) {
            state.armingIn = state.keypadConfig.armAwayDelay
            // Added conditions for digital control distinction
            if (state.type == 'digital' && al != 'armingAway') {
                if (logEnable) {
                    log.debug "In armAway (${version()}) alarm: ${al} - Validation for first digital submit"
                }
                // Event HSM Subscribes to
                sendEvent(name:'armingIn', value: state.keypadConfig.armAwayDelay, data:[armMode: armingStates[0x0B].securityKeypadState, armCmd: armingStates[0x0B].hsmCmd], isStateChange:true)
                // Set variable to for second digital submit
                changeStatus('armingAway')
                if (logEnable) log.debug "In armAway (${version()}) - armingIn: ${state.armingIn}"
                exitDelay(delay)
                runIn(delay, armAwayEnd)
            }
            // Added conditions for digital control distinction
            if (state.type == 'physical' && al != 'armingAway') {
                if (logEnable) {
                    log.debug "In armAway (${version()}) alarm: ${al} - Validation for first physical submit"
                }
                // Set variable to for second digital submit
                changeStatus('armingAway')
                if (logEnable) {
                    log.debug "In armAway (${version()}) - armingIn: ${state.armingIn}"
                }
                exitDelay(delay)
                runIn(delay, armAwayEnd)
            } else if (al == 'armingAway') {
                if (logEnable) {
                    log.debug "In armAway (${version()}) - Second Submission from HSM, not sending HSM update"
                }
                // Populate variable to be able to distinquish slarm state thta isn't final
                if (logEnable) {
                    log.debug "In armAway (${version()}) - No mathing condition. Nothing to execute"
                }
            } else {
                armAwayEnd()
            }
        } else {
            if (logEnable) {
                log.debug "In armAway - securityKeypad already set to 'armed away', so skipping."
            }
        }
    } else {
        if (logEnable) {
            log.debug "In armAway (${version()}) - delay: ${delay}"
        }
        if (sk != 'armed away') {
            if (delay > 0 ) {
                exitDelay(delay)
                runIn(delay, armAwayEnd)
            } else {
                armAwayEnd()
            }
        } else {
            if (logEnable) {
                log.debug "In armAway - securityKeypad already set to 'armed away', so skipping."
            }
        }
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
    if (logEnable) {
        log.debug "In armAwayEnd (${version()}) - sk: ${sk} code: ${state.code} type: ${state.type} delay: ${delay}"
    }
    // added conditional handeling for Exit Delay status. for sepreate handeling of delayed exit.
    if (sk == 'exit delay') {
        if (logEnable) {
            log.debug "In armAwayEnd (${version()}) sk: ${sk} Executing after delayed arming"
        }
        Date now = new Date()
        keypadUpdateStatus(0x0B, state.type, state.code)
        sendEvent(name:'alarmStatusChangeTime', value: "${now}", isStateChange:true)
        long ems = now.getTime()
        sendEvent(name:'alarmStatusChangeEpochms', value: "${ems}", isStateChange:true)
        changeStatus('set')
        state.armingIn = 0
    } else if (sk != 'armed away') {
        if (logEnable) {
            log.debug "In armAwayEnd (${version()}) sk: ${sk} Executing immediate arming"
        }
        Date now = new Date()
        keypadUpdateStatus(0x0B, state.type, state.code)
        if (state.type == 'digital') {
            // Event HSM Subscribes to
            sendEvent(name:'armingIn', value: state.keypadConfig.armAwayDelay, data:[armMode: armingStates[0x0B].securityKeypadState, armCmd: armingStates[0x0B].hsmCmd], isStateChange:true)
        }
        sendEvent(name:'alarmStatusChangeTime', value: "${now}", isStateChange:true)
        long ems = now.getTime()
        sendEvent(name:'alarmStatusChangeEpochms', value: "${ems}", isStateChange:true)
        changeStatus('set')
        state.armingIn = 0
    }
}

void armHome(delay = state.keypadConfig.armHomeDelay) {
    if (logEnable) {
        log.debug "In armHome (${version()}) - delay: ${delay}"
    }
    def sk = device.currentValue('securityKeypad')
    def al = device.currentValue('alarm')
    if (sk != 'armed home') {
        if (delay > 0) {
            state.armingIn = state.keypadConfig.armHomeDelay
            // Added conditions for digital control distinction
            if (state.type == 'digital' && al != 'armingHome') {
                if (logEnable) {
                    log.debug "In armHome (${version()}) alarm: ${al} - Validation for first digital submit"
                }
                // Event for HSM.
                sendEvent(name:'armingIn', value: state.keypadConfig.armHomeDelay, data:[armMode: armingStates[0x0A].securityKeypadState, armCmd: armingStates[0x0A].hsmCmd], isStateChange:true)
                // Set variable to for second digital submit
                changeStatus('armingHome')
                if (logEnable) {
                    log.debug "In armHome (${version()}) - armingIn: ${state.armingIn}"
                }
                exitDelay(delay)
                runIn(delay, armHomeEnd)
            }
            // Added conditions for digital control distinction
            if (state.type == 'physical' && al != 'armingHome') {
                if (logEnable) {
                    log.debug "In armHome (${version()}) alarm: ${al} - Validation for first physical submit"
                }
                // Set variable to for second digital submit
                changeStatus('armingHome')
                if (logEnable) {
                    log.debug "In armHome (${version()}) - armingIn: ${state.armingIn}"
                }
                exitDelay(delay)
                runIn(delay, armHomeEnd)
            } else if (al == 'armingHome') {
                if (logEnable) {
                    log.debug "In armHome (${version()}) - Second Submission from HSM, not sending HSM update"
                }
            }
            if (logEnable) {
                log.debug "In armHome (${version()}) - armingIn: ${state.armingIn}"
            }
        } else {
            armHomeEnd()
        }
    } else {
        if (logEnable) {
            log.debug "In armHome - securityKeypad already set to 'armed home', so skipping."
        }
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
    if (logEnable) {
        log.debug "In armHomeEnd (${version()}) - sk: ${sk} code: ${state.code} type: ${state.type} delay: ${delay}"
    }
    if (sk == 'exit delay') {
        if (logEnable) {
            log.debug "In armHomeEnd (${version()}) sk: ${sk} Executing after delayed arming"
        }
        Date now = new Date()
        keypadUpdateStatus(0x0A, state.type, state.code)
        sendEvent(name:'alarmStatusChangeTime', value: "${now}", isStateChange:true)
        long ems = now.getTime()
        sendEvent(name:'alarmStatusChangeEpochms', value: "${ems}", isStateChange:true)
        changeStatus('set')
        state.armingIn = 0
    }
    else if (sk != 'armed home') {
        if (logEnable) {
            log.debug "In armHomeEnd (${version()}) sk: ${sk} Executing immediate arming"
        }
        Date now = new Date()
        keypadUpdateStatus(0x0A, state.type, state.code)
        if (state.type == 'digital') {
            // Event for HSM.
            sendEvent(name:'armingIn', value: state.keypadConfig.armHomeDelay, data:[armMode: armingStates[0x0A].securityKeypadState, armCmd: armingStates[0x0A].hsmCmd], isStateChange:true)
        }
        sendEvent(name:'alarmStatusChangeTime', value: "${now}", isStateChange:true)
        long ems = now.getTime()
        sendEvent(name:'alarmStatusChangeEpochms', value: "${ems}", isStateChange:true)
        changeStatus('set')
        state.armingIn = 0
    }
}

void disarm(delay = 0) {
    if (logEnable) {
        log.debug "In disarm (${version()}) - delay: ${delay}"
    }
    def sk = device.currentValue('securityKeypad')
    if (logEnable) {
        log.debug "In disarm (${version()}) - sk: ${sk}"
    }
    if (sk != 'disarmed') {
        if (delay > 0) {
            exitDelay(delay)
            runIn(delay, disarmEnd)
        } else {
            disarmEnd()
        }
    } else {
        if (logEnable) {
            log.debug "In disarm - securityKeypad already set to 'disarmed', so skipping."
        }
    }
}

void disarmEnd() {
    if (!state.code) {
        state.code = ''
    }
    if (!state.type) {
        state.type = 'physical'
    }
    def sk = device.currentValue('securityKeypad')
    if (logEnable) {
        log.debug "In disarmEnd (${version()}) - sk: ${sk} code: ${state.code} type: ${state.type}"
    }
    if (sk != 'disarmed') {
        Date now = new Date()
        keypadUpdateStatus(0x02, state.type, state.code)  // Sends status to Keypad
        sendLocationEvent(name: 'hsmSetArm', value: 'disarm')
        sendEvent(name:'alarmStatusChangeTime', value: "${now}", isStateChange:true)
        long ems = now.getTime()
        sendEvent(name:'alarmStatusChangeEpochms', value: "${ems}", isStateChange:true)
        changeStatus('off')
        state.armingIn = 0
        unschedule(armHomeEnd)
        unschedule(armAwayEnd)
        unschedule(changeStatus)
    }
}

void exitDelay(delay) {
    if (logEnable) {
        log.debug "In exitDelay (${version()}) - delay: ${delay}"
    }
    if (delay) {
        sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:0x12, propertyId:7, value:delay.toInteger()]]).format())
        // update state so that a disarm command during the exit delay resets the indicator lights
        state.keypadStatus = '18'
        type = state.code != '' ? 'physical' : 'digital'
        eventProcess(name: 'securityKeypad', value: 'exit delay', type: type, data: state.code)
        if (logEnable) {
            log.debug "In exitDelay (${version()}) - type: ${type}"
        }
    }
}

def changeStatus(data) {
    if (logEnable) {
        log.debug "In changeStatus (${version()}) - data: ${data}"
    }
    sendEvent(name: 'alarm', value: data, isStateChange: true)
}

void setEntryDelay(delay) {
    if (logEnable) {
        log.debug "In setEntryDelay (${version()}) - delay: ${delay}"
    }
    state.keypadConfig.entryDelay = delay != null ? delay.toInteger() : 0
}

void setExitDelay(Map delays) {
    if (logEnable) {
        log.debug "In setExitDelay (${version()}) - delay: ${delays}"
    }
    state.keypadConfig.exitDelay = (delays?.awayDelay ?: 0).toInteger()
    state.keypadConfig.armNightDelay = (delays?.nightDelay ?: 0).toInteger()
    state.keypadConfig.armHomeDelay = (delays?.homeDelay ?: 0).toInteger()
    state.keypadConfig.armAwayDelay = (delays?.awayDelay ?: 0).toInteger()
}

void setExitDelay(delay) {
    if (logEnable) {
        log.debug "In setExitDelay (${version()}) - delay: ${delay}"
    }
    state.keypadConfig.exitDelay = delay != null ? delay.toInteger() : 0
}

void setArmNightDelay(delay) {
    if (logEnable) {
        log.debug "In setArmNightDelay (${version()}) - delay: ${delay}"
    }
    state.keypadConfig.armNightDelay = delay != null ? delay.toInteger() : 0
}

void setArmAwayDelay(delay) {
    if (logEnable) {
        log.debug "In setArmAwayDelay (${version()}) - delay: ${delay}"
    }
    sendEvent(name:'armAwayDelay', value: delay)
    state.keypadConfig.armAwayDelay = delay != null ? delay.toInteger() : 0
}

void setArmHomeDelay(delay) {
    if (logEnable) {
        log.debug "In setArmHomeDelay (${version()}) - delay: ${delay}"
    }
    sendEvent(name:'armHomeDelay', value: delay)
    state.keypadConfig.armHomeDelay = delay != null ? delay.toInteger() : 0
}
void setCodeLength(pincodelength) {
    if (logEnable) {
        log.debug "In setCodeLength (${version()}) - pincodelength: ${pincodelength}"
    }
    eventProcess(name:'codeLength', value: pincodelength, descriptionText: "${device.displayName} codeLength set to ${pincodelength}")
    state.keypadConfig.codeLength = pincodelength
    // set zwave entry code key buffer
    // 6F06XX10
    sendToDevice('6F06' + hubitat.helper.HexUtils.integerToHexString(pincodelength.toInteger() + 1, 1).padLeft(2, '0') + '0F')
}

void setPartialFunction(mode = null) {
    if (logEnable) {
        log.debug "In setPartialFucntion (${version()}) - mode: ${mode}"
    }
    if (logEnable) {
        log.debug "In setPartialFucntion (${version()}) - armingIn: ${state.armingIn}"
    }
    if (state.armingIn > 0) {
        state.armingIn = state.armingIn - 1
        if (logEnable) {
            log.debug "In setPartialFucntion (${version()}) - armingIn: ${state.armingIn}"
        }
    }
    if (!(mode in ['armHome', 'armNight'])) {
        if (txtEnable) {
            log.warn "Custom command used by HSM: ${mode}"
        }
    } else if (mode in ['armHome', 'armNight']) {
        state.keypadConfig.partialFunction = mode == 'armHome' ? 'armHome' : 'armNight'
    }
}

// alarm capability commands

void off() {
    if (logEnable) {
        log.debug "In off (${version()})"
    }
    eventProcess(name:'alarm', value:'off')
    changeStatus('off')
    sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:state.keypadStatus, propertyId:2, value:0xFF]]).format())
}

void both() {
    if (logEnable) {
        log.debug "In both (${version()})"
    }
    siren()
}

void siren() {
    if (logEnable) {
        log.debug "In Siren (${version()})"
    }
    eventProcess(name:'alarm', value:'siren')
    changeStatus('siren')
    sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:0x0C, propertyId:2, value:0xFF]]).format())
}

void strobe() {
    if (logEnable) {
        log.debug "In strobe (${version()})"
    }
    eventProcess(name:'alarm', value:'strobe')
    changeStatus('strobe')
    List<String> cmds = []
    cmds.add(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:0x0C, propertyId:2, value:0xFF]]).format())
    cmds.add(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:0x0C, propertyId:2, value:0x00]]).format())
    sendToDevice(cmds)
}

void zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {
    if (logEnable) {
        log.debug "${cmd}"
    }
}

void zwaveEvent(hubitat.zwave.commands.entrycontrolv1.EntryControlNotification cmd) {
    if (logEnable) {
        log.debug "${cmd}"
    }

    Map ecn = [:]
    ecn.sequenceNumber = cmd.sequenceNumber
    ecn.dataType = cmd.dataType
    ecn.eventType = cmd.eventType
    def currentStatus = device.currentValue('securityKeypad')
    def alarmStatus = device.currentValue('alarm')
    String code = (cmd.eventData.collect { (char) it }.join() as String)

    if (logEnable) {
        log.debug "Entry control: ${ecn} keycache: ${code}"
    }
    switch (ecn.eventType) {
        // Away Mode Button
        case 5:
            if (logEnable) {
                log.debug 'In case 5 - Away Mode Button'
            }
            if (validatePin(code) || instantArming) {
                if (currentStatus == 'disarmed') {
                    if (logEnable) {
                        log.debug "In case 5 - Passed - currentStatus: ${currentStatus}"
                    }
                    state.type = 'physical'
                    if (!state.keypadConfig.armAwayDelay) {
                        state.keypadConfig.armAwayDelay = 0
                    }
                    // Indicate 'armingIn' event to HSM. HSM subscribes to this event and will call armAway() after the delay.
                    // If arming fails (bypass failure etc), HSM will not call armAway and we'll return to normal.
                    sendEvent(name:'armingIn', descriptionText: "Arming AWAY mode in ${state.keypadConfig.armAwayDelay} delay", value: state.keypadConfig.armAwayDelay, data:[armMode: armingStates[0x0B].securityKeypadState, armCmd: armingStates[0x0B].hsmCmd], isStateChange:true)
                } else {
                    if (logEnable) {
                        log.debug "In case 5 - Failed - Please Disarm Alarm before changing alarm type - currentStatus: ${currentStatus}"
                    }
                }
            } else {
                if (logEnable) {
                    log.debug "In case 5 - Failed - Invalid PIN - currentStatus: ${currentStatus}"
                }
                sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:0x09, propertyId:2, value:0xFF]]).format())
            }
            break
        // Home Mode Button
        case 6:
            if (logEnable) {
                log.debug 'In case 6 - Home Mode Button'
            }
            if (validatePin(code) || instantArming) {
                if (currentStatus == 'disarmed') {
                    if (logEnable) log.debug 'In case 6 - Passed'
                    state.type = 'physical'
                    state.keypadConfig.partialFunction = state.keypadConfig.partialFunctiob ?: 'armHome'
                    if (state.keypadConfig.partialFunction == 'armHome') {
                        if (logEnable) {
                            log.debug 'In case 6 - Partial Passed - Arming Home'
                        }
                        if (!state.keypadConfig.armHomeDelay) {
                            state.keypadConfig.armHomeDelay = 0
                        }
                        // Indicate 'armingIn' event to HSM. HSM subscribes to this event and will call armAway() after the delay.
                        // If arming fails (bypass failure etc), HSM will not call armAway and we'll return to normal.
                        sendEvent(name:'armingIn', descriptionText: "Arming HOME mode in ${state.keypadConfig.armAwayDelay} delay", value: state.keypadConfig.armHomeDelay, data:[armMode: armingStates[0x0A].securityKeypadState, armCmd: armingStates[0x0A].hsmCmd], isStateChange:true)
                    }
                    // TODO: Testing, this is why armNight currently does nothing, the handling for the partialFunction was totally missing.
                    if (state.keypadConfig.partialFunction == 'armNight') {
                        if (logEnable) {
                            log.debug 'In case 6 - Partial Passed - Arming Night'
                        }
                        if (!state.keypadConfig.armNightDelay) {
                            state.keypadConfig.armNightDelay = 0
                        }
                        // Indicate 'armingIn' event to HSM. HSM subscribes to this event and will call armAway() after the delay.
                        // If arming fails (bypass failure etc), HSM will not call armAway and we'll return to normal.
                        sendEvent(name:'armingIn', descriptionText: "Arming NIGHT mode in ${state.keypadConfig.armAwayDelay} delay", value: state.keypadConfig.armNightDelay, data:[armMode: armingStates[0x00].securityKeypadState, armCmd: armingStates[0x00].hsmCmd], isStateChange:true)
                    }
                } else {
                    if (alarmStatus == 'active') {
                        if (logEnable) {
                            log.debug "In case 6 - Silenced - Alarm will sound again in 10 seconds - currentStatus: ${currentStatus}"
                        }
                        changeStatus('silent')
                        runIn(10, changeStatus, [data:'active'])
                    } else {
                        if (logEnable) {
                            log.debug "In case 6 - Failed - Please Disarm Alarm before changing alarm type - currentStatus: ${currentStatus}"
                        }
                    }
                }
            } else {
                if (logEnable) {
                    log.debug "In case 6 - Home Mode Failed - Invalid PIN - currentStatus: ${currentStatus}"
                }
                sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:0x09, propertyId:2, value:0xFF]]).format())
            }
            break
        // Disarm Mode Button
        case 3:
            if (logEnable) {
                log.debug 'In case 3 - Disarm Mode Button'
            }
            if (validatePin(code)) {
                if (logEnable) {
                    log.debug 'In case 3 - Code Passed'
                }
                state.type = 'physical'
                // Event HSM Subscribes to
                sendEvent(name:'armingIn', value: 0, descriptionText: 'Disarming after valid code entry', data:[armMode: armingStates[0x02].securityKeypadState, armCmd: armingStates[0x02].hsmCmd], isStateChange:true)
                keypadUpdateStatus(0x02, state.type, state.code) // Sends status to Keypad to move it to disarmed
                Date now = new Date()
                sendEvent(name:'alarmStatusChangeTime', value: "${now}", isStateChange:true)
                long ems = now.getTime()
                sendEvent(name:'alarmStatusChangeEpochms', value: "${ems}", isStateChange:true)
                changeStatus('off')
                state.armingIn = 0
                unschedule(armHomeEnd)
                unschedule(armAwayEnd)
                unschedule(changeStatus)
            } else {
                if (logEnable) {
                    log.debug "In case 3 - Disarm Failed - Invalid PIN - currentStatus: ${currentStatus}"
                }
                sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:0x09, propertyId:2, value:0xFF]]).format())
            }
            break
        // Code sent after hitting the Check Mark
        case 2:
            state.type = 'physical'
            Date now = new Date()
            long ems = now.getTime()
            if (!code) {
                code = 'check mark'
            }
            if (validateCheck) {
                if (validatePin(code)) {
                    if (logEnable) {
                        log.debug 'In case 2 (check mark) - Code Passed'
                    }
                } else {
                    if (logEnable) {
                        log.debug "In case 2 (check mark) - Code Failed - Invalid PIN - currentStatus: ${currentStatus}"
                    }
                    sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:0x09, propertyId:2, value:0xFF]]).format())
                }
            } else {
                sendEvent(name:'lastCodeName', value: "${code}", isStateChange:true)
                sendEvent(name:'lastCodeTime', value: "${now}", isStateChange:true)
                sendEvent(name:'lastCodeEpochms', value: "${ems}", isStateChange:true)
            }
            break
        // Police Button
        case 17:
            state.type = 'physical'
            Date now = new Date()
            long ems = now.getTime()
            sendEvent(name:'lastCodeName', value: 'police', isStateChange:true)
            sendEvent(name:'lastCodeTime', value: "${now}", isStateChange:true)
            sendEvent(name:'lastCodeEpochms', value: "${ems}", isStateChange:true)
            sendEvent(name: 'held', value: 11, isStateChange: true)
            break
        // Fire Button
        case 16:
            state.type = 'physical'
            Date now = new Date()
            long ems = now.getTime()
            sendEvent(name:'lastCodeName', value: 'fire', isStateChange:true)
            sendEvent(name:'lastCodeTime', value: "${now}", isStateChange:true)
            sendEvent(name:'lastCodeEpochms', value: "${ems}", isStateChange:true)
            sendEvent(name: 'held', value: 12, isStateChange: true)
            break
        // Medical Button
        case 19:
            state.type = 'physical'
            Date now = new Date()
            long ems = now.getTime()
            sendEvent(name:'lastCodeName', value: 'medical', isStateChange:true)
            sendEvent(name:'lastCodeTime', value: "${now}", isStateChange:true)
            sendEvent(name:'lastCodeEpochms', value: "${ems}", isStateChange:true)
            sendEvent(name: 'held', value: 13, isStateChange: true)
            break
        // Button pressed or held, idle timeout reached without explicit submission
        case 1:
            state.type = 'physical'
            handleButtons(code)
            break
    }
}

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
    sendEvent(name: 'held', value: btn, isStateChange:true)
    switch (btn) {
        case 11:
            Date now = new Date()
            long ems = now.getTime()
            sendEvent(name:'lastCodeName', value: 'police', isStateChange:true)
            sendEvent(name:'lastCodeTime', value: "${now}", isStateChange:true)
            sendEvent(name:'lastCodeEpochms', value: "${ems}", isStateChange:true)
            break
        case 12:
            Date now = new Date()
            long ems = now.getTime()
            sendEvent(name:'lastCodeName', value: 'fire', isStateChange:true)
            sendEvent(name:'lastCodeTime', value: "${now}", isStateChange:true)
            sendEvent(name:'lastCodeEpochms', value: "${ems}", isStateChange:true)
            break
        case 13:
            Date now = new Date()
            long ems = now.getTime()
            sendEvent(name:'lastCodeName', value: 'medical', isStateChange:true)
            sendEvent(name:'lastCodeTime', value: "${now}", isStateChange:true)
            sendEvent(name:'lastCodeEpochms', value: "${ems}", isStateChange:true)
            break
    }
}

void zwaveEvent(hubitat.zwave.commands.notificationv8.NotificationReport cmd) {
    if (logEnable) {
        log.info "Notification Report: ${cmd}"
    }
    Map evt = [:]
    if (cmd.notificationType == 8) {
        // power management
        switch (cmd.event) {
            case 1:
                // Power has been applied
                if (txtEnable) {
                    log.info "${device.displayName} Power has been applied"
                }
                break
            case 2:
                // AC mains disconnected
                evt.name = 'powerSource'
                evt.value = 'battery'
                evt.descriptionText = "${device.displayName} AC mains disconnected"
                eventProcess(evt)
                break
            case 3:
                // AC mains re-connected
                evt.name = 'powerSource'
                evt.value = 'mains'
                evt.descriptionText = "${device.displayName} AC mains re-connected"
                eventProcess(evt)
                break
            case 12:
                // battery is charging
                if (txtEnable) {
                    log.info "${device.displayName} Battery is charging"
                }
                break
        }
    }
    else if (cmd.notificationType == 7) {
        // security
        if (cmd.event == 8) {
            // motion active
            evt.name = 'motion'
            evt.value = 'active'
        } else if (cmd.event == 0 && cmd.eventParameter[0] == 8) {
            // idle event, motion clear
            evt.name = 'motion'
            evt.value = 'inactive'
        }
        // According to the manual, this is a "Dropped Frame" notification.
        if (cmd.event == 0 && cmd.notificationStatus == 255) {
            log.warn "${device.displayName} reports a dropped frame!."
        } else if (cmd.event == 0 && cmd.notificationStatus == 0) {
            if (logEnable) {
                log.debug "${device.displayName} reports that dropped frames condition has cleared."
            }
        }
        else {
            log.warn "Unhandled Notification (Security): ${cmd}"
        }
        // send event if found
        if (evt.name) {
            evt.descriptionText = "${device.displayName} ${evt.name} is ${evt.value}"
            eventProcess(evt)
        }
    } else if (cmd.notificationType == 9) {
        // According to the manual, this is the "System" notification type. 4 is a "software fault".
        // There are different kinds of faults documented.
        if (cmd.event == 4) {
            log.warn "${device.displayName} reports a software fault: ${cmd.eventParameter[0]}: 0x${hubitat.helper.HexUtils.integerToHexString(cmd.eventParameter[0], 1)}."
        }
    }
    else {
        if (logEnable) {
            log.debug "Unhandled NotificationReport: ${cmd}"
        }
    }
}

void entry() {
    int intDelay = state.keypadConfig.entryDelay ? state.keypadConfig.entryDelay.toInteger() : 0
    if (intDelay) {
        entry(intDelay)
    }
}

void entry(entranceDelay) {
    if (logEnable) {
        log.debug "In entry (${version()}) - delay: ${entranceDelay}"
    }
    if (entranceDelay) {
        sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:0x11, propertyId:7, value:entranceDelay.toInteger()]]).format())
    }
}

void getCodes() {
    if (logEnable) {
        log.debug 'getCodes()'
    }
    updateEncryption()
}

private updateEncryption() {
    String lockCodes = device.currentValue('lockCodes') //encrypted or decrypted
    if (lockCodes) {
        if (optEncrypt && lockCodes[0] == '{') {    //resend encrypted
            sendEvent(name:'lockCodes', value: encrypt(lockCodes), isStateChange:true)
        } else if (!optEncrypt && lockCodes[0] != '{') {    //resend decrypted
            sendEvent(name:'lockCodes', value: decrypt(lockCodes), isStateChange:true)
        } else {
            sendEvent(name:'lockCodes', value: lockCodes, isStateChange:true)
        }
    }
}

private Boolean validatePin(String pincode) {
    boolean retVal = false
    Map lockcodes = [:]
    if (optEncrypt) {
        try {
            lockcodes = parseJson(decrypt(device.currentValue('lockCodes')))
        } catch (e) {
            log.warn 'Ring Alarm Keypad G2 Community - No lock codes found.'
        }
    } else {
        try {
            lockcodes = parseJson(device.currentValue('lockCodes'))
        } catch (e) {
            log.warn 'Ring Alarm Keypad G2 Community - No lock codes found.'
        }
    }
    if (lockcodes) {
        lockcodes.each {
            if (it.value['code'] == pincode) {
                Date now = new Date()
                long ems = now.getTime()
                sendEvent(name:'validCode', value: 'true', isStateChange: true)
                sendEvent(name:'lastCodeName', value: "${it.value['name']}", isStateChange:true)
                sendEvent(name:'lastCodeTime', value: "${now}", isStateChange:true)
                sendEvent(name:'lastCodeEpochms', value: "${ems}", isStateChange:true)
                retVal = true
                String code = JsonOutput.toJson(["${it.key}":['name': "${it.value.name}", 'code': "${it.value.code}", 'isInitiator': true]])
                if (optEncrypt) {
                    state.code = encrypt(code)
                } else {
                    state.code = code
                }
            }
        }
    }
    if (!retVal) {
        sendEvent(name:'validCode', value: 'false', isStateChange: true)
    }
    return retVal
}

void setCode(codeposition, pincode, name) {
    if (logEnable) {
        log.debug "setCode(${codeposition}, ${pincode}, ${name})"
    }
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
    if (logEnable) {
        log.debug "deleteCode(${codeposition})"
    }
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

void zwaveEvent(hubitat.zwave.commands.indicatorv3.IndicatorReport cmd) {
    if (logEnable) {
        log.debug "Indicator Report: ${cmd}"
    }
}

List<String> runConfigs() {
    List<String> cmds = []
    configParams.each { param, data ->
        if (settings[data.input.name]) {
            if (logEnable) {
                log.debug "Configuring parameter: ${param} to ${settings[data.input.name]}"
            }
            cmds.addAll(configCmd(param, data.parameterSize, settings[data.input.name]))
        }
    }
    return cmds
}

List<String> pollConfigs() {
    List<String> cmds = []
    configParams.each { param, data ->
        if (settings[data.input.name]) {
            cmds.add(zwave.configurationV1.configurationGet(parameterNumber: param.toInteger()).format())
        }
    }
    return cmds
}

List<String> configCmd(parameterNumber, size, scaledConfigurationValue) {
    List<String> cmds = []
    cmds.add(zwave.configurationV1.configurationSet(parameterNumber: parameterNumber.toInteger(), size: size.toInteger(), scaledConfigurationValue: scaledConfigurationValue.toInteger()).format())
    cmds.add(zwave.configurationV1.configurationGet(parameterNumber: parameterNumber.toInteger()).format())
    return cmds
}

void zwaveEvent(hubitat.zwave.commands.configurationv1.ConfigurationReport cmd) {
    if (configParams[cmd.parameterNumber.toInteger()]) {
        Map configParam = configParams[cmd.parameterNumber.toInteger()]
        int scaledValue
        cmd.configurationValue.reverse().eachWithIndex { v, index ->
            scaledValue = scaledValue | v << (8 * index)
        }
        device.updateSetting(configParam.input.name, [value: "${scaledValue}", type: configParam.input.type])
    }
}

// Battery v1

void zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd) {
    Map evt = [name: 'battery', unit: '%']
    if (cmd.batteryLevel == 0xFF) {
        evt.descriptionText = "${device.displayName} has a low battery"
        evt.value = 1
    } else {
        evt.value = cmd.batteryLevel
        evt.descriptionText = "${device.displayName} battery is ${evt.value}${evt.unit}"
    }
    evt.isStateChange = true
    if (txtEnable && evt.descriptionText) {
        log.info evt.descriptionText
    }
    sendEvent(evt)
}

// MSP V2

void zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.DeviceSpecificReport cmd) {
    if (logEnable) {
        log.debug "Device Specific Report - DeviceIdType: ${cmd.deviceIdType}, DeviceIdFormat: ${cmd.deviceIdDataFormat}, Data: ${cmd.deviceIdData}"
    }
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

// Version V2

void zwaveEvent(hubitat.zwave.commands.versionv3.VersionReport cmd) {
    Double firmware0Version = cmd.firmware0Version + (cmd.firmware0SubVersion / 100)
    Double protocolVersion = cmd.zWaveProtocolVersion + (cmd.zWaveProtocolSubVersion / 100)
    if (logEnable) {
        log.debug "Version Report - FirmwareVersion: ${firmware0Version}, ProtocolVersion: ${protocolVersion}, HardwareVersion: ${cmd.hardwareVersion}"
    }
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

// Association V2

List<String> setDefaultAssociation() {
    List<String> cmds = []
    cmds.add(zwave.associationV2.associationSet(groupingIdentifier: 1, nodeId: zwaveHubNodeId).format())
    cmds.add(zwave.associationV2.associationGet(groupingIdentifier: 1).format())
    return cmds
}

List<String> processAssociations() {
    List<String> cmds = []
    cmds.addAll(setDefaultAssociation())
    return cmds
}

void zwaveEvent(hubitat.zwave.commands.associationv2.AssociationReport cmd) {
    List<String> temp = []
    if (cmd.nodeId != []) {
        cmd.nodeId.each {
            temp.add(it.toString().format( '%02x', it.toInteger() ).toUpperCase())
        }
    }
    if (logEnable) {
        log.debug "Association Report - Group: ${cmd.groupingIdentifier}, Nodes: $temp"
    }
}

// event filter

void eventProcess(Map evt) {
    if (txtEnable && evt.descriptionText) {
        log.info evt.descriptionText
    }
    if (device.currentValue(evt.name).toString() != evt.value.toString()) {
        sendEvent(evt)
    }
}

// universal

void zwaveEvent(hubitat.zwave.Command cmd) {
    if (logEnable) {
        log.debug "skip: ${cmd}"
    }
}

void zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
    if (logEnable) {
        log.debug "S2 Encapsulation: ${cmd}"
    }
    hubitat.zwave.Command encapsulatedCommand = cmd.encapsulatedCommand(CMD_CLASS_VERS)
    if (encapsulatedCommand) {
        zwaveEvent(encapsulatedCommand)
    }
}

void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd) {
    if (logEnable) {
        log.debug "Supervision Get - SessionID: ${cmd.sessionID}, CC: ${cmd.commandClassIdentifier}, Command: ${cmd.commandIdentifier}"
    }

    hubitat.zwave.Command encapsulatedCommand = cmd.encapsulatedCommand(CMD_CLASS_VERS)
    if (encapsulatedCommand) {
        zwaveEvent(encapsulatedCommand)
    }

    // device quirk requires this to be insecure reply
    sendToDevice(zwave.supervisionV1.supervisionReport(sessionID: cmd.sessionID, reserved: 0, moreStatusUpdates: false, status: 0xFF, duration: 0).format())
}

void parse(String description) {
    if (logEnable) {
        log.debug "parse - ${description}"
    }
    hubitat.zwave.Command cmd = zwave.parse(description, CMD_CLASS_VERS)
    if (cmd) {
        zwaveEvent(cmd)
    }
}

void sendToDevice(List<String> cmds, Long delay=300) {
    sendHubCommand(new hubitat.device.HubMultiAction(commands(cmds, delay), hubitat.device.Protocol.ZWAVE))
}

void sendToDevice(String cmd, Long delay=300) {
    sendHubCommand(new hubitat.device.HubAction(zwaveSecureEncap(cmd), hubitat.device.Protocol.ZWAVE))
}

List<String> commands(List<String> cmds, Long delay=300) {
    return delayBetween(cmds.collect { zwaveSecureEncap(it) }, delay)
}

void proximitySensorHandler() {
    if (proximitySensor) {
        if (logEnable) log.debug 'Turning the Proximity Sensor OFF'
        sendToDevice(new hubitat.zwave.commands.configurationv1.ConfigurationSet(parameterNumber: 15, size: 1, scaledConfigurationValue: 0).format())
    } else {
        if (logEnable) log.debug 'Turning the Proximity Sensor ON'
        sendToDevice(new hubitat.zwave.commands.configurationv1.ConfigurationSet(parameterNumber: 15, size: 1, scaledConfigurationValue: 1).format())
    }
}

def volAnnouncement(newVol=null) {
    if (logEnable) {
        log.debug "In volAnnouncement (${version()}) - newVol: ${newVol}"
    }
    if (newVol) {
        def currentVol = device.currentValue('volAnnouncement')
        if (newVol.toString() == currentVol.toString()) {
            if (logEnable) {
                log.debug "Announcement Volume hasn't changed, so skipping"
            }
        } else {
            if (logEnable) {
                log.debug "Setting the Announcement Volume to $newVol"
            }
            nVol = newVol.toInteger()
            sendToDevice(new hubitat.zwave.commands.configurationv1.ConfigurationSet(parameterNumber: 4, size: 1, scaledConfigurationValue: nVol).format())
            sendEvent(name:'volAnnouncement', value: newVol, isStateChange:true)
        }
    } else {
        if (logEnable) log.debug 'Announcement value not specified, so skipping'
    }
}

def volKeytone(newVol=null) {
    if (logEnable) {
        log.debug "In volKeytone (${version()}) - newVol: ${newVol}"
    }
    if (newVol) {
        def currentVol = device.currentValue('volKeytone')
        if (newVol.toString() == currentVol.toString()) {
            if (logEnable) {
                log.debug "Keytone Volume hasn't changed, so skipping"
            }
        } else {
            if (logEnable) {
                log.debug "Setting the Keytone Volume to $newVol"
            }
            nVol = newVol.toInteger()
            sendToDevice(new hubitat.zwave.commands.configurationv1.ConfigurationSet(parameterNumber: 5, size: 1, scaledConfigurationValue: nVol).format())
            sendEvent(name:'volKeytone', value: newVol, isStateChange:true)
        }
    } else {
        if (logEnable) log.debug 'Keytone value not specified, so skipping'
    }
}

def volSiren(newVol=null) {
    if (logEnable) {
        log.debug "In volSiren (${version()}) - newVol: ${newVol}"
    }
    if (newVol) {
        def currentVol = device.currentValue('volSiren')
        if (newVol.toString() == currentVol.toString()) {
            if (logEnable) {
                log.debug "Siren Volume hasn't changed, so skipping"
            }
            def sVol = currentVol.toInteger() * 10
        } else {
            if (logEnable) {
                log.debug "Setting the Siren Volume to $newVol"
            }
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
    if (logEnable) {
        log.debug "In playTone (${version()}) - tone: ${tone} at Volume: ${sVol}"
    }
    if (!tone) {
        tone = theTone
        if (logEnable) {
            log.debug "In playTone - Tone is NULL, so setting tone to theTone: ${tone}"
        }
    }
    if (tone == 'Tone_1') { // Siren
        if (logEnable) {
            log.debug 'In playTone - Tone 1'
        }
        changeStatus('active')
        sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:0x0C, propertyId:2, value:sVol]]).format())
    } else if (tone == 'Tone_2') { // 3 chirps
        if (logEnable) {
            log.debug 'In playTone - Tone 2'
        }
        changeStatus('active')
        sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:0x0E, propertyId:2, value:sVol]]).format())
    } else if (tone == 'Tone_3') { // 4 chirps
        if (logEnable) {
            log.debug 'In playTone - Tone 3'
        }
        changeStatus('active')
        sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:0x0F, propertyId:2, value:sVol]]).format())
    } else if (tone == 'Tone_4') { // Navi
        if (logEnable) {
            log.debug 'In playTone - Tone 4'
        }
        changeStatus('active')
        sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:0x60, propertyId:0x09, value:sVol]]).format())
    } else if (tone == 'Tone_5') { // Guitar
        if (logEnable) {
            log.debug 'In playTone - Tone 5'
        }
        changeStatus('active')
        sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:0x61, propertyId:0x09, value:sVol]]).format())
    } else if (tone == 'Tone_6') { // Windchimes
        if (logEnable) {
            log.debug 'In playTone - Tone 6'
        }
        changeStatus('active')
        sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:0x62, propertyId:0x09, value:sVol]]).format())
    } else if (tone == 'Tone_7') { // Doorbell 1
        if (logEnable) {
            log.debug 'In playTone - Tone 7'
        }
        changeStatus('active')
        sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:0x63, propertyId:0x09, value:sVol]]).format())
    } else if (tone == 'Tone_8') { // Doorbell 2
        if (logEnable) {
            log.debug 'In playTone - Tone 8'
        }
        changeStatus('active')
        sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:0x64, propertyId:0x09, value:sVol]]).format())
    } else if (tone == 'Tone_9') { // Invalid Code Sound
        if (logEnable) {
            log.debug 'In playTone - Tone  9'
        }
        changeStatus('active')
        sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:0x09, propertyId:0x01, value:sVol]]).format())
    } else if (tone == 'test') { // test
        if (logEnable) {
            log.debug 'In playTone - test'
        }
        changeStatus('active')
        sendToDevice(zwave.indicatorV3.indicatorSet(indicatorCount:1, value: 0, indicatorValues:[[indicatorId:0x61, propertyId:0x09, value:sVol]]).format())
    }
}
