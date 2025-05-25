const fetch = require('node-fetch');

// Helper to invoke another App Builder action (kaltura-video-details)
// In a real App Builder app, you might use the `appbuilder-sdk` or direct URL invocation.
// For simplicity, we'll assume direct URL invocation if the URL is known/configured.
// If kaltura-video-details is within the same app, its URL can be constructed or discovered.
async function getVideoDetails(params, videoDetailsActionUrl, kalturaApiUrl, kalturaAccessToken, entryId) {
    if (!videoDetailsActionUrl) {
        // Fallback or direct implementation if URL not provided
        console.warn("videoDetailsActionUrl not provided to kaltura-event-handler. Consider direct Kaltura API call if needed.");
        // As a simplified fallback, one might directly call Kaltura's media.get here,
        // but ideally, it reuses the dedicated kaltura-video-details action.
        // This example will proceed assuming it might not get full details if this action is not callable.
        return { entryId: entryId, title: "Title (details not fetched)", description: "Description (details not fetched)" };
    }

    console.log(`Calling kaltura-video-details action at ${videoDetailsActionUrl} for entryId ${entryId}`);
    const actionParams = {
        kaltura_api_url: kalturaApiUrl, // This needs to be passed or configured
        kaltura_access_token: kalturaAccessToken, // This needs to be passed or configured
        entry_id: entryId
    };

    try {
        const response = await fetch(videoDetailsActionUrl, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(actionParams)
        });
        if (!response.ok) {
            const errorBody = await response.text();
            throw new Error(`kaltura-video-details action failed with status ${response.status}: ${errorBody}`);
        }
        const responseJson = await response.json();
        // App Builder actions often wrap their response in a "body" object
        return responseJson.body || responseJson;
    } catch (error) {
        console.error("Error calling kaltura-video-details action:", error.message);
        // Return minimal data or throw, depending on desired error handling
        return { entryId: entryId, title: `Title (Error: ${error.message})`, description: "Description (Error fetching details)" };
    }
}


async function main(params) {
    console.log("kaltura-event-handler received event:", JSON.stringify(params, null, 2));

    // Extract parameters passed to the action (from manifest.yml or event forwarding)
    const {
        aem_webhook_url,
        aem_webhook_apikey,
        // These would be needed if calling kaltura-video-details
        kaltura_video_details_action_url, // URL of the kaltura-video-details action
        kaltura_api_url_for_details,      // Kaltura API URL (e.g. https://api.kaltura.com/api_v3/service)
        kaltura_ks_for_details            // Kaltura KS/access_token for kaltura-video-details
    } = params;

    // The actual event payload from Adobe I/O Events (from Kaltura)
    // This structure depends on Kaltura's webhook format and I/O Event transformation.
    // We assume `params.event` holds the core event data.
    const kalturaEvent = params.event || params; // If the action is directly invoked with the event as root
    
    // --- Basic Event Parsing ---
    // This is highly dependent on the actual event structure from Kaltura.
    // Example: Kaltura webhooks often have `eventType` and `objectType`.
    // The `entryId` might be nested within an `object` or `entry` field.
    let entryId;
    let eventType = kalturaEvent.eventType || "UNKNOWN_EVENT_TYPE";

    if (kalturaEvent.object && kalturaEvent.object.id && (kalturaEvent.object.objectType === 'KalturaMediaEntry' || kalturaEvent.object.objectType === 'KalturaBaseEntry')) {
        entryId = kalturaEvent.object.id;
    } else if (kalturaEvent.entryId) { // Some events might have entryId at the root
        entryId = kalturaEvent.entryId;
    } else if (kalturaEvent.entry_id) { // Or entry_id
        entryId = kalturaEvent.entry_id;
    }
    // Add more parsing logic based on actual Kaltura event structures you subscribe to.

    if (!entryId) {
        console.error("Could not extract entryId from Kaltura event:", JSON.stringify(kalturaEvent));
        return { statusCode: 400, body: { error: "Could not extract entryId from event." } };
    }

    console.log(`Processing Kaltura event: type='${eventType}', entryId='${entryId}'`);

    if (!aem_webhook_url || !aem_webhook_apikey) {
        console.error("AEM webhook URL or API key not configured for the action.");
        return { statusCode: 500, body: { error: "AEM webhook integration not configured." } };
    }

    let videoDetailsPayload = {
        entryId: entryId,
        eventType: eventType,
        // Default minimal data if details action is not called or fails
        title: "Title (pending details)",
        description: "Description (pending details)"
    };

    // (Optional but Recommended) Call kaltura-video-details to get full metadata
    if (kaltura_video_details_action_url && kaltura_api_url_for_details && kaltura_ks_for_details) {
        try {
            const details = await getVideoDetails(params, kaltura_video_details_action_url, kaltura_api_url_for_details, kaltura_ks_for_details, entryId);
            // Merge or replace with more complete details
            videoDetailsPayload = { ...videoDetailsPayload, ...details }; // details from kaltura-video-details will override defaults
            console.log("Successfully fetched details for entryId:", entryId);
        } catch (e) {
            console.warn(`Failed to fetch full video details for entryId ${entryId}: ${e.message}. Proceeding with minimal data.`);
            // videoDetailsPayload already contains minimal data, can add error info if desired
            videoDetailsPayload.detailsError = e.message;
        }
    } else {
        console.warn("kaltura-video-details action not configured to be called. Proceeding with minimal data from event.");
    }


    // Notify AEM Servlet
    console.log("Notifying AEM servlet at:", aem_webhook_url);
    try {
        const response = await fetch(aem_webhook_url, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-Kaltura-Webhook-Api-Key': aem_webhook_apikey
            },
            body: JSON.stringify(videoDetailsPayload)
        });

        const responseText = await response.text();
        if (!response.ok) {
            console.error(`AEM webhook notification failed with status ${response.status}: ${responseText}`);
            return {
                statusCode: 500,
                body: {
                    error: "Failed to notify AEM.",
                    aem_response_status: response.status,
                    aem_response_body: responseText
                }
            };
        }

        console.log("AEM servlet notified successfully. Response:", responseText);
        return {
            statusCode: 200,
            body: {
                message: "Kaltura event processed and AEM notified.",
                entryId: entryId,
                aem_response: JSON.parse(responseText) // Assuming AEM returns JSON
            }
        };

    } catch (error) {
        console.error("Error notifying AEM servlet:", error);
        return {
            statusCode: 500,
            body: { error: "Error during AEM notification.", details: error.message }
        };
    }
}

module.exports = { main };
