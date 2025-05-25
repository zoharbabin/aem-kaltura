const fetch = require('node-fetch');
const { URLSearchParams } = require('url');

// Helper function to make Kaltura API calls
async function callKalturaApi(apiUrlBase, service, action, params, accessToken) {
    const apiUrl = `${apiUrlBase}/${service}/action/${action}`;
    const allParams = new URLSearchParams({
        ks: accessToken,
        format: 1, // JSON format
        ...params
    });

    console.log(`Calling Kaltura: ${apiUrl} with params: ${allParams.toString()}`);
    const response = await fetch(apiUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: allParams
    });

    const responseText = await response.text();
    console.log(`Kaltura API response text for ${service}.${action}: ${responseText}`);

    if (!response.ok) {
        throw new Error(`Kaltura API error for ${service}.${action}: ${response.status} ${response.statusText} - ${responseText}`);
    }
    try {
        const jsonResponse = JSON.parse(responseText);
        if (jsonResponse && (jsonResponse.objectType === "KalturaAPIException" || jsonResponse.code)) {
            throw new Error(`Kaltura API Exception for ${service}.${action}: ${jsonResponse.message || responseText}`);
        }
        return jsonResponse;
    } catch (e) {
        if (response.ok && e instanceof SyntaxError) {
             console.warn(`Warning: Could not parse JSON response for ${service}.${action}, but status was OK. Response: ${responseText}`);
             return { rawResponse: responseText };
        }
        throw e;
    }
}

async function main(params) {
    const {
        kaltura_api_url,
        entry_id,
        timecode_ms,
        set_as_default = true // Default to true if not provided
    } = params;

    const ks = params.ks || params.kaltura_access_token;

    if (!kaltura_api_url || !ks || !entry_id || timecode_ms === undefined) {
        return {
            statusCode: 400,
            body: { error: "Missing required parameters: kaltura_api_url, kaltura_access_token (or ks), entry_id, timecode_ms." }
        };
    }

    let newThumbnailAssetId;
    let finalThumbnailUrl = null;

    try {
        // Step 1: Generate Thumbnail by Timecode
        console.log(`Step 1: Generating thumbnail for entry ID ${entry_id} at timecode ${timecode_ms}ms`);
        const generateParams = {
            entryId: entry_id,
            timeOffset: timecode_ms
        };
        const thumbnailAsset = await callKalturaApi(kaltura_api_url, 'thumbnail', 'generateByTimecode', generateParams, ks);
        
        if (!thumbnailAsset || !thumbnailAsset.id) {
            throw new Error("Failed to generate thumbnail or get thumbnail asset ID from response.");
        }
        newThumbnailAssetId = thumbnailAsset.id;
        console.log(`Thumbnail generated successfully. Asset ID: ${newThumbnailAssetId}`);
        // The direct URL of the new thumbnail might be in thumbnailAsset.url, thumbnailAsset.downloadUrl etc.
        // but we will fetch the default one after setting it.

        // Step 2 (Conditional): Set as Default
        if (set_as_default) {
            console.log(`Step 2: Setting thumbnail asset ${newThumbnailAssetId} as default for entry ID ${entry_id}`);
            const setDefaultParams = {
                entryId: entry_id,
                thumbAssetId: newThumbnailAssetId
            };
            await callKalturaApi(kaltura_api_url, 'thumbnail', 'setAsDefault', setDefaultParams, ks);
            console.log(`Thumbnail ${newThumbnailAssetId} successfully set as default.`);
        }

        // Step 3: Fetch new default thumbnail URL from media.get
        console.log(`Step 3: Fetching updated media entry details for entry ID ${entry_id} to get new default thumbnail URL.`);
        const mediaDetails = await callKalturaApi(kaltura_api_url, 'media', 'get', { entryId: entry_id }, ks);
        finalThumbnailUrl = mediaDetails.thumbnailUrl; // This should now reflect the new default thumbnail
        console.log(`New default thumbnail URL for entry ID ${entry_id}: ${finalThumbnailUrl}`);


        return {
            statusCode: 200,
            body: {
                status: "success",
                message: `Thumbnail generated ${set_as_default ? 'and set as default' : 'successfully'}.`,
                thumbnail_asset_id: newThumbnailAssetId,
                new_thumbnail_url: finalThumbnailUrl,
                kaltura_entry_id: entry_id
            }
        };

    } catch (error) {
        console.error(`Kaltura thumbnail generation process failed for entry ID ${entry_id}:`, error);
        return {
            statusCode: 500,
            body: {
                error: "Kaltura thumbnail generation process failed.",
                details: error.message,
                stack: error.stack // Optional for debugging
            }
        };
    }
}

module.exports = { main };
