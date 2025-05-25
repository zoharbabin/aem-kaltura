package com.example.core.schedulers;

import com.example.core.config.KalturaConfig;
import com.example.core.services.KalturaConfigManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client5.http.classic.methods.HttpPost;
import org.apache.http.client5.http.impl.classic.CloseableHttpClient;
import org.apache.http.client5.http.impl.classic.HttpClients;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.osgi.services.HttpClientBuilderFactory;
import org.apache.sling.api.resource.*;
import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.json.JSONObject;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Component(service = Runnable.class, immediate = true)
@Designate(ocd = KalturaMetadataSyncJobConfig.class)
public class KalturaMetadataSyncJob implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(KalturaMetadataSyncJob.class);
    public static final String SERVICE_USER_NAME = "myaemproject-serviceuser"; // Defined in previous tasks

    @Reference
    private Scheduler scheduler;

    @Reference
    private KalturaConfigManager kalturaConfigManager;

    @Reference
    private ResourceResolverFactory resourceResolverFactory;
    
    @Reference(cardinality = ReferenceCardinality.OPTIONAL)
    private HttpClientBuilderFactory httpClientBuilderFactory;


    private volatile KalturaMetadataSyncJobConfig config;

    @Activate
    protected void activate(KalturaMetadataSyncJobConfig config) {
        this.config = config;
        LOG.info("KalturaMetadataSyncJob activated with cron expression: '{}', enabled: {}",
                 config.scheduler_expression(), config.syncEnabled());
        // Schedule options can also be configured here if not using the @Component properties directly for scheduling
    }

    @Modified
    protected void modified(KalturaMetadataSyncJobConfig config) {
        // Re-schedule if configuration changes, though @Component properties handle this for scheduler.*
        this.config = config;
        LOG.info("KalturaMetadataSyncJob modified. New cron: '{}', enabled: {}",
                 config.scheduler_expression(), config.syncEnabled());
    }

    @Deactivate
    protected void deactivate() {
        LOG.info("KalturaMetadataSyncJob deactivated.");
    }

    @Override
    public void run() {
        if (config == null || !config.syncEnabled()) {
            LOG.info("Kaltura Metadata Sync Job is disabled. Skipping execution.");
            return;
        }

        LOG.info("Kaltura Metadata Sync Job started.");

        Map<String, Object> serviceUserParams = Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, (Object) SERVICE_USER_NAME);
        try (ResourceResolver serviceResolver = resourceResolverFactory.getServiceResourceResolver(serviceUserParams)) {
            
            KalturaConfig globalKalturaConfig = kalturaConfigManager.getConfiguration();
            if (globalKalturaConfig == null) {
                LOG.error("Global Kaltura configuration (KalturaConfigManager) is not available. Cannot proceed.");
                return;
            }
            String videoDetailsUrl = kalturaConfigManager.getConfigProperty("kalturaVideoDetailsAppBuilderUrl");
            String accessToken = globalKalturaConfig.kalturaAccessToken();
            String kalturaApiBaseUrl = kalturaConfigManager.getConfigProperty("kalturaAuthUrl");
             if (kalturaApiBaseUrl != null && kalturaApiBaseUrl.contains("/oauth2/authorize")) {
                 kalturaApiBaseUrl = kalturaApiBaseUrl.substring(0, kalturaApiBaseUrl.indexOf("/oauth2/authorize")) + "/api_v3/service";
            } else {
                LOG.error("Could not determine Kaltura API base URL from kalturaAuthUrl: {}. Cannot proceed.", kalturaConfigManager.getConfigProperty("kalturaAuthUrl"));
                return;
            }

            if (StringUtils.isAnyBlank(videoDetailsUrl, accessToken, kalturaApiBaseUrl)) {
                LOG.error("Kaltura configuration for App Builder (Video Details URL, Access Token, or API Base URL) is missing. Cannot proceed.");
                return;
            }

            String[] searchPaths = config.searchPaths();
            String[] resourceTypes = config.componentResourceTypes();

            if (searchPaths == null || searchPaths.length == 0 || resourceTypes == null || resourceTypes.length == 0) {
                LOG.warn("Search paths or component resource types are not configured. Skipping query.");
                return;
            }
            
            StringBuilder queryBuilder = new StringBuilder("SELECT * FROM [nt:unstructured] AS s WHERE ISDESCENDANTNODE(s, '");
            // Building the query string
            // Example: SELECT * FROM [nt:unstructured] AS s WHERE (ISDESCENDANTNODE(s, '/content/myaemproject') OR ISDESCENDANTNODE(s, '/content/anotherpath'))
            // AND s.[kalturaEntryId] IS NOT NULL
            // AND (s.[sling:resourceType] = 'myaemproject/components/content/kaltura-video-player' OR s.[sling:resourceType] = 'myaemproject/components/content/kaltura-video-upload')

            queryBuilder.append(StringUtils.join(searchPaths, "') OR ISDESCENDANTNODE(s, '"));
            queryBuilder.append("') AND s.[kalturaEntryId] IS NOT NULL AND (");

            for (int i = 0; i < resourceTypes.length; i++) {
                queryBuilder.append("s.[sling:resourceType] = '").append(resourceTypes[i]).append("'");
                if (i < resourceTypes.length - 1) {
                    queryBuilder.append(" OR ");
                }
            }
            queryBuilder.append(")");

            String query = queryBuilder.toString();
            LOG.debug("Executing JCR Query: {}", query);
            Iterator<Resource> foundResources = serviceResolver.findResources(query, "JCR-SQL2");

            int updatedCount = 0;
            int processedCount = 0;

            while (foundResources.hasNext()) {
                Resource componentResource = foundResources.next();
                processedCount++;
                ValueMap properties = componentResource.getValueMap();
                String entryId = properties.get("kalturaEntryId", String.class);

                if (StringUtils.isBlank(entryId)) {
                    LOG.warn("Found component at {} with null or blank kalturaEntryId, skipping.", componentResource.getPath());
                    continue;
                }

                LOG.debug("Processing component: {} with Kaltura Entry ID: {}", componentResource.getPath(), entryId);

                try {
                    JSONObject appBuilderPayload = new JSONObject();
                    appBuilderPayload.put("entry_id", entryId);
                    appBuilderPayload.put("kaltura_api_url", kalturaApiBaseUrl);
                    appBuilderPayload.put("kaltura_access_token", accessToken);

                    String appBuilderResponse = callAppBuilder(videoDetailsUrl, appBuilderPayload.toString());
                    JSONObject videoData = new JSONObject(appBuilderResponse);
                    JSONObject videoDetails = videoData.has("body") ? videoData.getJSONObject("body") : videoData;
                    
                    if (videoDetails.has("error")) {
                        LOG.error("Error from App Builder for entryId {}: {} - {}", entryId, videoDetails.getString("error"), videoDetails.optString("details"));
                        continue;
                    }

                    ModifiableValueMap modifiableProperties = componentResource.adaptTo(ModifiableValueMap.class);
                    if (modifiableProperties == null) {
                        LOG.warn("Could not get ModifiableValueMap for resource: {}. Skipping update.", componentResource.getPath());
                        continue;
                    }

                    boolean updated = false;
                    // Compare and update title
                    String kalturaTitle = videoDetails.optString("title");
                    if (StringUtils.isNotBlank(kalturaTitle) && !kalturaTitle.equals(properties.get("videoTitle", String.class))) {
                        modifiableProperties.put("videoTitle", kalturaTitle);
                        // For components that might use jcr:title directly (like the player component if it was based on page)
                        // modifiableProperties.put("jcr:title", kalturaTitle); 
                        updated = true;
                        LOG.info("Updating title for component {} (Entry ID: {}) to: '{}'", componentResource.getPath(), entryId, kalturaTitle);
                    }

                    // Compare and update description
                    String kalturaDescription = videoDetails.optString("description");
                    if (kalturaDescription != null && !kalturaDescription.equals(properties.get("videoDescription", String.class))) {
                        // Allow blank description to be synced if it was previously non-blank
                        modifiableProperties.put("videoDescription", kalturaDescription);
                        // modifiableProperties.put("jcr:description", kalturaDescription);
                        updated = true;
                        LOG.info("Updating description for component {} (Entry ID: {})", componentResource.getPath(), entryId);
                    }
                    
                    // Optionally sync duration and thumbnail
                    long kalturaDuration = videoDetails.optLong("duration", 0);
                    if (kalturaDuration > 0 && kalturaDuration != properties.get("kalturaDuration", 0L)) {
                        modifiableProperties.put("kalturaDuration", kalturaDuration);
                        updated = true;
                        LOG.info("Updating duration for component {} (Entry ID: {}) to: {}", componentResource.getPath(), entryId, kalturaDuration);
                    }

                    String kalturaThumbnailUrl = videoDetails.optString("thumbnailUrl");
                     if (StringUtils.isNotBlank(kalturaThumbnailUrl) && !kalturaThumbnailUrl.equals(properties.get("kalturaThumbnailUrl", String.class))) {
                        modifiableProperties.put("kalturaThumbnailUrl", kalturaThumbnailUrl);
                        updated = true;
                        LOG.info("Updating thumbnail URL for component {} (Entry ID: {})", componentResource.getPath(), entryId);
                    }


                    if (updated) {
                        serviceResolver.commit(); // Commit changes for this resource
                        updatedCount++;
                        LOG.info("Committed metadata updates for component: {} (Entry ID: {})", componentResource.getPath(), entryId);
                    }

                } catch (Exception e) {
                    LOG.error("Error processing entry ID {} for component {}: {}", entryId, componentResource.getPath(), e.getMessage(), e);
                    if (serviceResolver.hasChanges()) {
                        serviceResolver.revert(); // Revert changes for this specific resource if an error occurs mid-update
                    }
                }
            }
            LOG.info("Kaltura Metadata Sync Job finished. Processed {} components, updated {} components.", processedCount, updatedCount);

        } catch (LoginException e) {
            LOG.error("LoginException: Could not get service resolver for {}. Ensure the service user is configured correctly.", SERVICE_USER_NAME, e);
        } catch (Exception e) {
            LOG.error("General error in Kaltura Metadata Sync Job: {}", e.getMessage(), e);
        }
    }

    private String callAppBuilder(String url, String payload) throws IOException {
        CloseableHttpClient httpClient = getHttpClient();
        try {
            HttpPost httpPost = new HttpPost(url);
            httpPost.setEntity(new StringEntity(payload, ContentType.APPLICATION_JSON));
            return httpClient.execute(httpPost, httpResponse -> {
                int statusCode = httpResponse.getCode();
                String responseBody = new java.util.Scanner(httpResponse.getEntity().getContent(), StandardCharsets.UTF_8).useDelimiter("\\A").next();
                if (statusCode >= 200 && statusCode < 300) {
                    return responseBody;
                } else {
                    throw new IOException("App Builder action failed with status " + statusCode + ". Response: " + responseBody);
                }
            });
        } finally {
             if (httpClientBuilderFactory == null && httpClient != null) {
                httpClient.close();
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
}
