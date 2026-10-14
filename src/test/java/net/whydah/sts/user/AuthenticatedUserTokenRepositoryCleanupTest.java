package net.whydah.sts.user;

import com.hazelcast.map.IMap;
import net.whydah.sso.config.ApplicationMode;
import net.whydah.sso.user.types.UserToken;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticatedUserTokenRepositoryCleanupTest {

    @BeforeAll
    static void shared() {
        Map<String, String> envs = new HashMap<>();
        envs.put(ApplicationMode.IAM_MODE_KEY, ApplicationMode.DEV);
        EnvHelper.setEnv(envs);
    }

    @SuppressWarnings("unchecked")
    private static <V> IMap<String, V> map(String field) throws Exception {
        Field f = AuthenticatedUserTokenRepository.class.getDeclaredField(field);
        f.setAccessible(true);
        return (IMap<String, V>) f.get(null);
    }

    private static UserToken token(String id, String userName, long issuedAt, long lifespanMs) {
        UserToken t = new UserToken();
        t.setUserTokenId(id);
        t.setUserName(userName);
        t.setTimestamp(String.valueOf(issuedAt));
        t.setLifespan(String.valueOf(lifespanMs));
        return t;
    }

    @Test
    void cleanupRemovesExpiredTokensAndOrphanedUsernamesOnly() throws Exception {
        IMap<String, UserToken> tokens = map("activeusertokensmap");
        IMap<String, String> usernames = map("active_username_usertokenids_map");
        long now = System.currentTimeMillis();

        String valid = UUID.randomUUID().toString();
        String expired = UUID.randomUUID().toString();
        String orphanUser = "orphan-" + UUID.randomUUID();
        tokens.put(valid, token(valid, "valid-user", now, 3_600_000));
        tokens.put(expired, token(expired, "expired-user", now - 7_200_000, 3_600_000));
        usernames.put("valid-user", valid);
        usernames.put("expired-user", expired);
        usernames.put(orphanUser, UUID.randomUUID().toString());

        AuthenticatedUserTokenRepository.cleanUserTokenMap();

        assertTrue(tokens.containsKey(valid));
        assertFalse(tokens.containsKey(expired));
        assertEquals(valid, usernames.get("valid-user"));
        assertNull(usernames.get("expired-user"));
        assertNull(usernames.get(orphanUser));
    }
}
