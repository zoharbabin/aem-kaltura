const { main: videoUploadAction } = require('../actions/video-upload/index');
const FormData = require('form-data'); // Used by the action, so good to be aware of

// Mock node-fetch
const mockFetch = jest.fn();
jest.mock('node-fetch', () => (...args) => mockFetch(...args));

// Helper to create a consistent API response structure
const kalturaApiResponse = (data, objectType = null, error = null) => {
    if (error) {
        return {
            ok: false,
            status: error.status || 500,
            statusText: error.statusText || 'Internal Server Error',
            text: async () => JSON.stringify({
                objectType: "KalturaAPIException",
                message: error.message,
                code: error.code || "API_ERROR"
            }),
            json: async () => ({
                objectType: "KalturaAPIException",
                message: error.message,
                code: error.code || "API_ERROR"
            })
        };
    }
    const responseBody = objectType ? { ...data, objectType } : data;
    return {
        ok: true,
        status: 200,
        statusText: 'OK',
        text: async () => JSON.stringify(responseBody),
        json: async () => responseBody
    };
};


describe('Kaltura Video Upload Action', () => {
    const baseParams = {
        kaltura_api_url: 'https://fake.kaltura.com/api_v3/service',
        kaltura_access_token: 'fake_ks_token',
        videoFile: Buffer.from('fake video content').toString('base64'), // Base64 encoded
        video_filename: 'test_video.mp4',
        video_title: 'Test Video Title',
        video_description: 'A great test video.'
    };

    beforeEach(() => {
        mockFetch.mockClear();
    });

    test('should successfully upload a video following all Kaltura steps', async () => {
        // Mock responses for each step
        mockFetch.mockResolvedValueOnce(kalturaApiResponse({ id: 'fake_entry_id_123', name: baseParams.video_title }, 'KalturaMediaEntry')); // media.add
        mockFetch.mockResolvedValueOnce(kalturaApiResponse({ id: 'fake_upload_token_456' }, 'KalturaUploadToken')); // uploadToken.add
        mockFetch.mockResolvedValueOnce(kalturaApiResponse({}, 'KalturaUploadToken')); // uploadToken.upload (often returns the token object or empty on success)
        mockFetch.mockResolvedValueOnce(kalturaApiResponse({ id: 'fake_entry_id_123', name: baseParams.video_title, description: baseParams.video_description }, 'KalturaMediaEntry')); // media.addContent

        const response = await videoUploadAction(baseParams);

        expect(response.statusCode).toBe(200);
        expect(response.body.message).toBe("Video uploaded and processed successfully.");
        expect(response.body.entryId).toBe('fake_entry_id_123');
        expect(response.body.name).toBe(baseParams.video_title);

        // Verify API calls
        expect(mockFetch).toHaveBeenCalledTimes(4);
        const calls = mockFetch.mock.calls;

        // 1. media.add
        expect(calls[0][0]).toContain('/media/action/add');
        expect(calls[0][1].body.toString()).toContain('entry%3AobjectType=KalturaMediaEntry');
        expect(calls[0][1].body.toString()).toContain(`entry%3Aname=${encodeURIComponent(baseParams.video_title)}`);

        // 2. uploadToken.add
        expect(calls[1][0]).toContain('/uploadtoken/action/add');

        // 3. uploadToken.upload
        expect(calls[2][0]).toContain('/uploadtoken/action/upload');
        expect(calls[2][1].body).toBeInstanceOf(FormData); // Check if FormData was used
        // Note: Deeper inspection of FormData content is complex with mocks.
        // We trust the 'form-data' library and our action's construction.

        // 4. media.addContent
        expect(calls[3][0]).toContain('/media/action/addContent');
        expect(calls[3][1].body.toString()).toContain('entryId=fake_entry_id_123');
        expect(calls[3][1].body.toString()).toContain('resource%3Atoken=fake_upload_token_456');
    });

    test('should return 400 if required parameters are missing', async () => {
        const params = { ...baseParams, kaltura_access_token: undefined };
        const response = await videoUploadAction(params);
        expect(response.statusCode).toBe(400);
        expect(response.body.error).toContain("Missing required parameters");
    });

    test('should return 400 for invalid base64 videoFile data', async () => {
        const params = { ...baseParams, videoFile: "this is not base64" };
        const response = await videoUploadAction(params);
        expect(response.statusCode).toBe(400);
        expect(response.body.error).toContain("Invalid base64 videoFile data");
    });

    test('should handle error during media.add step', async () => {
        mockFetch.mockResolvedValueOnce(kalturaApiResponse(null, null, { message: 'Media add failed' }));
        const response = await videoUploadAction(baseParams);
        expect(response.statusCode).toBe(500);
        expect(response.body.error).toContain("Kaltura video upload process failed");
        expect(response.body.details).toContain("Media add failed");
    });

    test('should handle error during uploadToken.add step', async () => {
        mockFetch.mockResolvedValueOnce(kalturaApiResponse({ id: 'fake_entry_id_err' }, 'KalturaMediaEntry')); // media.add success
        mockFetch.mockResolvedValueOnce(kalturaApiResponse(null, null, { message: 'Upload token add failed' })); // uploadToken.add fail
        const response = await videoUploadAction(baseParams);
        expect(response.statusCode).toBe(500);
        expect(response.body.details).toContain("Upload token add failed");
    });

    test('should handle error during uploadToken.upload step', async () => {
        mockFetch.mockResolvedValueOnce(kalturaApiResponse({ id: 'fake_entry_id_err2' }, 'KalturaMediaEntry'));
        mockFetch.mockResolvedValueOnce(kalturaApiResponse({ id: 'fake_upload_token_err2' }, 'KalturaUploadToken'));
        mockFetch.mockResolvedValueOnce(kalturaApiResponse(null, null, { message: 'File upload itself failed' })); // uploadToken.upload fail
        const response = await videoUploadAction(baseParams);
        expect(response.statusCode).toBe(500);
        expect(response.body.details).toContain("File upload itself failed");
    });

    test('should handle error during media.addContent step', async () => {
        mockFetch.mockResolvedValueOnce(kalturaApiResponse({ id: 'fake_entry_id_err3' }, 'KalturaMediaEntry'));
        mockFetch.mockResolvedValueOnce(kalturaApiResponse({ id: 'fake_upload_token_err3' }, 'KalturaUploadToken'));
        mockFetch.mockResolvedValueOnce(kalturaApiResponse({}, 'KalturaUploadToken')); // uploadToken.upload success
        mockFetch.mockResolvedValueOnce(kalturaApiResponse(null, null, { message: 'Media addContent failed' })); // media.addContent fail
        const response = await videoUploadAction(baseParams);
        expect(response.statusCode).toBe(500);
        expect(response.body.details).toContain("Media addContent failed");
    });

    test('should handle Kaltura API exception (e.g. invalid KS)', async () => {
        // Simulate an API exception from Kaltura (e.g., an invalid KS token)
        const apiErrorResponse = {
            objectType: "KalturaAPIException",
            message: "Invalid KS (session)",
            code: "INVALID_KS",
            args: []
        };
         mockFetch.mockResolvedValueOnce({ // Mocking the raw fetch response
            ok: true, // Kaltura API often returns 200 OK but with an error object in body
            status: 200,
            text: async () => JSON.stringify(apiErrorResponse),
            json: async () => apiErrorResponse
        });
        const response = await videoUploadAction(baseParams);
        expect(response.statusCode).toBe(500);
        expect(response.body.error).toContain("Kaltura video upload process failed");
        expect(response.body.details).toContain("Invalid KS (session)");
    });

});
