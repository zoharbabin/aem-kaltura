package com.example.core.servlets;

import com.example.core.services.KalturaConfigManager;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.*;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletPaths;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

@Component(service = Servlet.class)
@SlingServletPaths("/bin/myaemproject/kaltura/event-receiver")
public class KalturaWebhookReceiverServlet extends SlingAllMethodsServlet {

    private static final Logger LOG = LoggerFactory.getLogger(KalturaWebhookReceiverServlet.class);
    private static final String HEADER_API_KEY = "X-Kaltura-Webhook-Api-Key";
    public static final String SERVICE_USER_NAME = "myaemproject-serviceuser"; // Defined in previous tasks

    @Reference
    private KalturaConfigManager kalturaConfigManager;

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        JSONObject jsonResponse = new JSONObject();

        String configuredApiKey = kalturaConfigManager.getConfigProperty("kalturaWebhookApiKey");
        String requestApiKey = request.getHeader(HEADER_API_KEY);

        if (StringUtils.isBlank(configuredApiKey) || !configuredApiKey.equals(requestApiKey)) {
            LOG.warn("Invalid or missing API key received for Kaltura webhook. Configured: {}, Received: {}",
                    StringUtils.isNotBlank(configuredApiKey) ? "Present" : "Missing", requestApiKey);
            jsonResponse.put("status", "error");
            jsonResponse.put("message", "Unauthorized: Invalid or missing API key.");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write(jsonResponse.toString());
            return;
        }

        String requestBody = IOUtils.toString(request.getReader());
        if (StringUtils.isBlank(requestBody)) {
            jsonResponse.put("status", "error");
            jsonResponse.put("message", "Bad Request: Empty payload.");
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write(jsonResponse.toString());
            return;
        }

        LOG.debug("Received Kaltura event payload: {}", requestBody);

        try {
            JSONObject payload = new JSONObject(requestBody);
            String entryId = payload.optString("entryId", null);
            String eventType = payload.optString("eventType", "UNKNOWN");

            if (StringUtils.isBlank(entryId)) {
                jsonResponse.put("status", "error");
                jsonResponse.put("message", "Bad Request: Missing 'entryId' in payload.");
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getWriter().write(jsonResponse.toString());
                return;
            }

            LOG.info("Processing event '{}' for Kaltura Entry ID: {}", eventType, entryId);

            // Example: For a 'delete' event, you might want to mark components as deleted or remove entryId.
            // For 'update' or 'flavor_ready', you update metadata.
            // This example focuses on updating metadata similar to the sync job.

            Map<String, Object> serviceUserParams = Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, (Object) SERVICE_USER_NAME);
            try (ResourceResolver serviceResolver = resourceResolverFactory.getServiceResourceResolver(serviceUserParams)) {
                // Query for components matching the entryId
                // Using the same resource types as the sync job for consistency
                String[] resourceTypes = kalturaConfigManager.getConfiguration() != null ?
                                         new String[]{"myaemproject/components/content/kaltura-video-player", "myaemproject/components/content/kaltura-video-upload"} : // Fallback if config is complex
                                         new String[0]; // Or get from a new config specific to this servlet if needed

                if (resourceTypes.length == 0) {
                     LOG.warn("No component resource types configured for webhook updates. Check KalturaMetadataSyncJobConfig or add specific config.");
                }


                // Simplified query - in a real scenario, use searchPaths like in the sync job for performance.
                // For brevity, this example queries globally for the entryId and resourceType.
                // A more robust query would be:
                // SELECT * FROM [nt:unstructured] AS s WHERE s.[kalturaEntryId] = '<entryId>' AND (s.[sling:resourceType] = 'type1' OR s.[sling:resourceType] = 'type2')
                // This simple query might be slow on large repositories if not indexed.
                
                StringBuilder queryBuilder = new StringBuilder("SELECT * FROM [nt:unstructured] AS s WHERE s.[kalturaEntryId] = '");
                queryBuilder.append(entryId.replace("'", "''")); // Basic SQL injection prevention for entryId
                queryBuilder.append("'");

                if (resourceTypes.length > 0) {
                    queryBuilder.append(" AND (");
                    for (int i = 0; i < resourceTypes.length; i++) {
                        queryBuilder.append("s.[sling:resourceType] = '").append(resourceTypes[i]).append("'");
                        if (i < resourceTypes.length - 1) {
                            queryBuilder.append(" OR ");
                        }
                    }
                    queryBuilder.append(")");
                }
                
                String query = queryBuilder.toString();
                LOG.debug("Executing JCR Query for webhook update: {}", query);
                Iterator<Resource> foundResources = serviceResolver.findResources(query, "JCR-SQL2");

                int updatedCount = 0;
                if (!foundResources.hasNext()) {
                    LOG.info("No AEM components found with Kaltura Entry ID: {}", entryId);
                }

                while (foundResources.hasNext()) {
                    Resource componentResource = foundResources.next();
                    ModifiableValueMap properties = componentResource.adaptTo(ModifiableValueMap.class);
                    if (properties == null) {
                        LOG.warn("Could not get ModifiableValueMap for resource: {}. Skipping update.", componentResource.getPath());
                        continue;
                    }

                    boolean changed = false;
                    String kalturaTitle = payload.optString("title", null);
                    if (kalturaTitle != null && !kalturaTitle.equals(properties.get("videoTitle", String.class))) {
                        properties.put("videoTitle", kalturaTitle);
                        changed = true;
                    }

                    String kalturaDescription = payload.optString("description", null);
                    if (kalturaDescription != null && !kalturaDescription.equals(properties.get("videoDescription", String.class))) {
                        properties.put("videoDescription", kalturaDescription);
                        changed = true;
                    }
                    
                    long kalturaDuration = payload.optLong("duration", 0);
                    if (kalturaDuration > 0 && kalturaDuration != properties.get("kalturaDuration", 0L)) {
                        properties.put("kalturaDuration", kalturaDuration);
                        changed = true;
                    }

                    String kalturaThumbnailUrl = payload.optString("thumbnailUrl", null);
                     if (kalturaThumbnailUrl != null && !kalturaThumbnailUrl.equals(properties.get("kalturaThumbnailUrl", String.class))) {
                        properties.put("kalturaThumbnailUrl", kalturaThumbnailUrl);
                        changed = true;
                    }
                    // Potentially update other fields like tags, playbackUrls if the kaltura-video-details model stores them
                    // and if the payload from App Builder includes them.

                    if (changed) {
                        serviceResolver.commit();
                        updatedCount++;
                        LOG.info("Updated metadata for component at {} due to webhook event for Entry ID: {}", componentResource.getPath(), entryId);
                    } else {
                        LOG.info("No metadata changes required for component at {} for Entry ID: {}", componentResource.getPath(), entryId);
                    }
                }

                jsonResponse.put("status", "success");
                jsonResponse.put("message", "Processed event for entryId " + entryId + ". Updated " + updatedCount + " component(s).");
                response.setStatus(HttpServletResponse.SC_OK);

            } catch (LoginException e) {
                LOG.error("LoginException for service user {}: {}", SERVICE_USER_NAME, e.getMessage(), e);
                jsonResponse.put("status", "error");
                jsonResponse.put("message", "Internal Server Error: Could not get service user session.");
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            } catch (PersistenceException e) {
                 LOG.error("PersistenceException while updating component for entryId {}: {}", entryId, e.getMessage(), e);
                jsonResponse.put("status", "error");
                jsonResponse.put("message", "Internal Server Error: Could not save updates to component.");
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                if (serviceResolver.hasChanges()) serviceResolver.revert();
            }


        } catch (Exception e) {
            LOG.error("Error processing Kaltura webhook payload: {}", e.getMessage(), e);
            jsonResponse.put("status", "error");
            jsonResponse.put("message", "Internal Server Error: " + e.getMessage());
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }

        response.getWriter().write(jsonResponse.toString());
    }
}
