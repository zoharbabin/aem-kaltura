const { main: kalturaGenerateThumbnailAction } = require('../actions/kaltura-generate-thumbnail/index');

// Mock node-fetch
const mockFetch = jest.fn();
jest.mock('node-fetch', () => (...args) => mockFetch(...args));

// Helper to create a consistent API response structure
const kalturaApiResponse = (data, isError = false, errorMessage = 'API Error', errorCode = 'API_ERROR_CODE') => {
    if (isError) {
        // Simulate a direct network or severe API error
        return {
            ok: false,
            status: 500,
            statusText: 'Internal Server Error',
            text: async () => JSON.stringify({ objectType: "KalturaAPIException", message: errorMessage, code: errorCode }),
            json: async () => ({ objectType: "KalturaAPIException", message: errorMessage, code: errorCode })
        };
    }
    // Simulate Kaltura's 200 OK response which might contain an API error object in the body
    if (data && data.objectType === "KalturaAPIException") {
         return {
            ok: true, 
            status: 200,
            statusText: 'OK',
            text: async () => JSON.stringify(data),
            json: async () => data
        };
    }
    return {
        ok: true,
        status: 200,
        statusText: 'OK',
        text: async () => JSON.stringify(data),
        json: async () => data
    };
};

describe('Kaltura Generate Thumbnail Action', () => {
    const baseParams = {
        kaltura_api_url: 'https://fake.kaltura.com/api_v3/service',
        kaltura_access_token: 'fake_ks_token',
        entry_id: 'test_entry_123',
        timecode_ms: 5000, // 5 seconds
        set_as_default: true
    };

    beforeEach(() => {
        mockFetch.mockClear();
    });

    test('should successfully generate thumbnail, set as default, and fetch new URL', async () => {
        const mockGeneratedThumbnailAsset = { id: 'thumb_asset_xyz123', url: 'https://cdn.kaltura.com/path/to/new_thumb_temp.jpg' };
        // setAsDefault often returns an empty success or the entry object, let's assume empty for now
        const mockSetAsDefaultResponse = { objectType: "KalturaMediaEntry" }; // Or simply {} or true
        const mockMediaGetResponse = { id: baseParams.entry_id, thumbnailUrl: 'https://cdn.kaltura.com/path/to/final_default_thumb.jpg' };

        mockFetch
            .mockResolvedValueOnce(kalturaApiResponse(mockGeneratedThumbnailAsset)) // thumbnail.generateByTimecode
            .mockResolvedValueOnce(kalturaApiResponse(mockSetAsDefaultResponse))    // thumbnail.setAsDefault
            .mockResolvedValueOnce(kalturaApiResponse(mockMediaGetResponse));      // media.get

        const response = await kalturaGenerateThumbnailAction(baseParams);

        expect(response.statusCode).toBe(200);
        expect(response.body.status).toBe('success');
        expect(response.body.message).toContain('Thumbnail generated and set as default');
        expect(response.body.thumbnail_asset_id).toBe('thumb_asset_xyz123');
        expect(response.body.new_thumbnail_url).toBe('https://cdn.kaltura.com/path/to/final_default_thumb.jpg');
        expect(response.body.kaltura_entry_id).toBe(baseParams.entry_id);

        expect(mockFetch).toHaveBeenCalledTimes(3);
        // Call 1: thumbnail.generateByTimecode
        expect(mockFetch.mock.calls[0][0]).toContain('/thumbnail/action/generateByTimecode');
        expect(mockFetch.mock.calls[0][1].body.toString()).toContain(`entryId=${baseParams.entry_id}`);
        expect(mockFetch.mock.calls[0][1].body.toString()).toContain(`timeOffset=${baseParams.timecode_ms}`);
        // Call 2: thumbnail.setAsDefault
        expect(mockFetch.mock.calls[1][0]).toContain('/thumbnail/action/setAsDefault');
        expect(mockFetch.mock.calls[1][1].body.toString()).toContain(`entryId=${baseParams.entry_id}`);
        expect(mockFetch.mock.calls[1][1].body.toString()).toContain(`thumbAssetId=thumb_asset_xyz123`);
        // Call 3: media.get
        expect(mockFetch.mock.calls[2][0]).toContain('/media/action/get');
        expect(mockFetch.mock.calls[2][1].body.toString()).toContain(`entryId=${baseParams.entry_id}`);
    });

    test('should generate thumbnail but not set as default if set_as_default is false', async () => {
        const paramsNoSetDefault = { ...baseParams, set_as_default: false };
        const mockGeneratedThumbnailAsset = { id: 'thumb_asset_abc456', url: 'https://cdn.kaltura.com/path/to/another_thumb.jpg' };
        // media.get will be called to fetch current default thumbnail, not necessarily the one just generated
        const mockMediaGetResponse = { id: baseParams.entry_id, thumbnailUrl: 'https://cdn.kaltura.com/path/to/existing_default_thumb.jpg' };

        mockFetch
            .mockResolvedValueOnce(kalturaApiResponse(mockGeneratedThumbnailAsset)) // thumbnail.generateByTimecode
            .mockResolvedValueOnce(kalturaApiResponse(mockMediaGetResponse));      // media.get (thumbnail.setAsDefault is skipped)

        const response = await kalturaGenerateThumbnailAction(paramsNoSetDefault);

        expect(response.statusCode).toBe(200);
        expect(response.body.status).toBe('success');
        expect(response.body.message).toContain('Thumbnail generated successfully'); // Not "set as default"
        expect(response.body.thumbnail_asset_id).toBe('thumb_asset_abc456');
        expect(response.body.new_thumbnail_url).toBe('https://cdn.kaltura.com/path/to/existing_default_thumb.jpg'); // Existing default

        expect(mockFetch).toHaveBeenCalledTimes(2); // Only generateByTimecode and media.get
        expect(mockFetch.mock.calls[0][0]).toContain('/thumbnail/action/generateByTimecode');
        expect(mockFetch.mock.calls[1][0]).toContain('/media/action/get');
    });

    test('should return 400 if required parameters are missing', async () => {
        const params = { ...baseParams, entry_id: undefined };
        const response = await kalturaGenerateThumbnailAction(params);
        expect(response.statusCode).toBe(400);
        expect(response.body.error).toContain("Missing required parameters");
    });

    test('should handle error during thumbnail.generateByTimecode', async () => {
        mockFetch.mockResolvedValueOnce(kalturaApiResponse({objectType: "KalturaAPIException", message: "Generation failed"}));
        const response = await kalturaGenerateThumbnailAction(baseParams);
        expect(response.statusCode).toBe(500);
        expect(response.body.error).toContain("Kaltura thumbnail generation process failed");
        expect(response.body.details).toContain("Generation failed");
    });

    test('should handle error during thumbnail.setAsDefault', async () => {
        const mockGeneratedThumbnailAsset = { id: 'thumb_asset_fail_set' };
        mockFetch
            .mockResolvedValueOnce(kalturaApiResponse(mockGeneratedThumbnailAsset)) // thumbnail.generateByTimecode (success)
            .mockResolvedValueOnce(kalturaApiResponse({objectType: "KalturaAPIException", message: "Set as default failed"})); // thumbnail.setAsDefault (fail)

        const response = await kalturaGenerateThumbnailAction(baseParams);
        expect(response.statusCode).toBe(500);
        expect(response.body.details).toContain("Set as default failed");
    });

    test('should handle error during media.get after successful generation and set default', async () => {
        const mockGeneratedThumbnailAsset = { id: 'thumb_asset_fail_get' };
        mockFetch
            .mockResolvedValueOnce(kalturaApiResponse(mockGeneratedThumbnailAsset)) // thumbnail.generateByTimecode
            .mockResolvedValueOnce(kalturaApiResponse({}))    // thumbnail.setAsDefault (success)
            .mockResolvedValueOnce(kalturaApiResponse({objectType: "KalturaAPIException", message: "Media get failed"})); // media.get (fail)

        const response = await kalturaGenerateThumbnailAction(baseParams);
        // The previous steps succeeded, so the core task is done.
        // The action might still return success for generation, but with a warning or null thumbnail URL.
        // Current implementation would throw, leading to 500. This is acceptable.
        expect(response.statusCode).toBe(500);
        expect(response.body.details).toContain("Media get failed");
    });
    
    test('should use "ks" parameter if kaltura_access_token is not provided', async () => {
        const paramsWithKs = {
            kaltura_api_url: baseParams.kaltura_api_url,
            ks: 'alternative_ks_token_for_thumb',
            entry_id: baseParams.entry_id,
            timecode_ms: baseParams.timecode_ms
        };
        const mockGeneratedThumbnailAsset = { id: 'thumb_asset_ks' };
        const mockMediaGetResponse = { id: baseParams.entry_id, thumbnailUrl: 'https://cdn.kaltura.com/ks_thumb.jpg' };

        mockFetch
            .mockResolvedValueOnce(kalturaApiResponse(mockGeneratedThumbnailAsset))
            .mockResolvedValueOnce(kalturaApiResponse({})) 
            .mockResolvedValueOnce(kalturaApiResponse(mockMediaGetResponse));
        
        await kalturaGenerateThumbnailAction(paramsWithKs);

        expect(mockFetch).toHaveBeenCalledTimes(3);
        expect(mockFetch.mock.calls[0][1].body.toString()).toContain('ks=alternative_ks_token_for_thumb');
        expect(mockFetch.mock.calls[1][1].body.toString()).toContain('ks=alternative_ks_token_for_thumb');
        expect(mockFetch.mock.calls[2][1].body.toString()).toContain('ks=alternative_ks_token_for_thumb');
    });
});
