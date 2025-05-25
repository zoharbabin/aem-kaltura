const fetch = require('node-fetch');
const FormData = require('form-data');
const { Buffer } = require('buffer'); // For Base64 decoding

// Helper function to make Kaltura API calls
async function callKalturaApi(apiUrlBase, service, action, params, accessToken, isFileUpload = false, fileBuffer = null, fileName = 'video.mp4') {
    const apiUrl = `${apiUrlBase}/${service}/action/${action}`;
    const allParams = new URLSearchParams({
        ks: accessToken,
        format: 1, // JSON format
        ...params
    });

    let response;
    if (isFileUpload) {
        if (!fileBuffer) {
            throw new Error("fileBuffer is required for file upload.");
        }
        const formData = new FormData();
        formData.append('ks', accessToken);
        formData.append('uploadTokenId', params.uploadTokenId); // Specific to uploadToken.upload
        formData.append('fileData', fileBuffer, { filename: fileName });
        // Kaltura might expect other params like resume, finalChunk in the form body too
        if (params.resume !== undefined) formData.append('resume', params.resume);
        if (params.finalChunk !== undefined) formData.append('finalChunk', params.finalChunk);


        console.log(`Calling Kaltura (file upload): ${apiUrl} with uploadTokenId: ${params.uploadTokenId}`);
        response = await fetch(apiUrl, {
            method: 'POST',
            body: formData,
            headers: formData.getHeaders() // form-data library helps set Content-Type correctly
        });
    } else {
        console.log(`Calling Kaltura: ${apiUrl} with params: ${allParams.toString()}`);
        response = await fetch(apiUrl, {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: allParams
        });
    }

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
        // If parsing failed but response was ok, it might be non-JSON (should not happen with format=1)
        if (response.ok && e instanceof SyntaxError) {
             console.warn(`Warning: Could not parse JSON response for ${service}.${action}, but status was OK. Response: ${responseText}`);
             return { rawResponse: responseText }; // Or handle as error
        }
        throw e; // Re-throw other errors
    }
}


async function main(params) {
    const {
        kaltura_api_url,
        kaltura_access_token,
        videoFile, // Assuming this is a base64 encoded string
        video_filename = 'uploaded_video.mp4', // Default filename if not provided
        video_title,
        video_description
    } = params;

    if (!kaltura_api_url || !kaltura_access_token || !videoFile) {
        return {
            statusCode: 400,
            body: { error: "Missing required parameters: kaltura_api_url, kaltura_access_token, videoFile." }
        };
    }

    let videoBuffer;
    try {
        videoBuffer = Buffer.from(videoFile, 'base64');
    } catch (e) {
        console.error("Error decoding base64 video file:", e);
        return { statusCode: 400, body: { error: "Invalid base64 videoFile data." } };
    }

    try {
        // Step 1: Add Media Entry
        const mediaAddParams = {
            'entry:objectType': 'KalturaMediaEntry',
            'entry:mediaType': 1, // KalturaMediaType.VIDEO (usually 1)
        };
        if (video_title) mediaAddParams['entry:name'] = video_title;
        if (video_description) mediaAddParams['entry:description'] = video_description;

        console.log("Step 1: Adding Media Entry...");
        const mediaEntry = await callKalturaApi(kaltura_api_url, 'media', 'add', mediaAddParams, kaltura_access_token);
        const entryId = mediaEntry.id;
        if (!entryId) {
            throw new Error("Failed to create media entry or get entryId.");
        }
        console.log(`Media Entry created with ID: ${entryId}`);

        // Step 2: Get Upload Token
        console.log("Step 2: Getting Upload Token...");
        const uploadTokenResponse = await callKalturaApi(kaltura_api_url, 'uploadtoken', 'add', {}, kaltura_access_token);
        const uploadTokenId = uploadTokenResponse.id;
        if (!uploadTokenId) {
            throw new Error("Failed to get upload token ID.");
        }
        console.log(`Upload Token obtained: ${uploadTokenId}`);

        // Step 3: Upload File
        console.log("Step 3: Uploading File...");
        // For simplicity, assume single chunk. For large files, chunking would be needed.
        const uploadParams = {
            uploadTokenId: uploadTokenId,
            resume: false,
            finalChunk: true
        };
        await callKalturaApi(kaltura_api_url, 'uploadtoken', 'upload', uploadParams, kaltura_access_token, true, videoBuffer, video_filename);
        console.log("File uploaded successfully against token.");

        // Step 4: Associate Uploaded File with Media Entry (using media.addContent)
        console.log("Step 4: Associating Uploaded File with Media Entry...");
        const addContentParams = {
            entryId: entryId,
            'resource:objectType': 'KalturaUploadedFileTokenResource',
            'resource:token': uploadTokenId
        };
        const finalEntry = await callKalturaApi(kaltura_api_url, 'media', 'addContent', addContentParams, kaltura_access_token);
        console.log("File associated with Media Entry successfully.");

        return {
            statusCode: 200,
            body: {
                message: "Video uploaded and processed successfully.",
                entryId: finalEntry.id || entryId,
                name: finalEntry.name || video_title,
                description: finalEntry.description || video_description,
                kalturaResponse: finalEntry
            }
        };

    } catch (error) {
        console.error("Kaltura video upload process failed:", error);
        return {
            statusCode: 500,
            body: {
                error: "Kaltura video upload process failed.",
                details: error.message,
                stack: error.stack // Optional: for debugging, might remove in prod
            }
        };
    }
}

module.exports = { main };
