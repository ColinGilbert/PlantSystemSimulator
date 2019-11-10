/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package embeddedplantsystemsimulator;

/**
 *
 * @author noob
 */
public class EntryPoint {

    public static void main(String[] args) {
        long uid = 999;

        EmbeddedPlantSystemSimulator sim = new EmbeddedPlantSystemSimulator(uid);

        sim.setLogging(true);

        sim.connect();

        while (true) {
            sim.simulationLoop();
        }

    }
}
