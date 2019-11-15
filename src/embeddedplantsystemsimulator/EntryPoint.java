/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package embeddedplantsystemsimulator;

import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author noob
 */
public class EntryPoint {

    
    
    public static void main(String[] args) {
        EmbeddedPlantSystemSimulator sim = new EmbeddedPlantSystemSimulator();
        sim.setLogging(true);
        sim.init(Math.abs(new Random().nextLong()));
        sim.connect();        

        while(true) {
           sim.simulationLoop();
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Logger.getLogger(EntryPoint.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }      
}
