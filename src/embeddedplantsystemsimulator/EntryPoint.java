/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package embeddedplantsystemsimulator;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author noob
 */
public class EntryPoint {

    public static void main(String[] args) {
        long uid = 999;

        EmbeddedPlantSystemSimulator sim = new EmbeddedPlantSystemSimulator(uid);

        sim.setLogging(true);

        sim.init();
        sim.connect();


        while(true) {
           sim.simulationLoop();
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Logger.getLogger(EntryPoint.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

    }
}
