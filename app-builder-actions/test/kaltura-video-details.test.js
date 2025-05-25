const { main: kalturaVideoDetailsAction } = require('../actions/kaltura-video-details/index');

// Mock node-fetch
const mockFetch = jest.fn();
jest.mock('node-fetch', () => (...args) => mockFetch(...args));

// Helper to create a consistent API response structure
const kalturaApiResponse = (data, isError = false, errorMessage = 'API Error', errorCode = 'API_ERROR_CODE') => {
    if (isError) {
        return {
            ok: false, // Typically for network errors, but Kaltura often returns 200 OK with error object
            status: 500, // Or some other error status
            statusText: 'Internal Server Error',
            text: async () => JSON.stringify({ objectType: "KalturaAPIException", message: errorMessage, code: errorCode }),
            json: async () => ({ objectType: "KalturaAPIException", message: errorMessage, code: errorCode })
        };
    }
    // Simulate Kaltura's 200 OK response even for API errors, error is in the body
    if (data && data.objectType === "KalturaAPIException") {
         return {
            ok: true, // Kaltura often returns 200 OK but with an error object in body
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


describe('Kaltura Video Details Action', () => {
    const baseParams = {
        kaltura_api_url: 'https://fake.kaltura.com/api_v3/service',
        kaltura_access_token: 'fake_ks_token',
        entry_id: 'test_entry_123'
    };

    beforeEach(() => {
        mockFetch.mockClear();
    });

    test('should successfully fetch video details and playable URLs', async () => {
        const mockFlavorAssetsResponse = [
            { id: 'flavor_asset_1', entryId: baseParams.entry_id, flavorParamsId: 'flavor_1', fileExt: 'mp4', width: 1280, height: 720, bitrate: 2000, tags: 'mp4,web', url: 'https://cdn.kaltura.com/path/to/video_720p.mp4', mimeType: 'video/mp4' },
            { id: 'flavor_asset_2', entryId: baseParams.entry_id, flavorParamsId: 'flavor_2', fileExt: 'm3u8', width: 0, height: 0, bitrate: 0, tags: 'hls,mbr', url: 'https://cdn.kaltura.com/path/to/playlist.m3u8', mimeType: 'application/vnd.apple.mpegurl' }
        ];
        const mockMediaGetResponse = {
            id: baseParams.entry_id,
            name: 'Test Video Title',
            description: 'A great description.',
            duration: 120, // seconds
            thumbnailUrl: 'https://cdn.kaltura.com/path/to/thumbnail.jpg',
            createdAt: 1678886400,
            updatedAt: 1678886400,
            tags: 'test,example'
        };

        mockFetch
            .mockResolvedValueOnce(kalturaApiResponse(mockFlavorAssetsResponse)) // flavorAsset.getWebPlayableByEntryId
            .mockResolvedValueOnce(kalturaApiResponse(mockMediaGetResponse));   // media.get

        const response = await kalturaVideoDetailsAction(baseParams);

        expect(response.statusCode).toBe(200);
        expect(response.body.entryId).toBe(baseParams.entry_id);
        expect(response.body.title).toBe('Test Video Title');
        expect(response.body.description).toBe('A great description.');
        expect(response.body.duration).toBe(120);
        expect(response.body.thumbnailUrl).toBe('https://cdn.kaltura.com/path/to/thumbnail.jpg');
        expect(response.body.tags).toEqual(['test', 'example']);
        expect(response.body.playbackUrls).toBeInstanceOf(Array);
        expect(response.body.playbackUrls.length).toBe(2);
        expect(response.body.playbackUrls[0].url).toBe('https://cdn.kaltura.com/path/to/video_720p.mp4');
        expect(response.body.playbackUrls[0].type).toBe('mp4');
        expect(response.body.playbackUrls[1].url).toBe('https://cdn.kaltura.com/path/to/playlist.m3u8');
        expect(response.body.playbackUrls[1].type).toBe('m3u8');

        expect(mockFetch).toHaveBeenCalledTimes(2);
        expect(mockFetch.mock.calls[0][0]).toContain('/flavorasset/action/getWebPlayableByEntryId');
        expect(mockFetch.mock.calls[0][1].body.toString()).toContain(`entryId=${baseParams.entry_id}`);
        expect(mockFetch.mock.calls[1][0]).toContain('/media/action/get');
        expect(mockFetch.mock.calls[1][1].body.toString()).toContain(`entryId=${baseParams.entry_id}`);
    });

    test('should use "ks" parameter if kaltura_access_token is not provided', async () => {
        mockFetch
            .mockResolvedValueOnce(kalturaApiResponse([]))
            .mockResolvedValueOnce(kalturaApiResponse({ id: baseParams.entry_id, name: 'Title' }));

        await kalturaVideoDetailsAction({
            kaltura_api_url: baseParams.kaltura_api_url,
            ks: 'alternative_ks_token',
            entry_id: baseParams.entry_id
        });
        expect(mockFetch.mock.calls[0][1].body.toString()).toContain('ks=alternative_ks_token');
        expect(mockFetch.mock.calls[1][1].body.toString()).toContain('ks=alternative_ks_token');
    });


    test('should return 400 if required parameters are missing', async () => {
        const params = { ...baseParams, entry_id: undefined };
        const response = await kalturaVideoDetailsAction(params);
        expect(response.statusCode).toBe(400);
        expect(response.body.error).toContain("Missing required parameters");
    });

    test('should handle error during flavorAsset.getWebPlayableByEntryId API call', async () => {
        mockFetch.mockResolvedValueOnce(kalturaApiResponse({objectType: "KalturaAPIException", message: "Flavor assets not found"}));

        const response = await kalturaVideoDetailsAction(baseParams);
        expect(response.statusCode).toBe(500);
        expect(response.body.error).toContain("Kaltura video details retrieval failed");
        expect(response.body.details).toContain("Flavor assets not found");
    });

    test('should handle error during media.get API call', async () => {
        mockFetch
            .mockResolvedValueOnce(kalturaApiResponse([])) // flavorAsset success (empty is valid)
            .mockResolvedValueOnce(kalturaApiResponse({objectType: "KalturaAPIException", message: "Media entry not found"})); // media.get fail

        const response = await kalturaVideoDetailsAction(baseParams);
        expect(response.statusCode).toBe(500);
        expect(response.body.details).toContain("Media entry not found");
    });

    test('should handle general network error from fetch', async () => {
        mockFetch.mockRejectedValueOnce(new Error("Network connection failed"));
        const response = await kalturaVideoDetailsAction(baseParams);
        expect(response.statusCode).toBe(500);
        expect(response.body.error).toContain("Kaltura video details retrieval failed");
        expect(response.body.details).toContain("Network connection failed");
    });

    test('should handle empty flavor assets response gracefully', async () => {
        const mockMediaGetResponse = { id: baseParams.entry_id, name: 'Test Video Title' };
        mockFetch
            .mockResolvedValueOnce(kalturaApiResponse([])) // Empty but valid response for flavors
            .mockResolvedValueOnce(kalturaApiResponse(mockMediaGetResponse));

        const response = await kalturaVideoDetailsAction(baseParams);
        expect(response.statusCode).toBe(200);
        expect(response.body.title).toBe('Test Video Title');
        expect(response.body.playbackUrls).toEqual([]);
    });

     test('should handle non-array flavor assets response gracefully', async () => {
        const mockMediaGetResponse = { id: baseParams.entry_id, name: 'Test Video Title' };
        mockFetch
            .mockResolvedValueOnce(kalturaApiResponse({ message: "Unexpected format for flavors" })) // Non-array response
            .mockResolvedValueOnce(kalturaApiResponse(mockMediaGetResponse));

        const response = await kalturaVideoDetailsAction(baseParams);
        expect(response.statusCode).toBe(200); // Action completes with media.get data
        expect(response.body.title).toBe('Test Video Title');
        expect(response.body.playbackUrls).toEqual([]); // playbackUrls should be empty
        // Optionally, log a warning or include a note in the response if this is unexpected.
    });
});
