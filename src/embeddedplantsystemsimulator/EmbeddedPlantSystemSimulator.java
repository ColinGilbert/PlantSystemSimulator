/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package embeddedplantsystemsimulator;

import noob.plantsystem.common.EmbeddedEventType;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.lang.Math;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.json.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.fasterxml.jackson.core.TokenStreamFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import noob.plantsystem.common.*;

public class EmbeddedPlantSystemSimulator implements MqttCallback {

    protected PersistentArduinoState persistedState;
    protected TransientArduinoState transientState;
    protected boolean persistentStateLoaded = false;
    protected boolean logging = true;
    protected String brokerURL = "tcp://127.0.0.1:1883";
    protected MemoryPersistence persistence;
    protected long deltaTime;
    protected MqttClient client;
    protected MqttConnectOptions connectionOptions;

    boolean started = false;

    float maxTemperature = 60.0f;
    float minTemperature = 10.0f;

    double dehumidifyHumidityLossPerMin = 1.0d;
    double dehumidifyHeatPerMin = 0.3d;
    double coolingHeatLossPerMin = 1.0d;
    double lightsOnHeatGainPerMin = 0.1d;
    double lightsOnHumidityGainPerMin = 0.1d;
    double dissipativeHeatLossPerMin = 0.05d;
    int co2InjectionPPMPerSec = 1000;
    int lightsOnCO2LossPerMin = 1000;
    long lastRecordedTime;
    
    long timeSinceLastMisting = 0;
    long currentMistingDuration = 0;
    long timeSinceLastUpdatePush = 0;
    
    EmbeddedPlantSystemSimulator(long uidArg) {

        ArduinoProxy proxy = ArduinoProxySaneDefaultsFactory.get();
        transientState = proxy.extractTransientState();
        persistedState = proxy.extractPersistentState();
        transientState.setUpperChamberTemperature(23.0f);
        transientState.setLowerChamberTemperature(18.0f);
        transientState.setUpperChamberHumidity(50.0f);
        persistedState.setUid(uidArg);
        connectionOptions = new MqttConnectOptions();
        connectionOptions.setCleanSession(true);
    }

    void init() {
        try {
            client = new MqttClient(brokerURL, Long.toString(persistedState.getUid()), new MemoryPersistence());
            client.setCallback(this);

        } catch (MqttException ex) {
            Logger.getLogger(EmbeddedPlantSystemSimulator.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public void setLogging(boolean arg) {
        logging = arg;
    }

    // This simulates the values creeping towards their respective equilibria.
    public void simulationLoop() {

        final long currentTime = System.currentTimeMillis();
        final long deltaTime = currentTime - lastRecordedTime;
        lastRecordedTime = currentTime;
        timeSinceLastUpdatePush += deltaTime;

        if (timeSinceLastUpdatePush > persistedState.getStatusUpdatePushInterval()) {
            pushTransientState();
            timeSinceLastUpdatePush = 0;
        }
        if (transientState.isMisting()) {
            
        }
        
        double deltaTemperature = 0.0d;
        double deltaHumidity = 0.0d;
        int deltaCO2 = 0;
        final boolean currentlyPowered = true;//transientState.isPowered();
        final boolean currentlyLit = transientState.isLit();
        final boolean currentlyOpen = false; //transientState.isOpen();
        final boolean currentlyLocked = true; //transientState.isLocked();
        final boolean currentlyDehumidifying = transientState.isDehumidifying();
        final boolean currentlyCooling = transientState.isCooling();
        final boolean currentlyInjectingCO2 = transientState.isInjectingCO2();

        final double MILLIS_IN_SEC = 1000.0d;
        final double MILLIS_IN_MIN = 60000.0d;
        if (currentlyPowered) {
            if (currentlyLit) {
                deltaTemperature += (double) deltaTime * lightsOnHeatGainPerMin / MILLIS_IN_MIN;
                deltaHumidity += (double) deltaTime * lightsOnHumidityGainPerMin / MILLIS_IN_MIN;
                deltaCO2 -= (long) ((double) deltaTime * lightsOnCO2LossPerMin / MILLIS_IN_MIN);
            }
            if (currentlyDehumidifying) {
                deltaHumidity -= (double) deltaTime * dehumidifyHumidityLossPerMin / MILLIS_IN_MIN;
            }
            if (currentlyCooling) {
                deltaTemperature -= (double) deltaTime * coolingHeatLossPerMin / MILLIS_IN_MIN;
            }
            if (currentlyInjectingCO2) {
                deltaCO2 += (long) (double) deltaTime * co2InjectionPPMPerSec / MILLIS_IN_SEC;
            }
            if (!currentlyOpen) {
                if (!currentlyLocked) {
                    long lastRecordedTimeLeftUnlocked = transientState.getTimeLeftUnlocked();
                    long timeLeftUnlocked = lastRecordedTimeLeftUnlocked - deltaTime;
                    if (timeLeftUnlocked < 1) {
                        transientState.setLocked(false);
                        transientState.setTimeLeftUnlocked(0);
                    }
                }
            }
        }
        deltaTemperature -= (double) deltaTime * dissipativeHeatLossPerMin / MILLIS_IN_MIN;
        int lastRecordedCO2Level = transientState.getCurrentCO2PPM();
        double lastRecordedTemperature = (double) transientState.getUpperChamberTemperature();
        double lastRecordedHumidity = (double) transientState.getUpperChamberHumidity();
        transientState.setCurrentCO2PPM(Math.max(0, lastRecordedCO2Level + deltaCO2));
        float upperChamberTemperature = Math.min(maxTemperature, Math.max((float) (lastRecordedTemperature + deltaTemperature), minTemperature));
        transientState.setUpperChamberTemperature(upperChamberTemperature);
        float upperChamberHumidity = Math.min(100.f, Math.max((float) (lastRecordedHumidity + deltaHumidity), 0.f));
        transientState.setUpperChamberHumidity(upperChamberHumidity);

        if (transientState.getCurrentCO2PPM() > persistedState.getTargetCO2PPM()) {
            transientState.setInjectingCO2(false);
        } else {
            transientState.setInjectingCO2(true);
        }
        if (transientState.getUpperChamberHumidity() > persistedState.getTargetUpperChamberHumidity()) {
            transientState.setDehumidifying(true);
        } else {
            transientState.setDehumidifying(false);
        }
        if (transientState.getUpperChamberTemperature() > persistedState.getTargetUpperChamberTemperature()) {
            transientState.setCooling(true);
        } else {
            transientState.setCooling(false);
        }

        final long timeOfDay = currentTime % 86400000; // There are that many milliseconds in a day!
        final boolean lights = shouldTheLightsBeOn(timeOfDay);
        if (lights && !currentlyLit) {
            transientState.setLit(lights);
            EmbeddedEventType ev = EmbeddedEventType.LIGHTS_ON;
            pushEmbeddedEvent(ev);
        } else if (!lights && currentlyLit) {
            transientState.setLit(lights);
            EmbeddedEventType ev = EmbeddedEventType.LIGHTS_OFF;
            pushEmbeddedEvent(ev);
        }
        transientState.setLit(lights);

        if (transientState.isDehumidifying() && !currentlyDehumidifying) {
            EmbeddedEventType ev = EmbeddedEventType.DEHUMIDIFIER_ON;
            pushEmbeddedEvent(ev);
        } else if (!transientState.isDehumidifying() && transientState.isDehumidifying()) {
            EmbeddedEventType ev = EmbeddedEventType.DEHUMIDIFIER_OFF;
            pushEmbeddedEvent(ev);
        }
        if (transientState.isCooling() && !currentlyCooling) {
            EmbeddedEventType ev = EmbeddedEventType.COOLING_ON;
            pushEmbeddedEvent(ev);
        } else if (!transientState.isCooling() && currentlyCooling) {
            EmbeddedEventType ev = EmbeddedEventType.COOLING_OFF;
            pushEmbeddedEvent(ev);           
        }
        if (transientState.isInjectingCO2() && !currentlyInjectingCO2) {
            EmbeddedEventType ev = EmbeddedEventType.CO2_VALVE_OPEN;
            pushEmbeddedEvent(ev);   
        } else if (!transientState.isInjectingCO2() && currentlyInjectingCO2) {
            EmbeddedEventType ev = EmbeddedEventType.CO2_VALVE_CLOSED;
            pushEmbeddedEvent(ev);            
        }

        // TODO: Lights code 
        lastRecordedTime = currentTime;
    }

 
    protected void pushEmbeddedEvent(EmbeddedEventType ev) {
        ObjectMapper mapper = new ObjectMapper();
        String message = "";
        try {
            message = mapper.writeValueAsString(new Integer(ev.ordinal()));
        } catch (JsonProcessingException ex) {
            Logger.getLogger(EmbeddedPlantSystemSimulator.class.getName()).log(Level.SEVERE, null, ex);
            return;
        }
        try {
            publish(TopicStrings.embeddedEvent() + "/" + persistedState.getUid(), 2, message.getBytes());
        } catch (MqttException ex) {
            Logger.getLogger(EmbeddedPlantSystemSimulator.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    protected boolean shouldTheLightsBeOn(long currentTime) {
        final long onTime = persistedState.getLightsOnTime();
        final long offTime = persistedState.getLightsOffTime();
        if (onTime < 0 || offTime < 0) {
            return false;
        }
        boolean results = false;
        if (offTime == onTime) {
            results = true;
        } else if (offTime < onTime) { // The simple, straightforward case.
            results = currentTime > onTime && currentTime < offTime;
        } else if (offTime > onTime) {
            if (currentTime > onTime) {
                results = true;
            } else if (currentTime > offTime && currentTime > onTime) {
                results = true;
            }
        }
        return results;
    }

    public void connect() {
        connect(brokerURL);
        subscribeToEmbeddedConfigPush();
    }

    void pushTransientState() {
        EmbeddedStatusReport state = new EmbeddedStatusReport();
        state.setUid(persistedState.getUid());
        state.setTimestamp(System.currentTimeMillis());
        state.updateTransientState(transientState);
        ObjectMapper objMapper = new ObjectMapper();
        String message = "";
        try {
            message = objMapper.writeValueAsString(state);
        } catch (JsonProcessingException ex) {
            Logger.getLogger(EmbeddedPlantSystemSimulator.class.getName()).log(Level.SEVERE, null, ex);
            return;
        }
        try {
            publish(TopicStrings.embeddedTransientStatePush() + "/" + persistedState.getUid(), 2, message.getBytes());
        } catch (MqttException ex) {
            Logger.getLogger(EmbeddedPlantSystemSimulator.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    protected void connect(String brokers) {

        // Connect to the MQTT server
        try {
            client.connect(connectionOptions);
            log("Connected to " + brokerURL + " with client ID " + client.getClientId());
        } catch (MqttSecurityException ex) {
            Logger.getLogger(EmbeddedPlantSystemSimulator.class.getName()).log(Level.SEVERE, null, ex);
            return;
        } catch (MqttException ex) {
            Logger.getLogger(EmbeddedPlantSystemSimulator.class.getName()).log(Level.SEVERE, null, ex);
            return;
        }
        //  try {
        //     client.disconnect();
        //  } catch (MqttException ex) {
        //     Logger.getLogger(EmbeddedPlantSystemSimulator.class.getName()).log(Level.SEVERE, null, ex);
        //     return;
        // }
    }

    protected void publish(String topicName, int qos, byte[] payload) throws MqttException {
        // Create and configure a message
        MqttMessage message = new MqttMessage(payload);
        message.setQos(qos);
        message.setRetained(false);
        client.publish(topicName, message);
        //client.disconnect();
        log("Message published! Topic = " + topicName + ", qos = " + qos + " Payload: " + payload);
    }

    protected void subscribeToEmbeddedConfigPush() {
        String topic = TopicStrings.configPushToEmbedded() + "/" + persistedState.getUid();
        try {
            client.subscribe(topic);
        } catch (MqttException ex) {
            Logger.getLogger(EmbeddedPlantSystemSimulator.class.getName()).log(Level.SEVERE, null, ex);
            return;
        }
        log("Subscribed to: " + topic);

    }

    // MQTT callbacks
    @Override
    public void connectionLost(Throwable cause) {
        log("Connection lost... Cause:" + cause);
        // TODO: Implement reconnect?
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
      //  log("MQTT message delivered! " + token);
    }

    @Override
    public void messageArrived(String topicArg, MqttMessage message) throws MqttException {
       //  log("MQTT message received. Topic = " + topicArg + ", message = " + message);

        if (topicArg.equals(TopicStrings.configPushToEmbedded() + "/" + persistedState.getUid())) {
            ObjectMapper objectMapper = new ObjectMapper();
            try {
                // Code wart. Replace with a data-driven approach in version 2.
                ArduinoConfigChangeRepresentation receivedState = objectMapper.readValue(message.toString().getBytes(), ArduinoConfigChangeRepresentation.class);
                if (persistedState.getUid() != receivedState.getUid()) {
                    log("LOGICAL ERROR: Received invalid UID as configuration. Our UID = " + persistedState.getUid() + ", assigned UID = " + receivedState.getUid());
                    return;
                }
                if (receivedState.isChangingMistingInterval()) {
                    persistedState.setMistingInterval(receivedState.getMistingInterval());
                }
                if (receivedState.isChangingMistingDuration()) {
                    persistedState.setMistingDuration(receivedState.getMistingDuration());
                }
                if (receivedState.isChangingStatusUpdatePushInterval()) {
                    persistedState.setStatusUpdatePushInterval(receivedState.getStatusUpdatePushInterval());
                }
                if (receivedState.isChangingNutrientsPPM()) {
                    persistedState.setNutrientsPPM(receivedState.getNutrientsPPM());
                }
                if (receivedState.isChangingNutrientSolutionRatio()) {
                    persistedState.setNutrientSolutionRatio(receivedState.getNutrientSolutionRatio());
                }
                if (receivedState.isChangingLightsOnTime()) {
                    persistedState.setLightsOnTime(receivedState.getLightsOnTime());
                }
                if (receivedState.isChangingLightsOffTime()) {
                    persistedState.setLightsOffTime(receivedState.getLightsOffTime());
                }
                if (receivedState.isChangingTargetUpperChamberHumidity()) {
                    persistedState.setTargetUpperChamberHumidity(receivedState.getTargetUpperChamberHumidity());
                }
                if (receivedState.isChangingTargetUpperChamberTemperature()) {
                    persistedState.setTargetUpperChamberTemperature(receivedState.getTargetUpperChamberTemperature());
                }
                if (receivedState.isChangingTargetLowerChamberTemperature()) {
                    persistedState.setTargetLowerChamberTemperature(receivedState.getTargetLowerChamberTemperature());
                }
                if (receivedState.isChangingTargetCO2PPM()) {
                    persistedState.setTargetCO2PPM(receivedState.getTargetCO2PPM());
                }
                if (!started) {
                    started = true;
                }
                System.out.println("Got state push: " + message.toString());
            } catch (IOException ex) {
                Logger.getLogger(EmbeddedPlantSystemSimulator.class.getName()).log(Level.SEVERE, null, ex);
                return;
            }
        } else {
            log("Received unsubscribed MQTT topic");

        }
    }

    // End of MQTT callbacks
    // Internals
    private void log(String arg) {
        if (logging) {
            System.out.println(arg);
        }
    }
}