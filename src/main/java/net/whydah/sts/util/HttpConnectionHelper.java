package net.whydah.sts.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.whydah.sso.util.StringConv;

public class HttpConnectionHelper {

    private final static Logger log = LoggerFactory.getLogger(HttpConnectionHelper.class);
    
    public static class Response {

    	private int responseCode;
    	private byte[] data = null;
    	
    	public String getContent() {
    		if(data!=null) {
    			return StringConv.UTF8(data);
    		}
    		return null;
    	}
    	
    	public void setData(byte[] data) {
    		this.data = data;
    	}

		public int getResponseCode() {
			return responseCode;
		}

		public void setResponseCode(int responseCode) {
			this.responseCode = responseCode;
		}
    }


    //GET
    public static Response get(String url) {
        return connect(url, "GET", null, null, null);
    }

    public static Response get(String url, String accessToken) {
        return connect(url, "GET", accessToken, null, null);
    }

    public static Response get(String url, String accessToken, Map<String, String> params, byte[] sentData) {
        return connect(url, "GET", accessToken, params, sentData);
    }


    //POST
    public static Response post(String url, String accessToken, byte[] data) {
        return connect(url, "POST", accessToken, null, data);
    }

    public static Response post(String url, byte[] data) {
        return connect(url, "POST", null, null, data);
    }

    public static Response post(String url, String accessToken, Map<String, String> params, byte[] sentData) {
        return connect(url, "POST", accessToken, params, sentData);
    }

    //PUT
    public static Response put(String url, String accessToken, byte[] data) {
        return connect(url, "PUT", accessToken, null, data);
    }

    public static Response put(String url, byte[] data) {
        return connect(url, "PUT", null, null, data);
    }

    public static Response put(String url, String accessToken, Map<String, String> params, byte[] sentData) {
        return connect(url, "PUT", accessToken, params, sentData);
    }

    //DELETE
    public static Response delete(String url) {
        return connect(url, "DELETE", null, null, null);
    }

    public static Response delete(String url, byte[] data) {
        return connect(url, "DELETE", null, null, data);
    }

    public static Response delete(String url, String accessToken) {
        return connect(url, "DELETE", accessToken, null, null);
    }

    public static Response delete(String url, String accessToken, byte[] data) {
        return connect(url, "DELETE", accessToken, null, data);
    }

    public static Response delete(String url, String accessToken, Map<String, String> params, byte[] sentData) {
        return connect(url, "DELETE", accessToken, params, sentData);
    }


    public static Response connect(String url, String method, String accessToken, Map<String, String> params, byte[] sentData) {
        log.debug("String {}, String {}, String {}, Map<String, String> {}, byte[] {}"
                , url, method, accessToken, params, sentData);
        Response responseLine = null;
        try {

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            if (accessToken != null && accessToken.length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            }

            if (params != null) {
                for (String key : params.keySet()) {
                    conn.setRequestProperty(key, params.get(key));
                }
            }
            conn.setRequestMethod(method);


            if (sentData != null) {
                conn.setDoOutput(true);
                if (!conn.getRequestProperties().containsKey("Content-Type")) {
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                }
                OutputStream os = conn.getOutputStream();
                os.write(sentData);
                os.flush();
                os.close();
            }


            responseLine = readInput(conn);

        } catch (UnsupportedEncodingException e) {
            log.error("UnsupportedEncodingException", e);
            e.printStackTrace();
        } catch (IOException e) {
            log.error("IOException", e);
            e.printStackTrace();
        }
        return responseLine;
    }

    private static Response readInput(HttpURLConnection conn) throws UnsupportedEncodingException, IOException {
//		String responseLine;
//		BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"));
//		
//		StringBuilder response = new StringBuilder();
//
//		while ((responseLine = br.readLine()) != null) {
//			response.append(responseLine.trim());
//		}
//
//		br.close();
//		return response.toString();
        Response res = new Response();
        res.setResponseCode(conn.getResponseCode());
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int nRead;
            byte[] data = new byte[1024];
            while ((nRead = conn.getInputStream().read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }

            buffer.flush();
            byte[] byteArray = buffer.toByteArray();
            res.setData(byteArray);//.readAllBytes() is Java9+);
        } catch (IOException e) {
            log.error("IOException", e);

            e.printStackTrace();
        }
        return res;
    }

}
