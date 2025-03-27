package net.whydah.sts.config;

import com.hazelcast.config.Config;
import com.hazelcast.config.XmlConfigBuilder;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class HazelCastConfigTest {


    @Test
    public void testHazelCastMap() throws Exception {
        Config cfg = new Config();
        HazelcastInstance instance = Hazelcast.newHazelcastInstance(cfg);

        Map<Integer, String> mapCustomers = instance.getMap("customers");
        startProcess();
        startProcess();
        startProcess();
        startProcess();
        startProcess();
        startProcess();
        startProcess();
        Thread.sleep(2000);

        //System.out.println("queueCustomers size: " + mapCustomers.size());
        //System.out.println("queueCustomers values: " + mapCustomers.values());
    }

    private void startProcess() {
        Config cfg = new Config();

        HazelcastInstance instance = Hazelcast.newHazelcastInstance(cfg);
        Map<Integer, String> mapCustomers = instance.getMap("customers");
        mapCustomers.put(1, "Joe-" + UUID.randomUUID().toString());
        mapCustomers.put(new Random().nextInt(), "Alice-" + UUID.randomUUID().toString());
        mapCustomers.put(new Random().nextInt(), "Bravo-" + UUID.randomUUID().toString());
        mapCustomers.put(new Random().nextInt(), "Charlie-" + UUID.randomUUID().toString());

        //System.out.println("Map size: " + mapCustomers.size());
    }

    @Test
    public void testConfigOverride() throws Exception {
        //- Dhazelcast.config
        System.setProperty("hazelcast.config", "test_hazelcast.xml");  // path to hazelcast.xml
        String xmlFileName = System.getProperty("hazelcast.config");
        Config cfg = new XmlConfigBuilder(xmlFileName).build();


    }
}
