package com.example.core.servlets;

import com.example.core.services.KalturaConfigManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client5.http.classic.methods.HttpPost;
import org.apache.http.client5.http.impl.classic.CloseableHttpClient;
import org.apache.http.client5.http.impl.classic.HttpClients;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.osgi.services.HttpClientBuilderFactory;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletPaths;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component(service = Servlet.class)
@SlingServletPaths("/bin/myaemproject/admin/kaltura/oauth/start")
public class OAuthStartServlet extends SlingAllMethodsServlet {

    private static final Logger LOG = LoggerFactory.getLogger(OAuthStartServlet.class);
    private static final String KALTURA_STATE_SESSION_KEY = "kalturaOAuthState";
    private static final String ADMIN_UI_PATH = "/apps/myaemproject/admin/kaltura-auth.html";


    @Reference
    private KalturaConfigManager kalturaConfigManager;

    // Optional: Use OSGi service for HttpClient factory if available and preferred.
    @Reference(cardinality = org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL)
    private HttpClientBuilderFactory httpClientBuilderFactory;


    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        try {
            String kalturaClientId = request.getParameter("kalturaClientId");
            String kalturaClientSecret = request.getParameter("kalturaClientSecret");
            String kalturaAuthUrl = request.getParameter("kalturaAuthUrl");
            String kalturaTokenUrl = request.getParameter("kalturaTokenUrl");
            String appBuilderOAuthHandlerUrl = request.getParameter("appBuilderOAuthHandlerUrl");

            LOG.info("Received parameters: ClientId={}, AuthUrl={}, TokenUrl={}, AppBuilderUrl={}",
                    kalturaClientId, kalturaAuthUrl, kalturaTokenUrl, appBuilderOAuthHandlerUrl);

            if (StringUtils.isAnyBlank(kalturaClientId, kalturaClientSecret, kalturaAuthUrl, kalturaTokenUrl, appBuilderOAuthHandlerUrl)) {
                response.sendRedirect(ADMIN_UI_PATH + "?status=error&message=" + URLEncoder.encode("All fields are required.", StandardCharsets.UTF_8.name()));
                return;
            }

            Map<String, Object> newConfig = new HashMap<>();
            newConfig.put("kalturaClientId", kalturaClientId);
            newConfig.put("kalturaClientSecret", kalturaClientSecret);
            newConfig.put("kalturaAuthUrl", kalturaAuthUrl);
            newConfig.put("kalturaTokenUrl", kalturaTokenUrl);
            newConfig.put("appBuilderOAuthHandlerUrl", appBuilderOAuthHandlerUrl);

            kalturaConfigManager.updateConfiguration(newConfig);
            LOG.info("Kaltura configuration updated successfully.");

            String state = UUID.randomUUID().toString();
            HttpSession session = request.getSession(true);
            session.setAttribute(KALTURA_STATE_SESSION_KEY, state);
            LOG.debug("Stored state in session: {}", state);

            String redirectUri = request.getScheme() + "://" +
                                 request.getServerName() +
                                 (request.getServerPort() == 80 || request.getServerPort() == 443 ? "" : ":" + request.getServerPort()) +
                                 "/bin/myaemproject/admin/kaltura/oauth/callback";
            LOG.debug("Constructed redirect_uri for callback servlet: {}", redirectUri);

            JSONObject appBuilderPayload = new JSONObject();
            appBuilderPayload.put("handler_type", "start_auth");
            appBuilderPayload.put("kaltura_auth_url", kalturaAuthUrl); // From form/config
            appBuilderPayload.put("client_id", kalturaClientId);     // From form/config
            appBuilderPayload.put("redirect_uri", redirectUri);
            appBuilderPayload.put("state", state);
            // Optional: Add scope if needed: appBuilderPayload.put("scope", "your:scopes");

            LOG.debug("Calling App Builder action at URL: {} with payload: {}", appBuilderOAuthHandlerUrl, appBuilderPayload.toString());

            String authorizationUrl;
            CloseableHttpClient httpClient = getHttpClient();
            try {
                HttpPost httpPost = new HttpPost(appBuilderOAuthHandlerUrl);
                httpPost.setEntity(new StringEntity(appBuilderPayload.toString(), ContentType.APPLICATION_JSON));

                authorizationUrl = httpClient.execute(httpPost, httpResponse -> {
                    int statusCode = httpResponse.getCode();
                    String responseBody = new java.util.Scanner(httpResponse.getEntity().getContent()).useDelimiter("\\A").next();
                    LOG.debug("App Builder response status: {}, body: {}", statusCode, responseBody);

                    if (statusCode >= 200 && statusCode < 300) {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        if (jsonResponse.has("body") && jsonResponse.getJSONObject("body").has("authorization_url")) {
                            return jsonResponse.getJSONObject("body").getString("authorization_url");
                        } else if (jsonResponse.has("authorization_url")){ // If body wrapper is not present
                             return jsonResponse.getString("authorization_url");
                        }
                        throw new IOException("App Builder response missing 'authorization_url'. Response: " + responseBody);
                    } else {
                        throw new IOException("App Builder action failed with status " + statusCode + ". Response: " + responseBody);
                    }
                });
            } finally {
                if (httpClientBuilderFactory == null && httpClient != null) { // Only close if manually created
                    httpClient.close();
                }
            }

            if (StringUtils.isNotBlank(authorizationUrl)) {
                LOG.info("Received authorization_url from App Builder: {}", authorizationUrl);
                response.sendRedirect(authorizationUrl);
            } else {
                LOG.error("Failed to get authorization_url from App Builder.");
                response.sendRedirect(ADMIN_UI_PATH + "?status=error&message=" + URLEncoder.encode("Failed to get authorization URL from App Builder.", StandardCharsets.UTF_8.name()));
            }

        } catch (Exception e) {
            LOG.error("Error in OAuthStartServlet", e);
            response.sendRedirect(ADMIN_UI_PATH + "?status=error&message=" + URLEncoder.encode("Error starting OAuth flow: " + e.getMessage(), StandardCharsets.UTF_8.name()));
        }
    }

    private CloseableHttpClient getHttpClient() {
        if (httpClientBuilderFactory != null) {
            return httpClientBuilderFactory.newBuilder().build();
        }
        // Fallback to default client if factory is not available (e.g. older AEM versions or specific setup)
        LOG.warn("HttpClientBuilderFactory not available, creating default HttpClient. Consider configuring the Apache HTTP Components Client Factory.");
        return HttpClients.createDefault();
    }
}
