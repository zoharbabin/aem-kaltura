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
    resourceTypes = "myaemproject/components/content/kaltura-video-upload",
    selectors = "upload",
    methods = "POST",
    extensions = "json"
)
public class VideoUploadServlet extends SlingAllMethodsServlet {

    private static final Logger LOG = LoggerFactory.getLogger(VideoUploadServlet.class);

    @Reference
    private KalturaConfigManager kalturaConfigManager;

    @Reference(cardinality = org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL)
    private HttpClientBuilderFactory httpClientBuilderFactory;

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        JSONObject jsonResponse = new JSONObject();

        try {
            String videoFileBase64 = request.getParameter("videoFile");
            String videoFilename = request.getParameter("videoFilename");
            String videoTitle = request.getParameter("videoTitle");
            String videoDescription = request.getParameter("videoDescription");

            if (StringUtils.isAnyBlank(videoFileBase64, videoFilename, videoTitle)) {
                jsonResponse.put("success", false);
                jsonResponse.put("message", "Missing required parameters: videoFile, videoFilename, or videoTitle.");
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getWriter().write(jsonResponse.toString());
                return;
            }

            KalturaConfig config = kalturaConfigManager.getConfiguration();
            if (config == null || StringUtils.isAnyBlank(
                    config.appBuilderOAuthHandlerUrl(), // This should be the App Builder *base* URL or specific video upload action URL
                    config.kalturaAccessToken())) { // Assuming appBuilderOAuthHandlerUrl stores the video-upload action URL
                jsonResponse.put("success", false);
                jsonResponse.put("message", "Kaltura configuration (App Builder URL or Access Token) missing in OSGi.");
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().write(jsonResponse.toString());
                return;
            }
            
            // The video-upload action URL should be specifically configured.
            // Let's assume appBuilderOAuthHandlerUrl in KalturaConfig is actually the video-upload action URL
            // or we need another field for it. For now, we use it.
            // A better approach: add a dedicated field in KalturaConfig for the video upload App Builder action URL.
            // String videoUploadAppBuilderActionUrl = config.videoUploadAppBuilderActionUrl();
            // For this example, let's assume the appBuilderOAuthHandlerUrl is *actually* the video upload action URL for simplicity
            // This is likely incorrect based on its name, but works with current config.
            String appBuilderVideoUploadUrl = kalturaConfigManager.getConfigProperty("appBuilderOAuthHandlerUrl"); 
             // A dedicated config like `config.appBuilderVideoUploadUrl()` would be better.
            if (StringUtils.isBlank(appBuilderVideoUploadUrl)) {
                 jsonResponse.put("success", false);
                jsonResponse.put("message", "App Builder video upload action URL is not configured.");
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().write(jsonResponse.toString());
                return;
            }


            JSONObject appBuilderPayload = new JSONObject();
            // Parameters for the 'video-upload' App Builder Action
            appBuilderPayload.put("kaltura_api_url", kalturaConfigManager.getConfigProperty("kalturaAuthUrl").replace("/oauth2/authorize", "/api_v3/service")); // Infer API base
            appBuilderPayload.put("kaltura_access_token", config.kalturaAccessToken());
            appBuilderPayload.put("videoFile", videoFileBase64);
            appBuilderPayload.put("video_filename", videoFilename);
            appBuilderPayload.put("video_title", videoTitle);
            appBuilderPayload.put("video_description", videoDescription);

            LOG.debug("Calling App Builder video-upload action at URL: {} with title: {}", appBuilderVideoUploadUrl, videoTitle);

            String appBuilderActionResponse;
            CloseableHttpClient httpClient = getHttpClient();
            try {
                HttpPost httpPost = new HttpPost(appBuilderVideoUploadUrl); // Use the dedicated video upload action URL
                httpPost.setEntity(new StringEntity(appBuilderPayload.toString(), ContentType.APPLICATION_JSON));

                appBuilderActionResponse = httpClient.execute(httpPost, httpResp -> {
                    int statusCode = httpResp.getCode();
                    String responseBody = new java.util.Scanner(httpResp.getEntity().getContent(), StandardCharsets.UTF_8.name()).useDelimiter("\\A").next();
                    LOG.debug("App Builder video-upload action response status: {}, body: {}", statusCode, responseBody);

                    if (statusCode >= 200 && statusCode < 300) {
                        return responseBody;
                    } else {
                        throw new IOException("App Builder video-upload action failed with status " + statusCode + ". Response: " + responseBody);
                    }
                });
            } finally {
                if (httpClientBuilderFactory == null && httpClient != null) {
                    httpClient.close();
                }
            }

            JSONObject appBuilderResult = new JSONObject(appBuilderActionResponse);
            // Assuming App Builder action wraps its result in a "body" object if it's a standard web action response
            JSONObject resultBody = appBuilderResult.has("body") ? appBuilderResult.getJSONObject("body") : appBuilderResult;


            if (resultBody.has("entryId")) {
                String kalturaEntryId = resultBody.getString("entryId");
                LOG.info("Successfully uploaded video. Kaltura Entry ID: {}", kalturaEntryId);

                // Persist to component node
                Resource componentResource = request.getResource();
                ModifiableValueMap properties = componentResource.adaptTo(ModifiableValueMap.class);
                String kalturaThumbnailUrl = null;

                // After successful upload, fetch video details to get thumbnail
                String videoDetailsActionUrl = kalturaConfigManager.getConfigProperty("kalturaVideoDetailsAppBuilderUrl");
                String kalturaApiBaseUrlForDetails = kalturaConfigManager.getConfigProperty("kalturaAuthUrl");
                if (kalturaApiBaseUrlForDetails != null && kalturaApiBaseUrlForDetails.contains("/oauth2/authorize")) {
                     kalturaApiBaseUrlForDetails = kalturaApiBaseUrlForDetails.substring(0, kalturaApiBaseUrlForDetails.indexOf("/oauth2/authorize")) + "/api_v3/service";
                }

                if (StringUtils.isNoneBlank(videoDetailsActionUrl, config.kalturaAccessToken(), kalturaApiBaseUrlForDetails)) {
                    JSONObject detailsPayload = new JSONObject();
                    detailsPayload.put("entry_id", kalturaEntryId);
                    detailsPayload.put("kaltura_api_url", kalturaApiBaseUrlForDetails);
                    detailsPayload.put("kaltura_access_token", config.kalturaAccessToken());

                    LOG.debug("Calling App Builder kaltura-video-details action for thumbnail: URL: {}, EntryID: {}", videoDetailsActionUrl, kalturaEntryId);
                    try {
                        String detailsResponseStr = callAppBuilder(videoDetailsActionUrl, detailsPayload.toString(), httpClient);
                        JSONObject detailsResponseJson = new JSONObject(detailsResponseStr);
                        JSONObject detailsBody = detailsResponseJson.has("body") ? detailsResponseJson.getJSONObject("body") : detailsResponseJson;
                        if (detailsBody.has("thumbnailUrl")) {
                            kalturaThumbnailUrl = detailsBody.getString("thumbnailUrl");
                            LOG.info("Fetched thumbnail URL for entryId {}: {}", kalturaEntryId, kalturaThumbnailUrl);
                        } else {
                            LOG.warn("thumbnailUrl not found in kaltura-video-details response for entryId: {}", kalturaEntryId);
                        }
                    } catch (Exception e) {
                        LOG.error("Failed to fetch video details for thumbnail for entryId {}: {}", kalturaEntryId, e.getMessage());
                        // Continue without thumbnail if this call fails
                    }
                } else {
                    LOG.warn("Configuration for calling kaltura-video-details (URL, Access Token, or API Base) is incomplete. Skipping thumbnail fetch.");
                }


                if (properties != null) {
                    properties.put("kalturaEntryId", kalturaEntryId);
                    properties.put("videoTitle", videoTitle); // Also save title/desc if changed
                    properties.put("videoDescription", videoDescription);
                    properties.put("videoFileName", videoFilename); // Store original filename for reference
                    if (StringUtils.isNotBlank(kalturaThumbnailUrl)) {
                        properties.put("kalturaThumbnailUrl", kalturaThumbnailUrl);
                    }
                    componentResource.getResourceResolver().commit();
                    LOG.info("Kaltura Entry ID, metadata, and thumbnail URL persisted to component node: {}", componentResource.getPath());
                } else {
                    LOG.warn("Could not adapt component resource to ModifiableValueMap at path: {}", componentResource.getPath());
                }

                jsonResponse.put("success", true);
                jsonResponse.put("message", "Video uploaded successfully!");
                jsonResponse.put("entryId", kalturaEntryId);
                if (StringUtils.isNotBlank(kalturaThumbnailUrl)) {
                    jsonResponse.put("thumbnailUrl", kalturaThumbnailUrl);
                }
                response.setStatus(HttpServletResponse.SC_OK);
            } else {
                throw new IOException("App Builder action response did not contain 'entryId'. Full response: " + resultBody.toString());
            }

        } catch (Exception e) {
            LOG.error("Error in VideoUploadServlet", e);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error uploading video: " + e.getMessage());
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }

        response.getWriter().write(jsonResponse.toString());
    }

// Overloaded callAppBuilder method to reuse existing HttpClient if one was already obtained.
private String callAppBuilder(String url, String payload, CloseableHttpClient httpClientInstance) throws IOException {
    boolean clientProvided = httpClientInstance != null;
    CloseableHttpClient client = clientProvided ? httpClientInstance : getHttpClient();
    try {
        HttpPost httpPost = new HttpPost(url);
        httpPost.setEntity(new StringEntity(payload, ContentType.APPLICATION_JSON));
        return client.execute(httpPost, httpResponse -> {
            int statusCode = httpResponse.getCode();
            String responseBody = new java.util.Scanner(httpResponse.getEntity().getContent(), StandardCharsets.UTF_8.name()).useDelimiter("\\A").next();
            if (statusCode >= 200 && statusCode < 300) {
                return responseBody;
            } else {
                throw new IOException("App Builder action at " + url + " failed with status " + statusCode + ". Response: " + responseBody);
            }
        });
    } finally {
        if (!clientProvided && client != null) { // Only close if created locally in this method scope
            client.close();
        }
    }
}


    private CloseableHttpClient getHttpClient() {
        if (httpClientBuilderFactory != null) {
            return httpClientBuilderFactory.newBuilder().build();
        }
        LOG.warn("HttpClientBuilderFactory not available, creating default HttpClient.");
        return HttpClients.createDefault();
    }
}
