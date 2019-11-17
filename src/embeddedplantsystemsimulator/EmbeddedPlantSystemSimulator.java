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
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import noob.plantsystem.common.*;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;

public class EmbeddedPlantSystemSimulator implements MqttCallback {

    protected ArduinoProxy proxy = new ArduinoProxy();
    protected boolean stateLoaded = false;
    protected boolean logging = true;
    final protected String brokerURL = "tcp://127.0.0.1:1883";
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

    final long lightsTimer = 15000;
    final long mistingTimer = 3000;
    final long coolingTimer = 7000;
    final long dehumidifyTimer = 4000;
    final long co2Timer = 6000;
    final long lockedTimer = 9000;

    EmbeddedPlantSystemSimulator() {

        proxy = ArduinoProxySaneDefaultsFactory.get();

        proxy.setCurrentUpperChamberTemperature(23.0f);
        proxy.setCurrentLowerChamberTemperature(18.0f);
        proxy.setCurrentUpperChamberHumidity(50.0f);

        connectionOptions = new MqttConnectOptions();
        connectionOptions.setCleanSession(true);
    }

    long lightsCounter = lightsTimer;
    long mistingCounter = mistingTimer;
    long coolingCounter = coolingTimer;
    long dehumidifyCounter = dehumidifyTimer;
    long co2Counter = co2Timer;
    long lockedCounter = lockedTimer;

    Random random = new Random();

    void init(long uid) {
        proxy.setUid(uid);
        try {
            client = new MqttClient(brokerURL, MqttClient.generateClientId(), new MemoryPersistence());
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

        if (timeSinceLastUpdatePush > 100) {
            pushState();
            timeSinceLastUpdatePush = 0;
        }
        boolean happening = random.nextBoolean();
        ArduinoEvent ev = new ArduinoEvent();

        lightsCounter -= deltaTime;
        if (lightsCounter < 0) {
            if (happening) {
                proxy.setLit(true);
                pushEmbeddedEvent(EmbeddedEventType.LIGHTS_ON);
            } else {
                proxy.setLit(false);
                pushEmbeddedEvent(EmbeddedEventType.LIGHTS_OFF);
            }
            lightsCounter = lightsTimer;
        }
        mistingCounter -= deltaTime;
        if (mistingCounter < 0) {
            if (happening) {
                proxy.setMisting(true);
                pushEmbeddedEvent(EmbeddedEventType.MIST_ON);
            } else {
                proxy.setMisting(false);
                pushEmbeddedEvent(EmbeddedEventType.MIST_OFF);
            }
            mistingCounter = mistingTimer;
        }
        coolingCounter -= deltaTime;
        if (coolingCounter < 0) {
            if (happening) {
                proxy.setCooling(true);
                pushEmbeddedEvent(EmbeddedEventType.COOLING_ON);

            } else {
                proxy.setCooling(false);
                pushEmbeddedEvent(EmbeddedEventType.COOLING_OFF);
            }
            coolingCounter = coolingTimer;
        }
        dehumidifyCounter -= deltaTime;
        if (dehumidifyCounter < 0) {
            if (happening) {
                proxy.setDehumidifying(true);
                pushEmbeddedEvent(EmbeddedEventType.DEHUMIDIFIER_ON);
            } else {
                proxy.setDehumidifying(false);
                pushEmbeddedEvent(EmbeddedEventType.DEHUMIDIFIER_OFF);
            }
            dehumidifyCounter = dehumidifyTimer;
        }
        co2Counter -= deltaTime;
        if (co2Counter < 0) {
            if (happening) {
                proxy.setInjectingCO2(true);
                pushEmbeddedEvent(EmbeddedEventType.CO2_VALVE_OPEN);
            } else {
                proxy.setInjectingCO2(false);
                pushEmbeddedEvent(EmbeddedEventType.CO2_VALVE_CLOSED);
            }
            co2Counter = co2Timer;
        }
        lockedCounter -= deltaTime;
        if (lockedCounter < 0) {
            if (happening) {
                proxy.setLocked(true);
                pushEmbeddedEvent(EmbeddedEventType.DOORS_LOCKED);
            } else {
                proxy.setLocked(false);
                pushEmbeddedEvent(EmbeddedEventType.DOORS_OPEN);
            }
            lockedCounter = lockedTimer;
        }

        double deltaTemperature = 0.0d;
        double deltaHumidity = 0.0d;
        int deltaCO2 = 0;

        final boolean currentlyPowered = proxy.isPowered();
        final boolean currentlyLit = proxy.isLit();
        final boolean currentlyOpen = proxy.isOpen();
        final boolean currentlyLocked = proxy.isLocked();
        // final boolean currentlyMisting = proxy.isMisting();
        final boolean currentlyDehumidifying = proxy.isDehumidifying();
        final boolean currentlyCooling = proxy.isCooling();
        final boolean currentlyInjectingCO2 = proxy.isInjectingCO2();

        final double MILLIS_IN_SEC = 1000.0d;
        final double MILLIS_IN_MIN = 60000.0d;
        final long MILLIS_IN_DAY = 86400000;

        final long timeOfDay = currentTime % MILLIS_IN_DAY; // There are that many milliseconds in a day!
        final boolean lights = shouldTheLightsBeOn(timeOfDay);

        proxy.setLit(lights);

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
                    long lastRecordedTimeLeftUnlocked = proxy.getTimeLeftUnlocked();
                    long timeLeftUnlocked = lastRecordedTimeLeftUnlocked - deltaTime;
                    if (timeLeftUnlocked < 1) {
                        proxy.setLocked(false);
                        proxy.setTimeLeftUnlocked(0);
                    }
                }
            }
        }
        deltaTemperature -= (double) deltaTime * dissipativeHeatLossPerMin / MILLIS_IN_MIN;
        int lastRecordedCO2Level = proxy.getCurrentCO2PPM();
        double lastRecordedTemperature = (double) proxy.getCurrentUpperChamberTemperature();
        double lastRecordedHumidity = (double) proxy.getCurrentUpperChamberHumidity();
        proxy.setCurrentCO2PPM(Math.max(0, lastRecordedCO2Level + deltaCO2));
        float upperChamberTemperature = Math.min(maxTemperature, Math.max((float) (lastRecordedTemperature + deltaTemperature), minTemperature));
        proxy.setCurrentUpperChamberTemperature(upperChamberTemperature);
        float upperChamberHumidity = Math.min(100.f, Math.max((float) (lastRecordedHumidity + deltaHumidity), 0.f));
        proxy.setCurrentUpperChamberHumidity(upperChamberHumidity);

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
            publish(TopicStrings.embeddedEvent() + "/" + proxy.getUid(), 0, message.getBytes());
        } catch (MqttException ex) {
            Logger.getLogger(EmbeddedPlantSystemSimulator.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    protected boolean shouldTheLightsBeOn(long currentTime) {
        final long onTime = proxy.getLightsOnTime();
        final long offTime = proxy.getLightsOffTime();
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

    void pushState() {
        proxy.setTimestamp(System.currentTimeMillis());
        ObjectMapper objMapper = new ObjectMapper();
        String message = "";
        try {
            message = objMapper.writeValueAsString(proxy);
        } catch (JsonProcessingException ex) {
            Logger.getLogger(EmbeddedPlantSystemSimulator.class.getName()).log(Level.SEVERE, null, ex);
            return;
        }
        try {
            publish(TopicStrings.embeddedStatePush(), 0, message.getBytes());
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
        } catch (MqttException ex) {
            Logger.getLogger(EmbeddedPlantSystemSimulator.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    protected void publish(String topicName, int qos, byte[] payload) throws MqttException {
        // Create and configure a message
        MqttMessage message = new MqttMessage(payload);
        message.setQos(qos);
        message.setRetained(false);
        client.publish(topicName, message);
    }

    protected void subscribeToEmbeddedConfigPush() {
        String topic = TopicStrings.configPushToEmbedded() + "/" + proxy.getUid();
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

        if (topicArg.equals(TopicStrings.configPushToEmbedded() + "/" + proxy.getUid())) {
            ObjectMapper objectMapper = new ObjectMapper();
            try {
                // Code wart. Replace with a data-driven approach in version 2.
                ArduinoConfigChangeRepresentation receivedState = objectMapper.readValue(message.toString().getBytes(), ArduinoConfigChangeRepresentation.class);
                if (proxy.getUid() != receivedState.getUid()) {
                    log("LOGICAL ERROR: Received invalid UID as configuration. Our UID = " + proxy.getUid() + ", assigned UID = " + receivedState.getUid());
                    return;
                }
                if (receivedState.isChangingMistingInterval()) {
                    proxy.setMistingInterval(receivedState.getMistingInterval());
                }
                if (receivedState.isChangingMistingDuration()) {
                    proxy.setMistingDuration(receivedState.getMistingDuration());
                }
                if (receivedState.isChangingStatusPushInterval()) {
                    proxy.setStatusPushInterval(receivedState.getStatusPushInterval());
                }
                if (receivedState.isChangingNutrientsPPM()) {
                    proxy.setNutrientsPPM(receivedState.getNutrientsPPM());
                }
                if (receivedState.isChangingNutrientSolutionRatio()) {
                    proxy.setNutrientSolutionRatio(receivedState.getNutrientSolutionRatio());
                }
                if (receivedState.isChangingLightsOnTime()) {
                    proxy.setLightsOnTime(receivedState.getLightsOnTime());
                }
                if (receivedState.isChangingLightsOffTime()) {
                    proxy.setLightsOffTime(receivedState.getLightsOffTime());
                }
                if (receivedState.isChangingTargetUpperChamberHumidity()) {
                    proxy.setTargetUpperChamberHumidity(receivedState.getTargetUpperChamberHumidity());
                }
                if (receivedState.isChangingTargetUpperChamberTemperature()) {
                    proxy.setTargetUpperChamberTemperature(receivedState.getTargetUpperChamberTemperature());
                }
                if (receivedState.isChangingTargetLowerChamberTemperature()) {
                    proxy.setTargetLowerChamberTemperature(receivedState.getTargetLowerChamberTemperature());
                }
                if (receivedState.isChangingTargetCO2PPM()) {
                    proxy.setTargetCO2PPM(receivedState.getTargetCO2PPM());
                }
                if (!started) {
                    started = true;
                }
                System.out.println("Got updated config : " + message.toString());
                // pushState();
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
