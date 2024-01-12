package net.whydah.sts.config;


import jakarta.ws.rs.core.Response;
import org.glassfish.jersey.server.mvc.Viewable;


public class DevModeHelper {

    public static Response return_DEV_MODE_ExampleUserToken(int n) {
        return Response.ok(new Viewable("/dev_usertoken.1.ftl")).build();
    }

    public static Response return_DEV_MODE_ExampleApplicationToken(int n) {
        return Response.ok(new Viewable("/dev_applicationtoken.1.ftl")).build();
    }

}
