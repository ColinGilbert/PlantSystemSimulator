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

public class EmbeddedPlantSystemSimulator implements MqttCallback {

    protected ArduinoProxy proxy = new ArduinoProxy();
    protected boolean stateLoaded = false;
    protected boolean logging = true;
    protected MemoryPersistence persistence;
    protected long deltaTime;
    protected MqttClient client;
    protected MqttConnectOptions connectionOptions;

    boolean started = false;
    double dehumidifyHumidityLossPerMin = 1.0d;
    double dehumidifyHeatPerMin = 0.3d;
    double coolingHeatLossPerMin = 1.0d;
    double lightsOnHeatGainPerMin = 0.1d;
    double lightsOnHumidityGainPerMin = 0.1d;
    double dissipativeHeatLossPerMin = 0.05d;
    int co2InjectionPPMPerSec = 100;
    int lightsOnCO2LossPerMin = 10000;
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
        this(new Random().nextLong());
    }

    EmbeddedPlantSystemSimulator(long uid) {
        proxy = ArduinoProxySaneDefaultsFactory.get();
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
        proxy.getPersistentState().setUid(uid);
        try {
            client = new MqttClient(CommonValues.mqttBrokerURL, MqttClient.generateClientId(), new MemoryPersistence());
            client.setCallback(this);

        } catch (MqttException ex) {
            Logger.getLogger(EmbeddedPlantSystemSimulator.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public void setLogging(boolean arg) {
        logging = arg;
    }

    public void simulationLoop() {
        if (started) {
            final long currentTime = System.currentTimeMillis();
            final long lastRecordedTime = proxy.getTransientState().getTimestamp();
            proxy.getTransientState().setTimestamp(currentTime);
            final long deltaTime = currentTime - lastRecordedTime;
            timeSinceLastUpdatePush += deltaTime;
            proxy.getTransientState().setPowered(true);
            if (timeSinceLastUpdatePush > 100) {
                pushState();
                timeSinceLastUpdatePush = 0;
            }
            // The following sends off random event notifications for display by the app.
            boolean happening = random.nextBoolean();
            lightsCounter -= deltaTime;
            if (lightsCounter < 0) {
                if (happening) {
                    proxy.getTransientState().setLit(true);
                    pushEmbeddedEvent(EmbeddedEventType.LIGHTS_ON);
                } else {
                    proxy.getTransientState().setLit(false);
                    pushEmbeddedEvent(EmbeddedEventType.LIGHTS_OFF);
                }
                lightsCounter = lightsTimer;
            }
            mistingCounter -= deltaTime;
            if (mistingCounter < 0) {
                if (happening) {
                    proxy.getTransientState().setMisting(true);
                    pushEmbeddedEvent(EmbeddedEventType.MIST_ON);
                } else {
                    proxy.getTransientState().setMisting(false);
                    pushEmbeddedEvent(EmbeddedEventType.MIST_OFF);
                }
                mistingCounter = mistingTimer;
            }
            coolingCounter -= deltaTime;
            if (coolingCounter < 0) {
                if (happening) {
                    proxy.getTransientState().setCooling(true);
                    pushEmbeddedEvent(EmbeddedEventType.COOLING_ON);

                } else {
                    proxy.getTransientState().setCooling(false);
                    pushEmbeddedEvent(EmbeddedEventType.COOLING_OFF);
                }
                coolingCounter = coolingTimer;
            }
            dehumidifyCounter -= deltaTime;
            if (dehumidifyCounter < 0) {
                if (happening) {
                    proxy.getTransientState().setDehumidifying(true);
                    pushEmbeddedEvent(EmbeddedEventType.DEHUMIDIFIER_ON);
                } else {
                    proxy.getTransientState().setDehumidifying(false);
                    pushEmbeddedEvent(EmbeddedEventType.DEHUMIDIFIER_OFF);
                }
                dehumidifyCounter = dehumidifyTimer;
            }
            co2Counter -= deltaTime;
            if (co2Counter < 0) {
                if (happening) {
                    proxy.getTransientState().setInjectingCO2(true);
                    pushEmbeddedEvent(EmbeddedEventType.CO2_VALVE_OPEN);
                } else {
                    proxy.getTransientState().setInjectingCO2(false);
                    pushEmbeddedEvent(EmbeddedEventType.CO2_VALVE_CLOSED);
                }
                co2Counter = co2Timer;
            }
            lockedCounter -= deltaTime;
            if (lockedCounter < 0) {
                if (happening) {
                    proxy.getTransientState().setLocked(true);
                    pushEmbeddedEvent(EmbeddedEventType.DOORS_LOCKED);
                } else {
                    proxy.getTransientState().setLocked(false);
                    pushEmbeddedEvent(EmbeddedEventType.DOORS_OPEN);
                }
                lockedCounter = lockedTimer;
            }

            double deltaTemperature = 0.0d;
            double deltaHumidity = 0.0d;
            int deltaCO2 = 0;

            final boolean currentlyPowered = proxy.getTransientState().isPowered();
            final boolean currentlyLit = proxy.getTransientState().isLit();
            final boolean currentlyOpen = proxy.getTransientState().isOpen();
            final boolean currentlyLocked = proxy.getTransientState().isLocked();
            //final boolean currentlyMisting = proxy.getTransientState().isMisting();
            final boolean currentlyDehumidifying = proxy.getTransientState().isDehumidifying();
            final boolean currentlyCooling = proxy.getTransientState().isCooling();
            final boolean currentlyInjectingCO2 = proxy.getTransientState().isInjectingCO2();
            final long timeOfDay = currentTime % CommonValues.millisInDay;

            final boolean lights = shouldTheLightsBeOn(timeOfDay);

   

            if (currentlyPowered) {
                if (currentlyLit) {
                    deltaTemperature += (double) deltaTime * lightsOnHeatGainPerMin / CommonValues.millisInMin;
                    deltaHumidity += (double) deltaTime * lightsOnHumidityGainPerMin / CommonValues.millisInMin;
                    deltaCO2 -= (long) ((double) deltaTime * lightsOnCO2LossPerMin / CommonValues.millisInMin);
                }
                if (currentlyDehumidifying) {
                    deltaHumidity -= (double) deltaTime * dehumidifyHumidityLossPerMin / CommonValues.millisInMin;
                }
                if (currentlyCooling) {
                    deltaTemperature -= (double) deltaTime * coolingHeatLossPerMin / (double) CommonValues.millisInMin;
                }
                if (currentlyInjectingCO2) {
                    deltaCO2 += (long) (double) deltaTime * co2InjectionPPMPerSec / CommonValues.millisInSec;
                }
                if (!currentlyOpen) {
                    if (!currentlyLocked) {
                        long lastRecordedTimeLeftUnlocked = proxy.getTransientState().getTimeLeftUnlocked();
                        long timeLeftUnlocked = lastRecordedTimeLeftUnlocked - deltaTime;
                        if (timeLeftUnlocked < 1) {
                            proxy.getTransientState().setLocked(false);
                            proxy.getTransientState().setTimeLeftUnlocked(0);
                        }
                    }
                }
            }
            deltaTemperature -= (double) deltaTime * dissipativeHeatLossPerMin / CommonValues.millisInMin;
            float lastRecordedTemperature = proxy.getTransientState().getCurrentUpperChamberTemperature();
            float lastRecordedHumidity = proxy.getTransientState().getCurrentUpperChamberHumidity();
            float upperChamberTemperature = Math.min(CommonValues.maxPossibleTemperature, lastRecordedTemperature + (float) deltaTemperature);
            upperChamberTemperature = Math.max(upperChamberTemperature, CommonValues.minPossibleTemperature);
            proxy.getTransientState().setCurrentUpperChamberTemperature(upperChamberTemperature);
            float upperChamberHumidity = Math.min(CommonValues.maxHumidity, lastRecordedHumidity + (float) deltaHumidity);
            upperChamberHumidity = Math.max(upperChamberHumidity, CommonValues.minHumidity);
            proxy.getTransientState().setCurrentUpperChamberHumidity(upperChamberHumidity);
            int lastRecordedCO2Level = Math.min(CommonValues.maxCO2PPM, proxy.getTransientState().getCurrentCO2PPM() + deltaCO2);
            lastRecordedCO2Level = Math.min(lastRecordedCO2Level, CommonValues.maxCO2PPM);
            proxy.getTransientState().setCurrentCO2PPM(lastRecordedCO2Level);
             proxy.getTransientState().setTimeLeftUnlocked(currentTime);
        } else {
            started = true;
        }
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
            publish(TopicStrings.embeddedEvent() + "/" + proxy.getPersistentState().getUid(), 0, message.getBytes());
        } catch (MqttException ex) {
            Logger.getLogger(EmbeddedPlantSystemSimulator.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    protected boolean shouldTheLightsBeOn(long currentTime) {
        final long onTime = proxy.getPersistentState().getLightsOnHour() * CommonValues.millisInHour + proxy.getPersistentState().getLightsOnMinute() * (long) CommonValues.millisInMin;
        final long offTime = proxy.getPersistentState().getLightsOffHour() * CommonValues.millisInHour + proxy.getPersistentState().getLightsOffMinute() * (long) CommonValues.millisInMin;
 
        if (onTime < 0 || offTime < 0) {
            return false;
        }
        boolean results = false;
        if (offTime == onTime) {
            results = true;
        } else if (offTime < onTime) { // The simple, straightforward case.
            results = currentTime > onTime && currentTime < offTime;
        } else if (offTime > onTime) { // A slightly less simple case.
            if (currentTime > onTime) {
                results = true;
            } else if (currentTime > offTime && currentTime > onTime) {
                results = true;
            }
        }
        return results;
    }

    public void connect() {
        connect(CommonValues.mqttBrokerURL);
        subscribeToEmbeddedConfigPush();
    }

    void pushState() {
        proxy.getTransientState().setTimestamp(System.currentTimeMillis());
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
            log("Connected to " + CommonValues.mqttBrokerURL + " with client ID " + client.getClientId());
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
        String topic = TopicStrings.configPushToEmbedded() + "/" + proxy.getPersistentState().getUid();
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

        if (topicArg.equals(TopicStrings.configPushToEmbedded() + "/" + proxy.getPersistentState().getUid())) {
            ObjectMapper objectMapper = new ObjectMapper();
            try {
                ArduinoConfigChangeRepresentation receivedState = objectMapper.readValue(message.toString().getBytes(), ArduinoConfigChangeRepresentation.class);
                if (proxy.getPersistentState().getUid() != receivedState.getPersistentState().getUid()) {
                    log("LOGICAL ERROR: Received invalid UID as configuration. Our UID = " + proxy.getPersistentState().getUid() + ", assigned UID = " + receivedState.getPersistentState().getUid());
                    return;
                }
                if (receivedState.isChangingMistingInterval()) {
                    proxy.getPersistentState().setMistingInterval(receivedState.getPersistentState().getMistingInterval());
                }
                if (receivedState.isChangingMistingDuration()) {
                    proxy.getPersistentState().setMistingDuration(receivedState.getPersistentState().getMistingDuration());
                }
                if (receivedState.isChangingStatusPushInterval()) {
                    proxy.getPersistentState().setStatusPushInterval(receivedState.getPersistentState().getStatusPushInterval());
                }
                if (receivedState.isChangingNutrientsPPM()) {
                    proxy.getPersistentState().setNutrientsPPM(receivedState.getPersistentState().getNutrientsPPM());
                }
                if (receivedState.isChangingNutrientSolutionRatio()) {
                    proxy.getPersistentState().setNutrientSolutionRatio(receivedState.getPersistentState().getNutrientSolutionRatio());
                }
                if (receivedState.isChangingLightsOnHour()) {
                    proxy.getPersistentState().setLightsOnHour(receivedState.getPersistentState().getLightsOnHour());
                }
                if (receivedState.isChangingLightsOffHour()) {
                    proxy.getPersistentState().setLightsOffHour(receivedState.getPersistentState().getLightsOffHour());
                }
                if (receivedState.isChangingLightsOnMinute()) {
                    proxy.getPersistentState().setLightsOnMinute(receivedState.getPersistentState().getLightsOnMinute());
                }
                if (receivedState.isChangingLightsOffMinute()) {
                    proxy.getPersistentState().setLightsOffMinute(receivedState.getPersistentState().getLightsOffMinute());
                }
                if (receivedState.isChangingTargetUpperChamberHumidity()) {
                    proxy.getPersistentState().setTargetUpperChamberHumidity(receivedState.getPersistentState().getTargetUpperChamberHumidity());
                }
                if (receivedState.isChangingTargetUpperChamberTemperature()) {
                    proxy.getPersistentState().setTargetUpperChamberTemperature(receivedState.getPersistentState().getTargetUpperChamberTemperature());
                }
                if (receivedState.isChangingTargetLowerChamberTemperature()) {
                    proxy.getPersistentState().setTargetLowerChamberTemperature(receivedState.getPersistentState().getTargetLowerChamberTemperature());
                }
                if (receivedState.isChangingTargetCO2PPM()) {
                    proxy.getPersistentState().setTargetCO2PPM(receivedState.getPersistentState().getTargetCO2PPM());
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
