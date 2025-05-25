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
        kaltura_access_token, // This can also be 'ks'
        entry_id
    } = params;

    const ks = params.ks || kaltura_access_token; // Allow 'ks' as an alias

    if (!kaltura_api_url || !ks || !entry_id) {
        return {
            statusCode: 400,
            body: { error: "Missing required parameters: kaltura_api_url, kaltura_access_token (or ks), entry_id." }
        };
    }

    try {
        // Step 1: Get Playable Flavors
        console.log(`Fetching playable flavors for entry ID: ${entry_id}`);
        const flavorAssets = await callKalturaApi(kaltura_api_url, 'flavorasset', 'getWebPlayableByEntryId', { entryId: entry_id }, ks);

        const playbackUrls = [];
        if (Array.isArray(flavorAssets)) {
            flavorAssets.forEach(asset => {
                // Construct direct URL if not fully provided (depends on Kaltura setup)
                // For cloud Kaltura, the URL is usually complete.
                // Example: Kaltura CDN URL construction: `${cdnHost}/p/${partnerId}/sp/${partnerId}00/playManifest/entryId/${entry_id}/flavorId/${asset.flavorParamsId}/format/applehttp/protocol/https/a.m3u8`
                // However, `getWebPlayableByEntryId` should ideally return full URLs or enough info.
                // The response from `getWebPlayableByEntryId` often contains a `url` property directly.
                // We need to inspect the actual response structure to be sure.
                // For now, assuming `asset.url` exists and is the direct playback URL.

                let playbackUrl = asset.url; // This might not be present or might need construction.
                // If asset.deliveryProfileId is present, it might imply a more complex URL construction
                // or that the URL is already complete.
                // For simplicity, we'll assume `asset.url` is the key if present, or we might need to build it.
                // The `getWebPlayableByEntryId` is supposed to return direct URLs.

                // A more robust way would be to check asset properties like fileExt, containerFormat, tags
                // to determine HLS, DASH, MP4 etc. and potentially construct URLs if only partial data is given.
                // The API documentation or a sample response would clarify this.
                // Let's assume for now the 'url' field is what we need or can be directly used.
                 if (!playbackUrl && asset.id && asset.entryId && asset.flavorParamsId) {
                     // Fallback: Try to construct a generic URL if a direct one isn't provided.
                     // This is highly dependent on Kaltura setup (SaaS vs On-prem, CDN configuration).
                     // This is a common pattern for SaaS:
                     // playbackUrl = `${kaltura_api_url.replace('/api_v3', '')}/p/${asset.partnerId}/sp/${asset.partnerId}00/playManifest/entryId/${asset.entryId}/flavorId/${asset.flavorParamsId}/format/applehttp/protocol/https/a.m3u8`; // for HLS
                     // For now, we will rely on the `url` field from the API.
                 }


                if (playbackUrl) {
                     playbackUrls.push({
                        type: asset.fileExt || 'unknown', // e.g., m3u8, mpd, mp4
                        mimeType: asset.mimeType || 'application/octet-stream',
                        url: playbackUrl,
                        width: asset.width,
                        height: asset.height,
                        bitrate: asset.bitrate,
                        tags: asset.tags ? asset.tags.split(',') : [], // e.g., 'hls', 'dash', 'mbr'
                        flavorId: asset.flavorParamsId, // ID of the flavor params
                        assetId: asset.id // ID of the flavor asset
                    });
                }
            });
        } else {
            console.warn("flavorAssets response was not an array or was empty:", flavorAssets);
        }


        // Step 2: Get Media Entry details (title, description, etc.)
        console.log(`Fetching media details for entry ID: ${entry_id}`);
        const mediaDetails = await callKalturaApi(kaltura_api_url, 'media', 'get', { entryId: entry_id }, ks);

        const responseBody = {
            entryId: mediaDetails.id,
            title: mediaDetails.name,
            description: mediaDetails.description,
            duration: mediaDetails.duration, // in seconds
            thumbnailUrl: mediaDetails.thumbnailUrl, // Default thumbnail
            // For more thumbnails: mediaDetails.dataUrl (if using dynamic thumbs) or related assets
            createdAt: mediaDetails.createdAt, // Unix timestamp
            updatedAt: mediaDetails.updatedAt, // Unix timestamp
            tags: mediaDetails.tags ? mediaDetails.tags.split(',') : [],
            playbackUrls: playbackUrls,
            // Raw responses for debugging if needed by client
            // rawFlavorData: flavorAssets,
            // rawMediaData: mediaDetails
        };

        return {
            statusCode: 200,
            body: responseBody
        };

    } catch (error) {
        console.error("Kaltura video details process failed:", error);
        return {
            statusCode: 500,
            body: {
                error: "Kaltura video details retrieval failed.",
                details: error.message,
                stack: error.stack // Optional for debugging
            }
        };
    }
}

module.exports = { main };
