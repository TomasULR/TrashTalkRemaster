package net;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.*;

import javax.net.ssl.*;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

public class ApiClient {

    private final OkHttpClient http;
    private final ObjectMapper json;
    private final String baseUrl;
    private final boolean trustAllCerts;
    private volatile String accessToken;

    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public String getBaseUrl()      { return baseUrl; }
    public boolean isTrustAllCerts() { return trustAllCerts; }

    public ApiClient(String baseUrl, boolean trustAllCerts) {
        this.baseUrl       = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.trustAllCerts = trustAllCerts;
        this.json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(chain -> {
                    Request.Builder req = chain.request().newBuilder();
                    if (accessToken != null) {
                        req.header("Authorization", "Bearer " + accessToken);
                    }
                    return chain.proceed(req.build());
                });

        if (trustAllCerts) {
            // Dev only: trust self-signed cert
            builder = applyTrustAll(builder);
        }

        this.http = builder.build();
    }

    public void setAccessToken(String token) {
        this.accessToken = token;
    }

    public void clearAccessToken() {
        this.accessToken = null;
    }

    // ---- Generic helpers ----

    public <T> T post(String path, Object body, Class<T> responseType) throws IOException {
        String reqBody = json.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(baseUrl + path)
                .post(RequestBody.create(reqBody, JSON))
                .build();
        try (Response response = http.newCall(request).execute()) {
            return handleResponse(response, responseType);
        }
    }

    public <T> T get(String path, Class<T> responseType) throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl + path)
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            return handleResponse(response, responseType);
        }
    }

    public void postNoBody(String path) throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl + path)
                .post(RequestBody.create("", JSON))
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ApiException(response.code(), parseErrorMessage(response));
            }
        }
    }

    public <T> T patch(String path, Object body, Class<T> responseType) throws IOException {
        String reqBody = json.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(baseUrl + path)
                .patch(RequestBody.create(reqBody, JSON))
                .build();
        try (Response response = http.newCall(request).execute()) {
            return handleResponse(response, responseType);
        }
    }

    public void delete(String path) throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl + path)
                .delete()
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ApiException(response.code(), parseErrorMessage(response));
            }
        }
    }

    private <T> T handleResponse(Response response, Class<T> type) throws IOException {
        String body = response.body() != null ? response.body().string() : "";
        if (!response.isSuccessful()) {
            String message = parseErrorMessage(body);
            throw new ApiException(response.code(), message);
        }
        return json.readValue(body, type);
    }

    private String parseErrorMessage(Response response) throws IOException {
        String body = response.body() != null ? response.body().string() : "";
        return parseErrorMessage(body);
    }

    private String parseErrorMessage(String body) {
        try {
            var node = json.readTree(body);
            if (node.has("message")) return node.get("message").asText();
            if (node.has("error"))   return node.get("error").asText();
        } catch (Exception ignored) {}
        return body.isEmpty() ? "Chyba serveru" : body;
    }

    // Dev-only: trust all SSL certs (self-signed)
    private OkHttpClient.Builder applyTrustAll(OkHttpClient.Builder builder) {
        try {
            TrustManager[] tm = new TrustManager[]{ new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tm, new java.security.SecureRandom());
            return builder
                    .sslSocketFactory(ctx.getSocketFactory(), (X509TrustManager) tm[0])
                    .hostnameVerifier((h, s) -> true);
        } catch (Exception e) {
            throw new RuntimeException("Nelze inicializovat TLS", e);
        }
    }

    public static class ApiException extends IOException {
        public final int statusCode;
        public ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}
