package com.example.core.servlets;

import com.example.core.config.KalturaConfig;
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
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletPaths;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Component(service = Servlet.class)
@SlingServletPaths("/bin/myaemproject/admin/kaltura/oauth/callback")
public class OAuthCallbackServlet extends SlingAllMethodsServlet {

    private static final Logger LOG = LoggerFactory.getLogger(OAuthCallbackServlet.class);
    private static final String KALTURA_STATE_SESSION_KEY = "kalturaOAuthState";
    private static final String ADMIN_UI_PATH = "/apps/myaemproject/admin/kaltura-auth.html"; // AEM Admin UI path
    private static final String KALTURA_TOKENS_JCR_PATH = "/etc/kaltura/oauth-tokens";
    public static final String SERVICE_USER_NAME = "myaemproject-serviceuser"; // Define your service user

    @Reference
    private KalturaConfigManager kalturaConfigManager;

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    @Reference(cardinality = org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL)
    private HttpClientBuilderFactory httpClientBuilderFactory;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String code = request.getParameter("code");
        String stateFromKaltura = request.getParameter("state");
        LOG.info("Callback received. Code: {}, State from Kaltura: {}", StringUtils.isNotEmpty(code) ? "PRESENT" : "MISSING", stateFromKaltura);

        HttpSession session = request.getSession(false);
        String stateFromSession = (session != null) ? (String) session.getAttribute(KALTURA_STATE_SESSION_KEY) : null;

        String statusMessage;
        String statusType = "error";

        if (session != null) {
            session.removeAttribute(KALTURA_STATE_SESSION_KEY); // Consume state
        }

        if (StringUtils.isBlank(stateFromKaltura) || !stateFromKaltura.equals(stateFromSession)) {
            LOG.error("State validation failed. Session state: '{}', Kaltura state: '{}'", stateFromSession, stateFromKaltura);
            statusMessage = "OAuth state validation failed. Possible CSRF attack.";
            response.sendRedirect(ADMIN_UI_PATH + "?status=" + statusType + "&message=" + URLEncoder.encode(statusMessage, StandardCharsets.UTF_8.name()));
            return;
        }

        LOG.debug("State validation successful.");

        KalturaConfig currentConfig = kalturaConfigManager.getConfiguration();
        if (currentConfig == null || StringUtils.isAnyBlank(
                currentConfig.kalturaClientId(),
                currentConfig.kalturaClientSecret(),
                currentConfig.kalturaTokenUrl(),
                currentConfig.appBuilderOAuthHandlerUrl())) {
            LOG.error("Kaltura configuration is missing or incomplete.");
            statusMessage = "Kaltura configuration is missing or incomplete in OSGi.";
            response.sendRedirect(ADMIN_UI_PATH + "?status=" + statusType + "&message=" + URLEncoder.encode(statusMessage, StandardCharsets.UTF_8.name()));
            return;
        }

        String redirectUri = request.getScheme() + "://" +
                             request.getServerName() +
                             (request.getServerPort() == 80 || request.getServerPort() == 443 ? "" : ":" + request.getServerPort()) +
                             request.getPathInfo(); // Should be /bin/myaemproject/admin/kaltura/oauth/callback

        JSONObject appBuilderPayload = new JSONObject();
        appBuilderPayload.put("handler_type", "handle_callback");
        appBuilderPayload.put("code", code);
        appBuilderPayload.put("state", stateFromKaltura); // Optional for App Builder, but good to pass
        appBuilderPayload.put("client_id", currentConfig.kalturaClientId());
        appBuilderPayload.put("client_secret", currentConfig.kalturaClientSecret());
        appBuilderPayload.put("kaltura_token_url", currentConfig.kalturaTokenUrl());
        appBuilderPayload.put("redirect_uri", redirectUri);

        LOG.debug("Calling App Builder 'handle_callback' with payload: {}", appBuilderPayload.toString());

        CloseableHttpClient httpClient = getHttpClient();
        try {
            HttpPost httpPost = new HttpPost(currentConfig.appBuilderOAuthHandlerUrl());
            httpPost.setEntity(new StringEntity(appBuilderPayload.toString(), ContentType.APPLICATION_JSON));

            String appBuilderResponse = httpClient.execute(httpPost, httpResp -> {
                int statusCode = httpResp.getCode();
                String responseBody = new java.util.Scanner(httpResp.getEntity().getContent()).useDelimiter("\\A").next();
                LOG.debug("App Builder response status: {}, body: {}", statusCode, responseBody);
                if (statusCode >= 200 && statusCode < 300) {
                    return responseBody;
                } else {
                    throw new IOException("App Builder action 'handle_callback' failed with status " + statusCode + ". Response: " + responseBody);
                }
            });

            JSONObject jsonResponse = new JSONObject(appBuilderResponse);
            JSONObject responseBody = jsonResponse.has("body") ? jsonResponse.getJSONObject("body") : jsonResponse;


            String accessToken = responseBody.optString("access_token", null);
            String refreshToken = responseBody.optString("refresh_token", null);
            long expiresIn = responseBody.optLong("expires_in", 0); // in seconds

            if (StringUtils.isAnyBlank(accessToken, refreshToken) || expiresIn <= 0) {
                LOG.error("App Builder response missing tokens or expires_in. Response: {}", appBuilderResponse);
                statusMessage = "Failed to retrieve valid tokens from App Builder: " + responseBody.optString("message", "No details provided.");
                throw new IOException(statusMessage);
            }

            long expiresAt = System.currentTimeMillis() + (expiresIn * 1000);

            // Store tokens in JCR
            storeTokensInJcr(accessToken, refreshToken, expiresAt);
            LOG.info("Tokens stored successfully in JCR at {}", KALTURA_TOKENS_JCR_PATH);

            // Update OSGi config with new tokens
            Map<String, Object> updatedTokenConfig = new HashMap<>();
            updatedTokenConfig.put("kalturaAccessToken", accessToken);
            updatedTokenConfig.put("kalturaRefreshToken", refreshToken);
            updatedTokenConfig.put("kalturaTokenExpiryTime", expiresAt);
            kalturaConfigManager.updateConfiguration(updatedTokenConfig);
            LOG.info("Kaltura OSGi configuration updated with new tokens and expiry.");


            statusMessage = "Successfully connected to Kaltura and stored tokens.";
            statusType = "success";

        } catch (Exception e) {
            LOG.error("Error during OAuth callback processing or App Builder call", e);
            statusMessage = "Error processing OAuth callback: " + e.getMessage();
        } finally {
            if (httpClientBuilderFactory == null && httpClient != null) {
                httpClient.close();
            }
        }
        response.sendRedirect(ADMIN_UI_PATH + "?status=" + statusType + "&message=" + URLEncoder.encode(statusMessage, StandardCharsets.UTF_8.name()));
    }

    private void storeTokensInJcr(String accessToken, String refreshToken, long expiresAt) throws PersistenceException, LoginException, RepositoryException {
        Map<String, Object> serviceUserParams = Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, (Object) SERVICE_USER_NAME);
        try (ResourceResolver serviceResolver = resourceResolverFactory.getServiceResourceResolver(serviceUserParams)) {
            Resource tokenResource = serviceResolver.getResource(KALTURA_TOKENS_JCR_PATH);
            if (tokenResource == null) {
                // Create path if it doesn't exist
                String parentPath = KALTURA_TOKENS_JCR_PATH.substring(0, KALTURA_TOKENS_JCR_PATH.lastIndexOf('/'));
                String nodeName = KALTURA_TOKENS_JCR_PATH.substring(KALTURA_TOKENS_JCR_PATH.lastIndexOf('/') + 1);
                Resource parentResource = serviceResolver.getResource(parentPath);
                if (parentResource == null) {
                     // Minimalistic path creation, for robustness use JcrUtil or similar
                    String[] parts = parentPath.substring(1).split("/"); // remove leading /
                    Resource currentParent = serviceResolver.getResource("/");
                    for (String part : parts) {
                        Resource child = currentParent.getChild(part);
                        if (child == null) {
                            LOG.info("Creating JCR path segment: {} under {}", part, currentParent.getPath());
                            currentParent = serviceResolver.create(currentParent, part, Collections.singletonMap(Node.JCR_PRIMARYTYPE, "sling:Folder"));
                        } else {
                            currentParent = child;
                        }
                    }
                    parentResource = currentParent;
                }
                 LOG.info("Creating JCR node: {} under {}", nodeName, parentResource.getPath());
                tokenResource = serviceResolver.create(parentResource, nodeName,
                        Map.of(Node.JCR_PRIMARYTYPE, "nt:unstructured"));
            }

            ModifiableValueMap tokenProps = tokenResource.adaptTo(ModifiableValueMap.class);
            if (tokenProps == null) {
                throw new PersistenceException("Could not adapt token resource to ModifiableValueMap at path: " + KALTURA_TOKENS_JCR_PATH);
            }

            tokenProps.put("accessToken", accessToken);
            tokenProps.put("refreshToken", refreshToken);
            tokenProps.put("expiresAt", expiresAt); // Store as timestamp

            // For security, set ACLs on this node if not already handled by parent structure.
            // This is a simplified example. Real ACL setup might be more complex.
            Session session = serviceResolver.adaptTo(Session.class);
            if (session != null) {
                // Example: try to ensure only admins can read. This needs more robust ACL management in practice.
                // String principal = "administrators"; // or a specific service user/group
                // AccessControlUtil.replaceAccessControlEntry(session, KALTURA_TOKENS_JCR_PATH, principal,
                // new String[]{Privilege.JCR_READ}, true, null, null);
            } else {
                LOG.warn("Could not get JCR Session to set permissions on token node.");
            }

            serviceResolver.commit();
            LOG.info("Tokens stored successfully in JCR at {}", KALTURA_TOKENS_JCR_PATH);

        } catch (LoginException e) {
            LOG.error("LoginException: Could not get service resolver for {}. Ensure the service user is configured correctly.", SERVICE_USER_NAME, e);
            throw e;
        } catch (PersistenceException e) {
            LOG.error("PersistenceException while storing tokens in JCR at {}: {}", KALTURA_TOKENS_JCR_PATH, e.getMessage(), e);
            throw e;
        }
    }
     private CloseableHttpClient getHttpClient() {
        if (httpClientBuilderFactory != null) {
            return httpClientBuilderFactory.newBuilder().build();
        }
        LOG.warn("HttpClientBuilderFactory not available, creating default HttpClient. Consider configuring the Apache HTTP Components Client Factory.");
        return HttpClients.createDefault();
    }
}
