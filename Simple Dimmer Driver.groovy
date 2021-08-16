import java.util.concurrent.* // Available (allow-listed) concurrency classes: ConcurrentHashMap, ConcurrentLinkedQueue, Semaphore, SynchronousQueue
import groovy.transform.Field

//////////////
#include zwaveTools.sendReceiveTools
void supervisionCheckResendInfo(report){
	log.warn "Device ${device.displayName}: Supervision Check is resending command: ${report.command} for endpoint ${report.endPoint}, previous attempts: ${report.attempt}."
}
Integer getS2MaxRetries() { return 5 }
Integer getS2RetryPeriod() { return 1500}
/////////// 
#include zwaveTools.globalDataTools
#include zwaveTools.endpointTools
#include zwaveTools.batteryTools
#include zwaveTools.zwaveDeviceDatabase
#include zwaveTools.notificationTools
#include zwaveTools.meterTools
#include zwaveTools.sensorTools
#include zwaveTools.binaryAndMultilevelDeviceTools
#include zwaveTools.centralSceneTools
#include zwaveTools.openSmarthouseTools
/////////////////


metadata {
	definition (name: "Any Z-Wave Dimmer Driver v1.5.5",namespace: "jvm", author: "jvm") {
		capability "Initialize"
		capability "Refresh"

		capability "Actuator"
		capability "Switch"
		capability "SwitchLevel"
		
       // capability "Sensor"				
        // capability "MotionSensor"
        // capability "TamperAlert"
		// capability "WaterSensor"
		// capability "ContactSensor"
		// capability "ShockSensor"		// Use this for glass breakage!
		// capability "IllumanceMeasurement"
		// capability "LiquidFlowRate"
		// attribute "carbonDioxideDetected"
		
		capability "EnergyMeter"
        capability "PowerMeter"
		capability "VoltageMeasurement"
        capability "CurrentMeter"
		attribute "energyConsumed", "number" 	// Custom Attribute for meter devices supporting energy consumption. Comment out if not wanted.
		attribute "powerFactor", "number"	// Custom Attribute for meter devices supporting powerFactor. Comment out if not wanted.
		attribute "pulseCount", "number"		// Custom Attribute for meter devices supporting powerFactor. Comment out if not wanted.
		attribute "reactiveCurrent", "number"		// Custom Attribute for meter devices supporting powerFactor. Comment out if not wanted.
		attribute "reactivePower", "number"		// Custom Attribute for meter devices supporting powerFactor. Comment out if not wanted.
		
		// capability "Battery"

		// capability "Consumable" 		// For smoke, CO, CO2 alarms that report their end-of-life
		// capability "FilterStatus" 	// For water filters that report status of filter
		
		capability "PushableButton"
		capability "HoldableButton"
		capability "ReleasableButton"
		capability "DoubleTapableButton"	
		attribute "multiTapButton", "number"

		command "identify" // implements the Z-Wave Plus identify function which can flash device indicators.
		command "resetDriver" // deletes the stored state information
							
        command "multiTap", [[name:"button",type:"NUMBER", description:"Button Number", constraints:["NUMBER"]],
		 			[name:"taps",type:"NUMBER", description:"Tap count", constraints:["NUMBER"]]]	

		command "setParameter",[[name:"parameterNumber",type:"NUMBER", description:"Parameter Number", constraints:["NUMBER"]],
					[name:"value",type:"NUMBER", description:"Parameter Value", constraints:["NUMBER"]]
					]	

		// Following Command is to help create a new data record to be added to deviceDatabase
        command "logDataRecord"

    }
	
	preferences 
	{	
        input name: "showParameterInputs", type: "bool", title: "Show Parameter Value Input Controls", defaultValue: false    
		input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
		input name: "txtEnable", type: "bool", title: "Enable text logging", defaultValue: true
		/*
		if (showParameterInputs) {
			getParameterValuesFromDevice()
			deviceInputs?.each{key, value -> input value}
        }
		*/
    }	
}
/////////////////////////////////////////////////////////////////////////
//////        Create and Manage Child Devices for Endpoints       ///////
/////////////////////////////////////////////////////////////////////////

void logDataRecord() {
log.debug dataRecordByProductType
}

void deleteUnwantedChildDevices()
{	
	// Delete child devices that don't use the proper network ID form (parent ID, followed by "-ep" followed by endpoint number).
	getChildDevices()?.each
	{ child ->	
	
		List childNetIdComponents = child.deviceNetworkId.split("-ep")
		if ((thisDeviceDataRecord.endpoints.containsKey(childNetIdComponents[1] as Integer)) && (childNetIdComponents[0] == device.deviceNetworkId)) {
			return
		} else {
			deleteChildDevice(child.deviceNetworkId)
		}			
	}
}

void createChildDevices()
{	
	thisDeviceDataRecord.endpoints.findAll{k, v -> (k != 0)}.each
	{ ep, value ->
		String childNetworkId = "${device.deviceNetworkId}-ep${"${ep}".padLeft(3, "0") }"
		com.hubitat.app.DeviceWrapper cd = getChildDevice(childNetworkId)
		if (cd.is( null )) {
			log.info "Device ${device.displayName}: creating child device: ${childNetworkId} with driver ${value.driver.type} and namespace: ${value.driver.namespace}."
			
			addChildDevice(value.driver.namespace, value.driver.type, childNetworkId, [name: value.driver.childName ?:"${device.displayName}-ep${ep}", isComponent: false])
		} 
	}
}
/////////////////////////////////////////////////////////////////

void identify() {
	log.warn "Device ${device.displayName}: The 'identify' function is experimental and only works for Zwave Plus Version 2 or greater devices!"
	// Identify function supported by Zwave Plus Version 2 and greater devices!
		List<Map<String, Short>> indicators = [
			[indicatorId:0x50, propertyId:0x03, value:0x08], 
			[indicatorId:0x50, propertyId:0x04, value:0x03],  
			[indicatorId:0x50, propertyId:0x05, value:0x06]
		]
		advancedZwaveSend(zwave.indicatorV3.indicatorSet(indicatorCount:3 , value:0, indicatorValues: indicators ))
}


void resetDriver() {
	state.clear()
}

void clearLeftoverStates() {
	List<String> allowed = ["deviceRecord"] 
	
	// Can't modify state from within state.each{}, so first collect what is unwanted, then remove in a separate unwanted.each
	List<String> unwanted = state.collect{ 
			if (allowed.contains( it.key as String)) return
			return it.key
		}.each{state.remove( it ) }
}

void removeAllSettings() {
    if (logEnable) log.debug "settings before clearing: " + settings
    // Copy keys set first to avoid any chance of concurrent modification
    def keys = new HashSet(settings.keySet())
    keys.each{ key -> device.removeSetting(key) }
     if (logEnable) log.debug "settings after clearing: " + settings
}

void initialize()
{
	// removeAllSettings()
	// By default, hide the parameter settings inputs since displaying them forces a refresh of all values the first time they are shown and is time consuming!
    device.updateSetting("showParameterInputs",[value:"false",type:"bool"])

	clearLeftoverStates()
	log.info "Device ${device.displayName}: Initializing."

	/////////////////////////////////////////////////////////////////////////////////////
	///                      Don't Alter this code block code!                        ///
	/// This code manages the different ways in which the device record may be stored ///
	///             - i.e., locally or from the openSmartHouse database               ///
	/////////////////////////////////////////////////////////////////////////////////////
	// If the format of the device record has changed, delete any locally stored data and recreate 
	if ((state.deviceRecord?.formatVersion as Integer) != dataRecordFormatVersion) state.remove("deviceRecord")
	
	Map localDataRecord = getThisDeviceDatabaseRecord()
	if (localDataRecord && (localDataRecord.formatVersion != dataRecordFormatVersion)) {
		log.warn "Device ${device.displayName}: Locally stored data record has wrong version number and will be ignored. Obtaining data from openSmartHouse instead. Locally stored record is: ${localDataRecord.inspect()}."
		}
		
	if (localDataRecord && (localDataRecord.formatVersion == dataRecordFormatVersion)){
		state.remove("deviceRecord") // If a device data record was added to the database, delete if it was previously from openSmartHouse.
		dataRecordByProductType.putAll(reparseDeviceData(localDataRecord)) // Store in the Global ConcurrentHashMap
	} else if ( state.deviceRecord && getDataRecordByProductType().deviceRecord.is( null ) ) { 
		// Put in the Global ConcurrentHashMap if it exist locally.
		dataRecordByProductType.putAll(reparseDeviceData(localDataRecord)) // Store in the Global ConcurrentHashMap
	} else if ( state.deviceRecord.is( null ) && getDataRecordByProductType().deviceRecord ) {
		// Data record doesn't exist in state, but it is in the concurrentHashMap - So store in state rather than re-retrieve
		state.deviceRecord = dataRecordByProductType.deviceRecord
	} else if ( state.deviceRecord.is( null )) {
		// Data record doesn't exist - get it and store in the global data record
		Map createdRecord = openSmarthouseCreateDeviceDataRecord() 
		state.deviceRecord = createdRecord
		if (createdRecord) dataRecordByProductType.putAll(reparseDeviceData(localDataRecord))
	}
	///////////////////////////////////////////////////////////////////////////////////
	//////////          Done with Device Data Record Management      //////////////////
	///////////////////////////////////////////////////////////////////////////////////	
	List<Integer> supportedClasses = getThisEndpointClasses(ep)
	
	// Create child devices if this is a multi-channel device.
	if (getDataRecordByProductType().classVersions?.containsKey(0x60)) {
		deleteUnwantedChildDevices()
		createChildDevices()
		}

	if (getDataRecordByProductType().classVersions?.containsKey(0x5B)) advancedZwaveSend(zwave.centralSceneV3.centralSceneSupportedGet())
	if (getDataRecordByProductType().classVersions?.containsKey(0x6C)) sendInitialCommand()
	
	if (txtEnable) log.info "Device ${device.displayName}: Refreshing device data."
	refresh()  
	
	if (txtEnable) log.info "Device ${device.displayName}: Done Initializing."

}

//////////// Get Inputs //////////////

Map getDeviceInputs()  { 
	Map returnMe = getDataRecordByProductType()?.deviceInputs?.sort({it.key})
	if (logEnable && returnMe.is( null ) ) log.warn "Device ${device.displayName}: Device has no inputs. Check if device was initialized. returnMe is ${returnMe}."
	return returnMe
}

Map filteredDeviceInputs() {
	if (advancedEnable) { 
		return getDeviceInputs()?.sort()
	} else  { // Just show the basic items
		return 	getDeviceInputs()?.findAll { it.value.category != "advanced" }?.sort()
	}
}

///////////////////////////////////////////////////////////////////////////////////////
///////      Handle Refreshes      ////////
/////////////////////////////////////////////////////////////////////////////////////// 
void componentRefresh(com.hubitat.app.DeviceWrapper cd){
	refreshEndpoint(cd:cd)
}

void refreshEndpoint(Map params = [cd: null, ep: null ])
{
	// com.hubitat.app.DeviceWrapper targetDevice = device
	Integer ep = null
	if (params.cd) {
			ep = (params.cd.deviceNetworkId.split("-ep")[-1]) as Integer
	} else if (! params.ep.is( null )) {
		ep = params.ep as Integer
	}
	if (ep.is( null )) return
	
	Map record = getThisEndpointData(ep)
		if (logEnable) log.debug "Device ${device.displayName}: Refreshing endpoint: ${ep ?: 0} with record ${record}"
		if (txtEnable) log.info "Device ${device.displayName}: refreshing values for endpoint ${ep}."
		List<Integer> supportedClasses = getThisEndpointClasses(ep)
		if (supportedClasses.contains(0x25)) 		advancedZwaveSend(zwave.switchBinaryV1.switchBinaryGet(), ep)
		if (supportedClasses.contains(0x26)) 		advancedZwaveSend(zwave.switchMultilevelV4.switchMultilevelGet(), ep)
		if (supportedClasses.contains(0x32) && meterTools_refresh) 			meterTools_refresh(ep)
		if (supportedClasses.contains(0x71) && notificationTools_refresh ) 	notificationTools_refresh(ep)
		// if (record.classes.contains(0x62)) 		refreshLock(ep)
		if (supportedClasses.contains(0x80)) 		batteryTools_refreshBattery()
}

void refresh()
{
	getFullEndpointRecord().each{thisEp, v ->
		refreshEndpoint(ep:thisEp)
	}
}

void	refreshLock(ep = null ) {
	log.error "Device ${device.displayName} Function refreshLock is not fully implemented."
}

/////////////////////////////////////////////////////////////////////////////////////// 
///////                   Parameter Updating and Management                    ////////
///////      Handle Update(), and Set, Get, and Process Parameter Values       ////////
/////////////////////////////////////////////////////////////////////////////////////// 

void logsOff() {
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

void updated()
{
	if (txtEnable) log.info "Device ${device.displayName}: Updating changed parameters (if any) . . ."
	if (logEnable) runIn(1800,logsOff)
	
	ConcurrentHashMap<Integer, BigInteger> parameterValueMap = getParameterValuesFromDevice()
	if (parameterValueMap.is( null ))
		{
			log.error "In function updated, parameterValueMap is ${parameterValueMap}"
			return
		}

	ConcurrentHashMap<Integer, BigInteger> pendingChanges = getPendingChangeMap()

	Map<Integer, BigInteger>  settingValueMap = getParameterValuesFromInputControls()
	// if (logEnable ) log.debug "Device ${device.displayName}: Current input control values are: ${settingValueMap}"

	// Find what changed
	settingValueMap.findAll{k, v -> !(v.is( null ))}.each {k, v ->
			Boolean changedValue = ((v as BigInteger) != (parameterValueMap.get(k as Integer) as BigInteger)) 
			if (changedValue) {
				pendingChanges?.put(k as Integer, v as BigInteger)
			} else pendingChanges?.remove(k)
		}

	if (txtEnable) log.info "Device ${device.displayName}: Pending parameter changes are: ${pendingChanges ?: "None"}"
	
	processPendingChanges()
	if (txtEnable) log.info "Device ${device.displayName}: Done updating changed parameters (if any) . . ."

}

void processPendingChanges()
{
	if (txtEnable) log.info "Device ${device.displayName}: Processing Pending parameter changes: ${getPendingChangeMap()}"
		pendingChangeMap?.findAll{k, v -> !(v.is( null ))}.each{ k, v ->
			if (txtEnable) log.info "Updating parameter ${k} to value ${v}"
			setParameter(parameterNumber: k , value: v)
		}
}

void setParameter(parameterNumber, value = null ) {
	if (parameterNumber && ( ! value.is( null) )) {
		setParameter(parameterNumber:parameterNumber, value:value)
	} else if (parameterNumber) {
		advancedZwaveSend( zwave.configurationV1.configurationGet(parameterNumber: parameterNumber))
		hubitat.zwave.Command report = myReportQueue("7006").poll(5, TimeUnit.SECONDS)
		if (logEnable) log.debug "Device ${device.displayName}: Received a parameter configuration report: ${report}."
	}
}

Boolean setParameter(Map params = [parameterNumber: null , value: null ] ){
    if (params.parameterNumber.is( null ) || params.value.is( null ) ) {
		log.warn "Device ${device.displayName}: Can't set parameter ${parameterNumber}, Incomplete parameter list supplied... syntax: setParameter(parameterNumber,size,value), received: setParameter(${parameterNumber}, ${size}, ${value})."
		return false
    } 
	
	String getThis = "${params.parameterNumber}" as String

	Integer PSize = ( deviceInputs.get(getThis)?.size) ?: (deviceInputs.get(params.parameterNumber as Integer)?.size )
	
	if (!PSize) {log.error "Device ${device.displayName}: Could not get parameter size in function setParameter. Defaulting to 1"; PSize = 1}

	advancedZwaveSend(zwave.configurationV1.configurationSet(scaledConfigurationValue: params.value as BigInteger, parameterNumber: params.parameterNumber, size: PSize))
	// The 'get' should not be supervised!
	advancedZwaveSend( zwave.configurationV1.configurationGet(parameterNumber: params.parameterNumber))
	
	// Wait for the report that is returned after the configurationGet, and then update the input controls so they display the updated value.
	Boolean success = myReportQueue("7006").poll(5, TimeUnit.SECONDS) ? true : false 

}

// Gets a map of all the values currently stored in the input controls.
Map<Integer, BigInteger> getParameterValuesFromInputControls()
{
	ConcurrentHashMap inputs = getDeviceInputs()
	
	if (!inputs) return
	
	Map<Integer, BigInteger> settingValues = [:]
	
	inputs.each 
		{ PKey , PData -> 
			BigInteger newValue = 0
			// if the setting returns an array, then it is a bitmap control, and add together the values.
			
			if (settings.get(PData.name as String) instanceof ArrayList) {
				settings.get(PData.name as String).each{ newValue += it as BigInteger }
			} else  {   
				newValue = settings.get(PData.name as String) as BigInteger  
			}
			settingValues.put(PKey, newValue)
		}
	if (txtEnable) log.info "Device ${device.displayName}: Current Parameter Setting Values are: " + settingValues
	return settingValues
}

@Field static  ConcurrentHashMap<String, ConcurrentHashMap> allPendingParameterChanges = new ConcurrentHashMap<String, ConcurrentHashMap>(128, 0.75, 1)
@Field static  ConcurrentHashMap<String, ConcurrentHashMap> allDevicesParameterValues = new ConcurrentHashMap<String, ConcurrentHashMap>(128, 0.75, 1)

ConcurrentHashMap getPendingChangeMap() {
	return  allPendingParameterChanges.get(device.deviceNetworkId, new ConcurrentHashMap(32, 0.75, 1) )
}

Map<Integer, BigInteger> getParameterValuesFromDevice()
{
	ConcurrentHashMap parameterValues = allDevicesParameterValues.get(device.deviceNetworkId, new ConcurrentHashMap<Integer, BigInteger>(32, 0.75, 1))
	
	ConcurrentHashMap inputs = getDeviceInputs()	
	
	log.debug "In function getParameterValuesFromDevice, parameter values are: ${parameterValues}. Size is: ${parameterValues.size()}. Inputs size is ${inputs.size()}."
	
	if (!inputs) return null

	if ((parameterValues?.size() as Integer) == (inputs?.size() as Integer) ) 
	{
		// if (logEnable) log.debug "Device ${device.displayName}: In Function getParameterValuesFromDevice, returning Previously retrieved Parameter values: ${parameterValues}"

		return parameterValues
	} else {
		// if (logEnable) log.debug "Getting missing parameter values"
		Integer waitTime = 1
		inputs.eachWithIndex 
			{ k, v, i ->
				if (parameterValues.get(k as Integer).is( null ) ) {
					if (txtEnable) log.info "Device ${device.displayName}: Obtaining value from Zwave device for parameter # ${k}"
					advancedZwaveSend(zwave.configurationV2.configurationGet(parameterNumber: k))
						// Wait 2 second for most of the reports, but wait up to 10 seconds for the last one.
						waitTime = (i >= (inputs.size() -1 )) ? 10 : 2
						myReportQueue("7006").poll(waitTime, TimeUnit.SECONDS)

				} else {
					if (logEnable) log.debug "Device ${device.displayName}: For parameter: ${k} previously retrieved a value of ${parameterValues.get(k as Integer)}."
				}
			}
		return parameterValues			
	}
	return null
}

void zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationReport  cmd)
{ 
	if (logEnable) log.debug "Device ${device.displayName}: Received a configuration report ${cmd}."
	ConcurrentHashMap parameterValues = allDevicesParameterValues.get(device.deviceNetworkId, new ConcurrentHashMap<Integer, BigInteger>(32, 0.75, 1))
	BigInteger newValue = (cmd.size == 1) ? cmd.configurationValue[0] : cmd.scaledConfigurationValue			
	if (newValue < 0) log.warn "Device ${device.displayName}: Negative configuration value reported for configuration parameter ${cmd.parameterNumber}."
				
	parameterValues.put((cmd.parameterNumber as Integer), newValue )
	
	pendingChangeMap.remove(cmd.parameterNumber as Integer)
	
	if (txtEnable) log.info "Device ${device.displayName}: updating parameter: ${cmd.parameterNumber} to ${newValue}."
	device.updateSetting("${cmd.parameterNumber}", newValue as Integer)
		
	myReportQueue(cmd.CMD).offer( cmd )
}

//////////////////////////////////////////////////////////////////////
//////                  Report Queues                          ///////
//////////////////////////////////////////////////////////////////////
// reportQueues stores a map of SynchronousQueues. When requesting a report from a device, the report handler communicates the report back to the requesting function using a queue. This makes programming more like "synchronous" programming, rather than asynchronous handling.
// This is a map within a map. The first level map is by deviceNetworkId. Since @Field static variables are shared among all devices using the same driver code, this ensures that you get a unique second-level map for a particular device. The second map is keyed by the report class hex string. For example, if you want to wait for the configurationGet report, wait for "7006".
@Field static reportQueues = new ConcurrentHashMap<String, ConcurrentHashMap>(128, 0.75, 1)

SynchronousQueue myReportQueue(String reportClass) {
	ConcurrentHashMap thisDeviceQueues = reportQueues.get(device.deviceNetworkId, new ConcurrentHashMap<String,SynchronousQueue>(32, 0.75, 1))
	
	// Get the queue if it exists, create (new) it if it does not.
	SynchronousQueue thisReportQueue = thisDeviceQueues.get(reportClass, new SynchronousQueue())
	return thisReportQueue
}

//////////////////////////////////////////////////////////////////////
//////                  Z-Wave Helper Functions                ///////
//////   Format messages, Send to Device, secure Messages      ///////
//////////////////////////////////////////////////////////////////////

////    Hail   ////
void zwaveEvent(hubitat.zwave.commands.hailv1.Hail cmd) {
	refresh()
}
