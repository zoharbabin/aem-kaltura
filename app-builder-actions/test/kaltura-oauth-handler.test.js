const { main, start_auth, handle_callback } = require('../actions/kaltura-oauth-handler/index');
// Mock node-fetch
const mockFetch = jest.fn();
jest.mock('node-fetch', () => (...args) => mockFetch(...args));


describe('Kaltura OAuth Handler Action', () => {

    beforeEach(() => {
        mockFetch.mockClear();
    });

    describe('main function dispatching', () => {
        test('should dispatch to start_auth if handler_type is start_auth', async () => {
            const params = {
                handler_type: 'start_auth',
                kaltura_auth_url: 'https://kaltura.example.com/auth',
                client_id: 'test_client_id',
                redirect_uri: 'https://test.app/callback',
                state: 'test_state_123'
            };
            const response = await main(params);
            expect(response.statusCode).toBe(200);
            expect(response.body.authorization_url).toContain('client_id=test_client_id');
        });

        test('should dispatch to handle_callback if handler_type is handle_callback', async () => {
            mockFetch.mockResolvedValueOnce({
                ok: true,
                json: async () => ({ access_token: 'fake_access_token' })
            });
            const params = {
                handler_type: 'handle_callback',
                code: 'auth_code_123',
                state: 'test_state_456', // Ideally, verify this matches a stored state
                client_id: 'test_client_id',
                client_secret: 'test_client_secret',
                kaltura_token_url: 'https://kaltura.example.com/token',
                redirect_uri: 'https://test.app/callback'
            };
            const response = await main(params);
            expect(response.statusCode).toBe(200);
            expect(response.body.access_token).toBe('fake_access_token');
        });

        test('should return 400 if handler_type is missing or invalid', async () => {
            const response = await main({ some_other_param: 'value' });
            expect(response.statusCode).toBe(400);
            expect(response.body.error).toContain("Missing or invalid 'handler_type' parameter");
        });
    });

    describe('start_auth function', () => {
        test('should construct the correct authorization URL', async () => {
            const params = {
                kaltura_auth_url: 'https://kaltura.example.com/auth',
                client_id: 'test_client_id',
                redirect_uri: 'https://test.app/callback',
                state: 'test_state_123',
                scope: 'read:videos write:videos'
            };
            const response = await start_auth(params);
            expect(response.statusCode).toBe(200);
            const expectedUrl = 'https://kaltura.example.com/auth?client_id=test_client_id&redirect_uri=https%3A%2F%2Ftest.app%2Fcallback&response_type=code&state=test_state_123&scope=read%3Avideos+write%3Avideos';
            expect(response.body.authorization_url).toBe(expectedUrl);
        });

        test('should return 400 if required parameters are missing for start_auth', async () => {
            const params = { kaltura_auth_url: 'https://kaltura.example.com/auth' }; // Missing client_id, redirect_uri, state
            const response = await start_auth(params);
            expect(response.statusCode).toBe(400);
            expect(response.body.error).toContain("Missing required parameters");
        });
    });

    describe('handle_callback function', () => {
        test('should exchange authorization code for tokens successfully', async () => {
            const mockTokenResponse = {
                access_token: 'mock_access_token_123',
                refresh_token: 'mock_refresh_token_456',
                expires_in: 3600,
                token_type: 'Bearer',
                scope: 'read:videos'
            };
            mockFetch.mockResolvedValueOnce({
                ok: true,
                json: async () => mockTokenResponse
            });

            const params = {
                code: 'auth_code_123',
                state: 'test_state_789',
                client_id: 'test_client_id_2',
                client_secret: 'test_client_secret_2',
                kaltura_token_url: 'https://kaltura.example.com/token',
                redirect_uri: 'https://test.app/callback'
            };
            const response = await handle_callback(params);

            expect(response.statusCode).toBe(200);
            expect(response.body.access_token).toBe('mock_access_token_123');
            expect(response.body.refresh_token).toBe('mock_refresh_token_456');
            expect(response.body.message).toContain("Successfully exchanged code for tokens");

            expect(mockFetch).toHaveBeenCalledTimes(1);
            expect(mockFetch).toHaveBeenCalledWith(
                'https://kaltura.example.com/token',
                expect.objectContaining({
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                })
            );
            const callBody = mockFetch.mock.calls[0][1].body.toString();
            expect(callBody).toContain('grant_type=authorization_code');
            expect(callBody).toContain('code=auth_code_123');
            expect(callBody).toContain('client_id=test_client_id_2');
            expect(callBody).toContain('client_secret=test_client_secret_2');
        });

        test('should return error if Kaltura token exchange fails', async () => {
            mockFetch.mockResolvedValueOnce({
                ok: false,
                status: 401,
                json: async () => ({ error: 'invalid_client', error_description: 'Client authentication failed' })
            });

            const params = {
                code: 'invalid_code',
                state: 'test_state_abc',
                client_id: 'test_client_id_3',
                client_secret: 'test_client_secret_3',
                kaltura_token_url: 'https://kaltura.example.com/token',
                redirect_uri: 'https://test.app/callback'
            };
            const response = await handle_callback(params);

            expect(response.statusCode).toBe(401);
            expect(response.body.error).toContain("Failed to exchange authorization code");
            expect(response.body.kaltura_error).toEqual({ error: 'invalid_client', error_description: 'Client authentication failed' });
        });

        test('should return 400 if required parameters are missing for handle_callback', async () => {
            const params = { code: 'some_code' }; // Missing other required params
            const response = await handle_callback(params);
            expect(response.statusCode).toBe(400);
            expect(response.body.error).toContain("Missing required parameters");
        });

        test('should handle network errors during token exchange', async () => {
            mockFetch.mockRejectedValueOnce(new Error("Network connection failed"));
             const params = {
                code: 'auth_code_net_error',
                state: 'test_state_net',
                client_id: 'test_client_id_net',
                client_secret: 'test_client_secret_net',
                kaltura_token_url: 'https://kaltura.example.com/token',
                redirect_uri: 'https://test.app/callback'
            };
            const response = await handle_callback(params);
            expect(response.statusCode).toBe(500);
            expect(response.body.error).toContain("An internal server error occurred");
            expect(response.body.details).toBe("Network connection failed");
        });
    });
});
