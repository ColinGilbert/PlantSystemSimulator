/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package embeddedplantsystemsimulator;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.io.ByteArrayOutputStream;

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

import noob.plantsystem.common.*;

public class EmbeddedPlantSystemSimulator implements MqttCallback {

    EmbeddedPlantSystemSimulator(long uidArg) {

        persistence = new MemoryPersistence();

        transientState = new TransientArduinoState();
        persistedState = new PersistentArduinoState();

        transientState.setUpperChamberTemperature(23.0f);
        transientState.setLowerChamberTemperature(18.0f);
        transientState.setUpperChamberHumidity(50.0f);

        persistedState.setUID(uidArg);

        topic = TopicStrings.statePushToEmbedded();
        topic += "/";
        topic += persistedState.getUID();

        connectionOptions = new MqttConnectOptions();
        connectionOptions.setCleanSession(true);
        try {
            client = new MqttClient(brokerURL, Long.toString(uidArg));

        } catch (MqttException e) {
            log("Could not setup mqtt client in constructor. Cause: " + e);
        }
    }

    public void setLogging(boolean arg) {
        logging = arg;
    }

    // This simulates the values creeping towards their respective equilibria.
    public void simulationLoop() {

        long currentTime = System.currentTimeMillis();
        long deltaTime = currentTime - lastRecordedTime;

        if (deltaTime < 1000) {
            return;
        }

        double deltaTemperature = 0.0d;
        double deltaHumidity = 0.0d;
        int deltaCO2 = 0;

        final double MILLIS_IN_SEC = 1000.0d;
        final double MILLIS_IN_MIN = 60000.0d;

        if (transientState.getLights()) {
            deltaTemperature += (double) deltaTime * lightsOnHeatGainPerMin / MILLIS_IN_MIN;
            deltaHumidity += (double) deltaTime * lightsOnHumidityGainPerMin / MILLIS_IN_MIN;
            deltaCO2 -= (long) ((double) deltaTime * lightsOnCO2LossPerMin / MILLIS_IN_MIN);
        }
        if (transientState.getDehumidifying()) {
            deltaHumidity -= (double) deltaTime * dehumidifyHumidityLossPerMin / MILLIS_IN_MIN;
        }
        if (transientState.getCooling()) {
            deltaTemperature -= (double) deltaTime * coolingHeatLossPerMin / MILLIS_IN_MIN;
        }
        if (transientState.getInjectingCO2()) {
            deltaCO2 += (long) (double) deltaTime * co2InjectionPPMPerSec / MILLIS_IN_SEC;
        }

        deltaTemperature -= (double) deltaTime * dissipativeHeatLossPerMin / MILLIS_IN_MIN;

        int lastRecordedCO2Level = transientState.getCO2PPM();
        double lastRecordedTemperature = (double) transientState.getUpperChamberTemperature();
        double lastRecordedHumidity = (double) transientState.getUpperChamberHumidity();

        transientState.setCO2PPM(lastRecordedCO2Level + deltaCO2);
        transientState.setUpperChamberTemperature((float) (lastRecordedTemperature + deltaTemperature));
        transientState.setUpperChamberHumidity((float) (lastRecordedHumidity + deltaHumidity));

        if (!transientState.getLocked()) {
            long lastRecordedTimeLeftUnlocked = transientState.getTimeLeftUnlocked();
            long timeLeftUnlocked = lastRecordedTimeLeftUnlocked - deltaTime;
            if (timeLeftUnlocked < 1) {
                transientState.setDoorsLocked(false);
                transientState.setTimeLeftUnlocked(0);
            }
        }

        if (transientState.getCO2PPM() > persistedState.getTargetCO2PPM()) {
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

        // TODO: Lights code
        lastRecordedTime = currentTime;

    }

    public void connect() {
        connect(brokerURL, 1);
        sendHello();
    }

    protected void sendHello() {

        ObjectMapper objectMapper = new ObjectMapper();
        String json;
        try {
         json = objectMapper.writeValueAsString(persistedState);
        } catch (JsonProcessingException e) {
            log("Could not turn hello message into JSON: " + e);
            return;
        }
        try {
            publish(TopicStrings.embeddedHello(), 1, json.getBytes());
        } catch (MqttException e) {
            log("COuld not send Hello via MQTT: " + e);
        }
        log("Sent Hello!");
    }

    protected void publish(String topicName, int qos, byte[] payload) throws MqttException {
        client.connect(connectionOptions);

        // Create and configure a message
        MqttMessage message = new MqttMessage(payload);
        message.setQos(qos);

        client.publish(topicName, message);

        client.disconnect();
        
        
        log("Message published! Topic = " + topicName + ", qos = " + qos + " Payload: " + payload);

    }

    protected void connect(String broker, int qos) {
        if (qos < 0) {
            qos = 0;
        }
        if (qos > 2) {
            qos = 2;
        }

        // Connect to the MQTT server
        try {
            client.connect(connectionOptions);
            log("Connected to " + brokerURL + " with client ID " + client.getClientId());
        } catch (MqttSecurityException e) {
            log("MQTT Security exception caught while connecting: " + e);
        } catch (MqttException e) {
            log("MQTTException caught while connecting: " + e);
        }
        log("Subscribing to topic \"" + topic + "\" qos " + qos);
        try {
            client.subscribe(topic, qos);
        } catch (MqttException e) {
            log("Caught MqttException while trying to subscribe" + e);
        }
        try {
            client.disconnect();
        } catch (MqttException e) {
            log("Caught MqttException while trying to disconnect after subscribing.");
        }
    }

    // MQTT callbacks
    @Override
    public void connectionLost(Throwable cause) {
        log("Connection lost... Cause:" + cause);
        // TODO: Implement reconnect?

    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        log("MQTT message delivered! " + token);
    }

    @Override
    public void messageArrived(String topicArg, MqttMessage message) throws MqttException {
        if (topicArg.equals(topic)) {
            ObjectMapper objectMapper = new ObjectMapper();
            try {
                PersistentArduinoState receivedState = objectMapper.readValue(message.toString().getBytes(), PersistentArduinoState.class);
                if (persistedState.getUID() != receivedState.getUID()) {
                    log("LOGICAL ERROR: Received invalid UID as configuration. Our UID = " + persistedState.getUID() + ", assigned UID = " + receivedState.getUID());
                    return;
                }
            } catch (IOException e) {
                log("Could not convert MQTT message to PersistentArduinoState. Cause: " + e);
                return;
            }
            if (!handshakeCompleted) {
                handshakeCompleted = true;
            }
        } else {
            log("Received unsubscribed MQTT topic: " + topic);
        }
        log("MQTT message received. Topic = " + topic + ", message = " + message);
    }

    // End of MQTT callbacks
    // Internals
    private void log(String arg) {
        if (logging) {
            System.out.println(arg);
        }
    }

    /*
    
    public void setUID(long arg);

    public void setMistingInterval(int arg);
    
    public void setMistingDuration(int arg);
    
    public void setStatusUpdatePushInterval(int arg);
    
    public void setNutrientSolutionRatio(double arg);
    
    public void setLightsOnTime(long arg);
    
    public void setLightsOffTime(long arg);
    
    public void setTargetUpperChamberHumidity(float arg);
    
    public void setTargetUpperChamberTemperature(float arg);
    
  //  public void setTargetLowerChamberTemperature(float arg);
    
    public void setTargetCO2PPM(int arg);
    
     */
    // String topic = "MQTT Examples";
    // String content = "Message from MqttPublishSample";
    protected PersistentArduinoState persistedState;// = new PersistentArduinoState();
    protected TransientArduinoState transientState;// = new TransientArduinoState();
    protected boolean handshakeCompleted = false;
    protected boolean persistentStateLoaded = false;
    protected boolean logging = true;
    protected String brokerURL = "tcp://127.0.0.1:1883";
    protected String topic;
    protected MemoryPersistence persistence;// = new MemoryPersistence();
    protected long deltaTime;
    protected MqttClient client;
    protected MqttConnectOptions connectionOptions;

    double dehumidifyHumidityLossPerMin = 1.0d;
    double dehumidifyHeatPerMin = 0.3d;
    double coolingHeatLossPerMin = 1.0d;
    double lightsOnHeatGainPerMin = 0.1d;
    double lightsOnHumidityGainPerMin = 0.1d;
    double dissipativeHeatLossPerMin = 0.05d;
    int co2InjectionPPMPerSec = 1000;
    int lightsOnCO2LossPerMin = 100;
    long lastRecordedTime;
    long timeSinceLastMisting = 0;
    long currentMistingDuration = 0;
}
