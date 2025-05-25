package com.example.core.models;

import com.example.core.services.KalturaConfigManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client5.http.classic.methods.HttpPost;
import org.apache.http.client5.http.impl.classic.CloseableHttpClient;
import org.apache.http.client5.http.impl.classic.HttpClients;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.osgi.services.HttpClientBuilderFactory;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.OSGiService;
import org.apache.sling.models.annotations.injectorspecific.ScriptVariable;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Comparator;


@Model(adaptables = {Resource.class, SlingHttpServletRequest.class},
       defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class KalturaVideoPlayerModel {

    private static final Logger LOG = LoggerFactory.getLogger(KalturaVideoPlayerModel.class);

    @ValueMapValue
    private String kalturaEntryId;

    @OSGiService
    private KalturaConfigManager kalturaConfigManager;
    
    // Optional: Use OSGi service for HttpClient factory if available and preferred.
    @OSGiService(filter = "(component.name=org.apache.http.osgi.impl.HttpClientBuilderFactoryImpl)")
    private HttpClientBuilderFactory httpClientBuilderFactory;


    private String title;
    private String description;
    private long duration; // in seconds
    private String thumbnailUrl;
    private List<PlaybackUrl> playbackUrls = new ArrayList<>();
    private String errorMessage;

    @PostConstruct
    protected void init() {
        if (StringUtils.isBlank(kalturaEntryId)) {
            errorMessage = "Kaltura Entry ID is not configured for this component.";
            LOG.warn(errorMessage);
            return;
        }

        if (kalturaConfigManager == null) {
            errorMessage = "KalturaConfigManager service is unavailable.";
            LOG.error(errorMessage);
            return;
        }
        
        String videoDetailsUrl = kalturaConfigManager.getConfigProperty("kalturaVideoDetailsAppBuilderUrl");
        String accessToken = kalturaConfigManager.getConfigProperty("kalturaAccessToken");
        // Infer API base URL from one of the existing full URLs, e.g., auth URL
        String kalturaApiBaseUrl = kalturaConfigManager.getConfigProperty("kalturaAuthUrl");
        if (kalturaApiBaseUrl != null && kalturaApiBaseUrl.contains("/oauth2/authorize")) {
             kalturaApiBaseUrl = kalturaApiBaseUrl.substring(0, kalturaApiBaseUrl.indexOf("/oauth2/authorize")) + "/api_v3/service";
        } else {
            // Fallback or error if base URL cannot be determined
            LOG.error("Could not determine Kaltura API base URL from kalturaAuthUrl: {}", kalturaConfigManager.getConfigProperty("kalturaAuthUrl"));
            errorMessage = "Kaltura API base URL configuration is invalid.";
            return;
        }


        if (StringUtils.isAnyBlank(videoDetailsUrl, accessToken, kalturaApiBaseUrl)) {
            errorMessage = "Kaltura configuration (Video Details URL, Access Token, or API Base URL) is missing in OSGi.";
            LOG.error(errorMessage + " Details URL: {}, AccessToken: {}, API Base: {}", videoDetailsUrl, StringUtils.isNotBlank(accessToken), kalturaApiBaseUrl);
            return;
        }

        JSONObject appBuilderPayload = new JSONObject();
        appBuilderPayload.put("entry_id", kalturaEntryId);
        appBuilderPayload.put("kaltura_api_url", kalturaApiBaseUrl); // Pass the base API URL
        appBuilderPayload.put("kaltura_access_token", accessToken);

        LOG.debug("Calling App Builder kaltura-video-details action at URL: {} for entryId: {}", videoDetailsUrl, kalturaEntryId);

        CloseableHttpClient httpClient = getHttpClient();
        try {
            HttpPost httpPost = new HttpPost(videoDetailsUrl);
            httpPost.setEntity(new StringEntity(appBuilderPayload.toString(), ContentType.APPLICATION_JSON));

            String appBuilderResponse = httpClient.execute(httpPost, httpResp -> {
                int statusCode = httpResp.getCode();
                String responseBody = new java.util.Scanner(httpResp.getEntity().getContent(), StandardCharsets.UTF_8).useDelimiter("\\A").next();
                LOG.debug("App Builder kaltura-video-details response status: {}, body: {}", statusCode, responseBody);
                if (statusCode >= 200 && statusCode < 300) {
                    return responseBody;
                } else {
                    throw new IOException("App Builder kaltura-video-details action failed with status " + statusCode + ". Response: " + responseBody);
                }
            });

            JSONObject jsonResponse = new JSONObject(appBuilderResponse);
            // App Builder actions typically wrap their response in a "body" object if they are web actions
            JSONObject videoData = jsonResponse.has("body") ? jsonResponse.getJSONObject("body") : jsonResponse;


            if (videoData.has("error")) { // Check for logical errors from the action
                 errorMessage = "Error from video details service: " + videoData.getString("error") + (videoData.has("details") ? " - " + videoData.getString("details") : "");
                 LOG.error(errorMessage);
                 return;
            }

            this.title = videoData.optString("title", "Untitled Video");
            this.description = videoData.optString("description", "");
            this.duration = videoData.optLong("duration", 0);
            this.thumbnailUrl = videoData.optString("thumbnailUrl", null);

            JSONArray urlsArray = videoData.optJSONArray("playbackUrls");
            if (urlsArray != null) {
                for (int i = 0; i < urlsArray.length(); i++) {
                    JSONObject urlObj = urlsArray.getJSONObject(i);
                    playbackUrls.add(new PlaybackUrl(
                        urlObj.optString("url"),
                        urlObj.optString("mimeType", "video/mp4"), // Default to video/mp4 if not specified
                        urlObj.optInt("width"),
                        urlObj.optInt("height"),
                        urlObj.optString("type", "mp4") // fileExt or similar
                    ));
                }
                // Sort playback URLs: HLS/DASH first, then by quality (height)
                Collections.sort(playbackUrls, new PlaybackUrlComparator());
            } else {
                LOG.warn("No playbackUrls array found in response for entryId: {}", kalturaEntryId);
            }
            if (playbackUrls.isEmpty()) {
                 LOG.warn("Playback URLs list is empty for entryId: {}", kalturaEntryId);
                 // errorMessage = "No playable video formats found for this entry."; // Optional: be more specific
            }


        } catch (Exception e) {
            LOG.error("Error fetching video details from App Builder or parsing response for entryId: " + kalturaEntryId, e);
            errorMessage = "Could not retrieve video details: " + e.getMessage();
        } finally {
             if (httpClientBuilderFactory == null && httpClient != null) { // Only close if manually created
                try {
                    httpClient.close();
                } catch (IOException e) {
                    LOG.error("Error closing HttpClient", e);
                }
            }
        }
    }
    
    private CloseableHttpClient getHttpClient() {
        if (httpClientBuilderFactory != null) {
            return httpClientBuilderFactory.newBuilder().build();
        }
        LOG.warn("HttpClientBuilderFactory OSGi service not available. Creating default HttpClient instance.");
        return HttpClients.createDefault();
    }

    public String getKalturaEntryId() {
        return kalturaEntryId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public long getDuration() {
        return duration;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public List<PlaybackUrl> getPlaybackUrls() {
        return playbackUrls;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isReady() {
        return StringUtils.isBlank(errorMessage) && StringUtils.isNotBlank(kalturaEntryId) && !playbackUrls.isEmpty();
    }

    // Inner class for Playback URL details
    public static class PlaybackUrl {
        private final String url;
        private final String type; // Mime type
        private final int width;
        private final int height;
        private final String formatType; // e.g. 'hls', 'dash', 'mp4' (derived from original 'type' or tags)


        public PlaybackUrl(String url, String type, int width, int height, String formatType) {
            this.url = url;
            this.type = type;
            this.width = width;
            this.height = height;
            this.formatType = formatType.toLowerCase();
        }

        public String getUrl() {
            return url;
        }

        public String getType() {
            return type;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }
        public String getFormatType() {
            return formatType;
        }
    }

    // Comparator to sort playback URLs
    private static class PlaybackUrlComparator implements Comparator<PlaybackUrl> {
        @Override
        public int compare(PlaybackUrl o1, PlaybackUrl o2) {
            // Prioritize HLS/DASH
            boolean o1IsStreaming = o1.getFormatType().equals("m3u8") || o1.getFormatType().equals("mpd");
            boolean o2IsStreaming = o2.getFormatType().equals("m3u8") || o2.getFormatType().equals("mpd");

            if (o1IsStreaming && !o2IsStreaming) return -1;
            if (!o1IsStreaming && o2IsStreaming) return 1;

            // If both are streaming or both are not, sort by height (descending)
            return Integer.compare(o2.getHeight(), o1.getHeight());
        }
    }
}
