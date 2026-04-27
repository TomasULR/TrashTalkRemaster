package files;

import auth.Session;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ApiClient;
import okhttp3.*;
import okio.BufferedSink;

import javax.net.ssl.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

public class FileApiService {

    private final String baseUrl;
    private final OkHttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public FileApiService(ApiClient client) {
        this.baseUrl = client.getBaseUrl();
        this.http = buildHttpClient(client.isTrustAllCerts());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AttachmentDto(
            String id,
            String filename,
            long sizeBytes,
            String mimeType,
            String sha256Hex,
            String messageId
    ) {}

    /**
     * Upload a file to a server. Calls onProgress with bytes sent so far.
     * Runs on whatever thread you call it from — use SwingWorker for UI.
     */
    public AttachmentDto upload(UUID serverId, File file, LongConsumer onProgress) throws IOException {
        String mimeType = Files.probeContentType(file.toPath());
        if (mimeType == null) mimeType = "application/octet-stream";

        RequestBody fileBody = buildProgressBody(file, mimeType, onProgress);
        RequestBody multipart = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.getName(), fileBody)
                .build();

        Request request = authed(new Request.Builder()
                .url(baseUrl + "/files/upload?serverId=" + serverId)
                .post(multipart))
                .build();

        try (Response response = http.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new ApiClient.ApiException(response.code(), extractMessage(body));
            }
            return json.readValue(body, AttachmentDto.class);
        }
    }

    /**
     * Download a file to a temp file. Returns the temp path.
     * Calls onProgress with bytes received so far.
     */
    public Path download(String attachmentId, String filename, LongConsumer onProgress) throws IOException {
        Request request = authed(new Request.Builder()
                .url(baseUrl + "/files/" + attachmentId)
                .get())
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ApiClient.ApiException(response.code(), "Download failed");
            }
            String ext = "";
            int dot = filename.lastIndexOf('.');
            if (dot >= 0) ext = filename.substring(dot);
            Path tmp = Files.createTempFile("trashtalk_", ext);
            tmp.toFile().deleteOnExit();

            ResponseBody body = response.body();
            if (body == null) return tmp;

            long received = 0;
            byte[] buf = new byte[8192];
            try (InputStream in = body.byteStream();
                 OutputStream out = Files.newOutputStream(tmp)) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    received += n;
                    onProgress.accept(received);
                }
            }
            return tmp;
        }
    }

    /** Fetch attachment metadata only (no body download). */
    public AttachmentDto info(String attachmentId) throws IOException {
        Request request = authed(new Request.Builder()
                .url(baseUrl + "/files/" + attachmentId + "/info")
                .get())
                .build();
        try (Response response = http.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful())
                throw new ApiClient.ApiException(response.code(), extractMessage(body));
            return json.readValue(body, AttachmentDto.class);
        }
    }

    // ---- helpers ----

    private Request.Builder authed(Request.Builder b) {
        String token = Session.get().getAccessToken();
        if (token != null) b.header("Authorization", "Bearer " + token);
        return b;
    }

    private RequestBody buildProgressBody(File file, String mimeType, LongConsumer onProgress) {
        return new RequestBody() {
            @Override public MediaType contentType() { return MediaType.parse(mimeType); }
            @Override public long contentLength()    { return file.length(); }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                long sent = 0;
                byte[] buf = new byte[8192];
                try (InputStream in = new FileInputStream(file)) {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        sink.write(buf, 0, n);
                        sent += n;
                        onProgress.accept(sent);
                    }
                }
            }
        };
    }

    private OkHttpClient buildHttpClient(boolean trustAllCerts) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS);
        if (trustAllCerts) {
            try {
                TrustManager[] tm = new TrustManager[]{ new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }};
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, tm, new java.security.SecureRandom());
                builder.sslSocketFactory(ctx.getSocketFactory(), (X509TrustManager) tm[0])
                       .hostnameVerifier((h, s) -> true);
            } catch (Exception e) {
                throw new RuntimeException("TLS init failed", e);
            }
        }
        return builder.build();
    }

    private String extractMessage(String body) {
        try {
            var node = json.readTree(body);
            if (node.has("message")) return node.get("message").asText();
            if (node.has("error"))   return node.get("error").asText();
        } catch (Exception ignored) {}
        return body.isEmpty() ? "Server error" : body;
    }
}
