package net.whydah.sts.util;


import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

public class UUIDSecretTest {

    Base64.Encoder encoder = Base64.getEncoder();


    @Test
    public void testGenerateUUIDSecret() throws Exception {
        String rawString = UUID.randomUUID().toString() + UUID.randomUUID().toString() + UUID.randomUUID().toString();

        String base64Secret = encoder.encodeToString(rawString.getBytes());

        System.out.println("Base64Secret: " + base64Secret);
    }
}
