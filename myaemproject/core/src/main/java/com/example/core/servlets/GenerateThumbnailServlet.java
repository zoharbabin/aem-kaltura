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
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component(service = Servlet.class)
@SlingServletResourceTypes(
    resourceTypes = "myaemproject/components/content/kaltura-video-player",
    selectors = "generateThumbnail",
    methods = "POST",
    extensions = "json"
)
public class GenerateThumbnailServlet extends SlingAllMethodsServlet {

    private static final Logger LOG = LoggerFactory.getLogger(GenerateThumbnailServlet.class);

    @Reference
    private KalturaConfigManager kalturaConfigManager;

    @Reference(cardinality = org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL)
    private HttpClientBuilderFactory httpClientBuilderFactory;

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        JSONObject jsonResponse = new JSONObject();

        Resource componentResource = request.getResource();
        String entryIdFromResource = componentResource.getValueMap().get("kalturaEntryId", String.class);
        String timecodeMsStr = request.getParameter("timecodeMs");
        // String entryIdFromRequest = request.getParameter("entryId"); // Optional, could use for validation

        if (StringUtils.isBlank(entryIdFromResource)) {
            jsonResponse.put("success", false).put("message", "Kaltura Entry ID not found on component resource.");
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write(jsonResponse.toString());
            return;
        }

        if (StringUtils.isBlank(timecodeMsStr)) {
            jsonResponse.put("success", false).put("message", "Timecode (timecodeMs) parameter is required.");
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write(jsonResponse.toString());
            return;
        }
        long timecodeMs;
        try {
            timecodeMs = Long.parseLong(timecodeMsStr);
            if (timecodeMs < 0) throw new NumberFormatException("Timecode must be non-negative.");
        } catch (NumberFormatException e) {
            jsonResponse.put("success", false).put("message", "Invalid timecode format: " + e.getMessage());
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write(jsonResponse.toString());
            return;
        }

        KalturaConfig config = kalturaConfigManager.getConfiguration();
        if (config == null) {
            jsonResponse.put("success", false).put("message", "Kaltura OSGi configuration is missing.");
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write(jsonResponse.toString());
            return;
        }

        String generateThumbnailActionUrl = kalturaConfigManager.getConfigProperty("kalturaGenerateThumbnailAppBuilderUrl");
        String kalturaApiBaseUrl = kalturaConfigManager.getConfigProperty("kalturaAuthUrl");
         if (kalturaApiBaseUrl != null && kalturaApiBaseUrl.contains("/oauth2/authorize")) {
             kalturaApiBaseUrl = kalturaApiBaseUrl.substring(0, kalturaApiBaseUrl.indexOf("/oauth2/authorize")) + "/api_v3/service";
        }
        String accessToken = config.kalturaAccessToken();

        if (StringUtils.isAnyBlank(generateThumbnailActionUrl, kalturaApiBaseUrl, accessToken)) {
            jsonResponse.put("success", false).put("message", "Kaltura configuration for App Builder (Generate Thumbnail URL, API Base URL, or Access Token) is missing.");
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write(jsonResponse.toString());
            return;
        }

        JSONObject appBuilderPayload = new JSONObject();
        appBuilderPayload.put("entry_id", entryIdFromResource);
        appBuilderPayload.put("timecode_ms", timecodeMs);
        appBuilderPayload.put("kaltura_api_url", kalturaApiBaseUrl);
        appBuilderPayload.put("kaltura_access_token", accessToken);
        appBuilderPayload.put("set_as_default", true); // Always set as default from this servlet

        LOG.debug("Calling App Builder kaltura-generate-thumbnail action: URL: {}, Payload: {}", generateThumbnailActionUrl, appBuilderPayload.toString());

        CloseableHttpClient httpClient = getHttpClient();
        try {
            HttpPost httpPost = new HttpPost(generateThumbnailActionUrl);
            httpPost.setEntity(new StringEntity(appBuilderPayload.toString(), ContentType.APPLICATION_JSON));

            String appBuilderResponseStr = httpClient.execute(httpPost, httpResp -> {
                int statusCode = httpResp.getCode();
                String responseBody = new java.util.Scanner(httpResp.getEntity().getContent(), StandardCharsets.UTF_8).useDelimiter("\\A").next();
                LOG.debug("App Builder kaltura-generate-thumbnail response status: {}, body: {}", statusCode, responseBody);
                if (statusCode >= 200 && statusCode < 300) {
                    return responseBody;
                } else {
                    throw new IOException("App Builder kaltura-generate-thumbnail action failed with status " + statusCode + ". Response: " + responseBody);
                }
            });

            JSONObject appBuilderResponseJson = new JSONObject(appBuilderResponseStr);
            JSONObject responseBody = appBuilderResponseJson.has("body") ? appBuilderResponseJson.getJSONObject("body") : appBuilderResponseJson;

            if (responseBody.has("error") || !"success".equals(responseBody.optString("status"))) {
                 String errorMessage = responseBody.has("error") ? responseBody.getString("error") : "Unknown error from App Builder.";
                 if (responseBody.has("details")) errorMessage += " Details: " + responseBody.getString("details");
                throw new IOException(errorMessage);
            }
            
            String newThumbnailUrl = responseBody.optString("new_thumbnail_url", null);

            if (StringUtils.isBlank(newThumbnailUrl)) {
                 throw new IOException("App Builder action did not return a new_thumbnail_url.");
            }

            ModifiableValueMap properties = componentResource.adaptTo(ModifiableValueMap.class);
            if (properties != null) {
                properties.put("kalturaThumbnailUrl", newThumbnailUrl);
                // Optionally update a "lastGeneratedThumbnailTimecode" or similar if needed
                // properties.put("lastThumbnailTimecodeMs", timecodeMs);
                componentResource.getResourceResolver().commit();
                LOG.info("Successfully updated kalturaThumbnailUrl for component {} to: {}", componentResource.getPath(), newThumbnailUrl);
            } else {
                // This should ideally not happen if the resource is valid
                throw new IOException("Could not adapt component resource to ModifiableValueMap at path: " + componentResource.getPath());
            }

            jsonResponse.put("success", true);
            jsonResponse.put("message", "New thumbnail generated and set as default successfully.");
            jsonResponse.put("new_thumbnail_url", newThumbnailUrl);
            jsonResponse.put("entryId", entryIdFromResource);
            response.setStatus(HttpServletResponse.SC_OK);

        } catch (Exception e) {
            LOG.error("Error in GenerateThumbnailServlet for component {}: {}", componentResource.getPath(), e.getMessage(), e);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error generating thumbnail: " + e.getMessage());
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            // Revert JCR changes if any were made before the error
            if (componentResource.getResourceResolver().hasChanges()) {
                componentResource.getResourceResolver().revert();
            }
        } finally {
            if (httpClientBuilderFactory == null && httpClient != null) {
                httpClient.close();
            }
        }
        response.getWriter().write(jsonResponse.toString());
    }

    private CloseableHttpClient getHttpClient() {
        if (httpClientBuilderFactory != null) {
            return httpClientBuilderFactory.newBuilder().build();
        }
        LOG.warn("HttpClientBuilderFactory OSGi service not available. Creating default HttpClient instance.");
        return HttpClients.createDefault();
    }
}
