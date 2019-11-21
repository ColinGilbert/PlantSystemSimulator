/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package embeddedplantsystemsimulator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import noob.plantsystem.common.*;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class EmbeddedPlantSystemSimulator implements MqttCallback {

    protected EmbeddedSystemCombinedStateMemento systemState = new EmbeddedSystemCombinedStateMemento();
    protected MemoryPersistence persistence;
    protected MqttClient client;
    protected MqttConnectOptions connectionOptions;
    protected boolean started = false;
    protected double dehumidifyHumidityLossPerMin = 1.0d;
    protected double dehumidifyHeatPerMin = 0.3d;
    protected double coolingHeatLossPerMin = 1.0d;
    protected double lightsOnHeatGainPerMin = 0.1d;
    protected double lightsOnHumidityGainPerMin = 0.1d;
    protected double dissipativeHeatLossPerMin = 0.05d;
    protected int co2InjectionPPMPerSec = 100;
    protected int lightsOnCO2LossPerMin = 10000;
    protected long timeSinceLastMisting = 0;
    protected long currentMistingDuration = 0;
    protected long timeSinceLastUpdatePush = 0;
    protected final long lightsTimer = 15000;
    protected final long mistingTimer = 3000;
    protected final long coolingTimer = 7000;
    protected final long dehumidifyTimer = 4000;
    protected final long co2Timer = 6000;
    protected final long lockedTimer = 9000;
    protected long lightsCounter = lightsTimer;
    protected long mistingCounter = mistingTimer;
    protected long coolingCounter = coolingTimer;
    protected long dehumidifyCounter = dehumidifyTimer;
    protected long co2Counter = co2Timer;
    protected long lockedCounter = lockedTimer;
    protected long deltaTime;
    protected boolean stateLoaded = false;
    protected boolean logging = true;
    protected Random random = new Random();

    EmbeddedPlantSystemSimulator() {
        this(new Random().nextLong());
    }

    EmbeddedPlantSystemSimulator(long uid) {
        systemState = EmbeddedSystemStateSaneDefaultsFactory.get();
        connectionOptions = new MqttConnectOptions();
        connectionOptions.setCleanSession(true);
    }

    void init(long uid) {
        systemState.getPersistentState().setUid(uid);
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
            final long lastRecordedTime = systemState.getTransientState().getTimestamp();
            systemState.getTransientState().setTimestamp(currentTime);
            final long deltaTime = currentTime - lastRecordedTime;
            timeSinceLastUpdatePush += deltaTime;
            systemState.getTransientState().setPowered(true);
            if (timeSinceLastUpdatePush > 100) {
                pushState();
                timeSinceLastUpdatePush = 0;
            }
            // The following sends off random event notifications for display by the app.
            boolean happening = random.nextBoolean();
            lightsCounter -= deltaTime;
            if (lightsCounter < 0) {
                if (happening) {
                    systemState.getTransientState().setLit(true);
                    pushEmbeddedEvent(EmbeddedSystemEventType.LIGHTS_ON);
                } else {
                    systemState.getTransientState().setLit(false);
                    pushEmbeddedEvent(EmbeddedSystemEventType.LIGHTS_OFF);
                }
                lightsCounter = lightsTimer;
            }
            mistingCounter -= deltaTime;
            if (mistingCounter < 0) {
                if (happening) {
                    systemState.getTransientState().setMisting(true);
                    pushEmbeddedEvent(EmbeddedSystemEventType.MIST_ON);
                } else {
                    systemState.getTransientState().setMisting(false);
                    pushEmbeddedEvent(EmbeddedSystemEventType.MIST_OFF);
                }
                mistingCounter = mistingTimer;
            }
            coolingCounter -= deltaTime;
            if (coolingCounter < 0) {
                if (happening) {
                    systemState.getTransientState().setCooling(true);
                    pushEmbeddedEvent(EmbeddedSystemEventType.COOLING_ON);

                } else {
                    systemState.getTransientState().setCooling(false);
                    pushEmbeddedEvent(EmbeddedSystemEventType.COOLING_OFF);
                }
                coolingCounter = coolingTimer;
            }
            dehumidifyCounter -= deltaTime;
            if (dehumidifyCounter < 0) {
                if (happening) {
                    systemState.getTransientState().setDehumidifying(true);
                    pushEmbeddedEvent(EmbeddedSystemEventType.DEHUMIDIFIER_ON);
                } else {
                    systemState.getTransientState().setDehumidifying(false);
                    pushEmbeddedEvent(EmbeddedSystemEventType.DEHUMIDIFIER_OFF);
                }
                dehumidifyCounter = dehumidifyTimer;
            }
            co2Counter -= deltaTime;
            if (co2Counter < 0) {
                if (happening) {
                    systemState.getTransientState().setInjectingCO2(true);
                    pushEmbeddedEvent(EmbeddedSystemEventType.CO2_VALVE_OPEN);
                } else {
                    systemState.getTransientState().setInjectingCO2(false);
                    pushEmbeddedEvent(EmbeddedSystemEventType.CO2_VALVE_CLOSED);
                }
                co2Counter = co2Timer;
            }
            lockedCounter -= deltaTime;
            if (lockedCounter < 0) {
                if (happening) {
                    systemState.getTransientState().setLocked(true);
                    pushEmbeddedEvent(EmbeddedSystemEventType.DOORS_LOCKED);
                } else {
                    systemState.getTransientState().setLocked(false);
                    pushEmbeddedEvent(EmbeddedSystemEventType.DOORS_OPEN);
                }
                lockedCounter = lockedTimer;
            }

            double deltaTemperature = 0.0d;
            double deltaHumidity = 0.0d;
            int deltaCO2 = 0;

            final boolean currentlyPowered = systemState.getTransientState().isPowered();
            final boolean currentlyLit = systemState.getTransientState().isLit();
            final boolean currentlyOpen = systemState.getTransientState().isOpen();
            final boolean currentlyLocked = systemState.getTransientState().isLocked();
            //final boolean currentlyMisting = systemState.getTransientState().isMisting();
            final boolean currentlyDehumidifying = systemState.getTransientState().isDehumidifying();
            final boolean currentlyCooling = systemState.getTransientState().isCooling();
            final boolean currentlyInjectingCO2 = systemState.getTransientState().isInjectingCO2();
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
                        long lastRecordedTimeLeftUnlocked = systemState.getTransientState().getTimeLeftUnlocked();
                        long timeLeftUnlocked = lastRecordedTimeLeftUnlocked - deltaTime;
                        if (timeLeftUnlocked < 1) {
                            systemState.getTransientState().setLocked(false);
                            systemState.getTransientState().setTimeLeftUnlocked(0);
                        }
                    }
                }
            }
            deltaTemperature -= (double) deltaTime * dissipativeHeatLossPerMin / CommonValues.millisInMin;
            float lastRecordedTemperature = systemState.getTransientState().getCurrentUpperChamberTemperature();
            float lastRecordedHumidity = systemState.getTransientState().getCurrentUpperChamberHumidity();
            float upperChamberTemperature = Math.min(CommonValues.maxPossibleTemperature, Math.abs(lastRecordedTemperature + (float) deltaTemperature));
            upperChamberTemperature = Math.max(upperChamberTemperature, CommonValues.minPossibleTemperature);
            systemState.getTransientState().setCurrentUpperChamberTemperature(upperChamberTemperature);
            float upperChamberHumidity = Math.min(CommonValues.maxHumidity, Math.abs(lastRecordedHumidity + (float) deltaHumidity));
            upperChamberHumidity = Math.max(upperChamberHumidity, CommonValues.minHumidity);
            systemState.getTransientState().setCurrentUpperChamberHumidity(upperChamberHumidity);
            int lastRecordedCO2Level = Math.min(CommonValues.maxCO2PPM, Math.abs(systemState.getTransientState().getCurrentCO2PPM() + deltaCO2));
            lastRecordedCO2Level = Math.min(lastRecordedCO2Level, CommonValues.maxCO2PPM);
            systemState.getTransientState().setCurrentCO2PPM(lastRecordedCO2Level);
            systemState.getTransientState().setTimeLeftUnlocked(currentTime);
        } else {
            started = true;
        }
    }

    protected void pushEmbeddedEvent(EmbeddedSystemEventType ev) {
        ObjectMapper mapper = new ObjectMapper();
        String message = "";
        try {
            message = mapper.writeValueAsString(new Integer(ev.ordinal()));
        } catch (JsonProcessingException ex) {
            Logger.getLogger(EmbeddedPlantSystemSimulator.class.getName()).log(Level.SEVERE, null, ex);
            return;
        }
        try {
            publish(TopicStrings.embeddedEvent() + "/" + systemState.getPersistentState().getUid(), 0, message.getBytes());
        } catch (MqttException ex) {
            Logger.getLogger(EmbeddedPlantSystemSimulator.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    protected boolean shouldTheLightsBeOn(long currentTime) {
        final long onTime = systemState.getPersistentState().getLightsOnHour() * CommonValues.millisInHour + systemState.getPersistentState().getLightsOnMinute() * (long) CommonValues.millisInMin;
        final long offTime = systemState.getPersistentState().getLightsOffHour() * CommonValues.millisInHour + systemState.getPersistentState().getLightsOffMinute() * (long) CommonValues.millisInMin;

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

    protected void connect() {
        connect(CommonValues.mqttBrokerURL);
        subscribeToEmbeddedConfigPush();
    }

    protected void pushState() {
        systemState.getTransientState().setTimestamp(System.currentTimeMillis());
        ObjectMapper objMapper = new ObjectMapper();
        String message = "";
        try {
            message = objMapper.writeValueAsString(systemState);
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
        String topic = TopicStrings.configPushToEmbedded() + "/" + systemState.getPersistentState().getUid();
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

        if (topicArg.equals(TopicStrings.configPushToEmbedded() + "/" + systemState.getPersistentState().getUid())) {
            ObjectMapper objectMapper = new ObjectMapper();
            try {
                EmbeddedSystemConfigChangeMemento receivedState = objectMapper.readValue(message.toString().getBytes(), EmbeddedSystemConfigChangeMemento.class);
                if (systemState.getPersistentState().getUid() != receivedState.getPersistentState().getUid()) {
                    log("LOGICAL ERROR: Received invalid UID as configuration. Our UID = " + systemState.getPersistentState().getUid() + ", assigned UID = " + receivedState.getPersistentState().getUid());
                    return;
                }
                if (receivedState.isChangingMistingInterval()) {
                    systemState.getPersistentState().setMistingInterval(receivedState.getPersistentState().getMistingInterval());
                }
                if (receivedState.isChangingMistingDuration()) {
                    systemState.getPersistentState().setMistingDuration(receivedState.getPersistentState().getMistingDuration());
                }
                if (receivedState.isChangingStatusPushInterval()) {
                    systemState.getPersistentState().setStatusPushInterval(receivedState.getPersistentState().getStatusPushInterval());
                }
                if (receivedState.isChangingNutrientsPPM()) {
                    systemState.getPersistentState().setNutrientsPPM(receivedState.getPersistentState().getNutrientsPPM());
                }
                if (receivedState.isChangingNutrientSolutionRatio()) {
                    systemState.getPersistentState().setNutrientSolutionRatio(receivedState.getPersistentState().getNutrientSolutionRatio());
                }
                if (receivedState.isChangingLightsOnHour()) {
                    systemState.getPersistentState().setLightsOnHour(receivedState.getPersistentState().getLightsOnHour());
                }
                if (receivedState.isChangingLightsOffHour()) {
                    systemState.getPersistentState().setLightsOffHour(receivedState.getPersistentState().getLightsOffHour());
                }
                if (receivedState.isChangingLightsOnMinute()) {
                    systemState.getPersistentState().setLightsOnMinute(receivedState.getPersistentState().getLightsOnMinute());
                }
                if (receivedState.isChangingLightsOffMinute()) {
                    systemState.getPersistentState().setLightsOffMinute(receivedState.getPersistentState().getLightsOffMinute());
                }
                if (receivedState.isChangingTargetUpperChamberHumidity()) {
                    systemState.getPersistentState().setTargetUpperChamberHumidity(receivedState.getPersistentState().getTargetUpperChamberHumidity());
                }
                if (receivedState.isChangingTargetUpperChamberTemperature()) {
                    systemState.getPersistentState().setTargetUpperChamberTemperature(receivedState.getPersistentState().getTargetUpperChamberTemperature());
                }
                if (receivedState.isChangingTargetLowerChamberTemperature()) {
                    systemState.getPersistentState().setTargetLowerChamberTemperature(receivedState.getPersistentState().getTargetLowerChamberTemperature());
                }
                if (receivedState.isChangingTargetCO2PPM()) {
                    systemState.getPersistentState().setTargetCO2PPM(receivedState.getPersistentState().getTargetCO2PPM());
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
   
    protected void log(String arg) {
        if (logging) {
            System.out.println(arg);
        }
    }
}
