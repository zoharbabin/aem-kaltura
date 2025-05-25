const fetch = require('node-fetch');
const { URLSearchParams } = require('url');

/**
 * Initiates the Kaltura OAuth2 authorization flow.
 *
 * @param {object} params - Parameters for the action.
 * @param {string} params.kaltura_auth_url - Kaltura's authorization endpoint URL.
 * @param {string} params.client_id - Your Kaltura application's Client ID.
 * @param {string} params.redirect_uri - The URI to redirect to after authorization.
 * @param {string} params.state - An opaque value used to maintain state between the request and callback.
 * @param {string} [params.scope] - Optional. The scope of the access request.
 * @returns {object} JSON response with the authorization_url or an error.
 */
async function start_auth(params) {
    const { kaltura_auth_url, client_id, redirect_uri, state, scope } = params;

    if (!kaltura_auth_url || !client_id || !redirect_uri || !state) {
        return {
            statusCode: 400,
            body: {
                error: "Missing required parameters: kaltura_auth_url, client_id, redirect_uri, state."
            }
        };
    }

    const authUrl = new URL(kaltura_auth_url);
    authUrl.searchParams.append('client_id', client_id);
    authUrl.searchParams.append('redirect_uri', redirect_uri);
    authUrl.searchParams.append('response_type', 'code');
    authUrl.searchParams.append('state', state);
    if (scope) {
        authUrl.searchParams.append('scope', scope);
    }

    return {
        statusCode: 200,
        body: {
            authorization_url: authUrl.toString()
        }
    };
}

/**
 * Handles the callback from Kaltura after user authorization.
 * Exchanges the authorization code for an access token.
 *
 * @param {object} params - Parameters for the action.
 * @param {string} params.code - The authorization code received from Kaltura.
 * @param {string} params.state - The state parameter received from Kaltura (should match the one sent in start_auth).
 * @param {string} params.client_id - Your Kaltura application's Client ID.
 * @param {string} params.client_secret - Your Kaltura application's Client Secret.
 * @param {string} params.kaltura_token_url - Kaltura's token endpoint URL.
 * @param {string} params.redirect_uri - The redirect_uri that was used in the initial auth request.
 * @returns {object} JSON response with tokens or an error.
 */
async function handle_callback(params) {
    const { code, state, client_id, client_secret, kaltura_token_url, redirect_uri } = params;

    if (!code || !client_id || !client_secret || !kaltura_token_url || !redirect_uri) {
        return {
            statusCode: 400,
            body: {
                error: "Missing required parameters: code, client_id, client_secret, kaltura_token_url, redirect_uri."
            }
        };
    }

    // It's good practice to verify the 'state' parameter here against a stored value
    // to prevent CSRF attacks, but that's beyond the scope of this basic example.

    const requestBody = new URLSearchParams();
    requestBody.append('grant_type', 'authorization_code');
    requestBody.append('code', code);
    requestBody.append('redirect_uri', redirect_uri);
    requestBody.append('client_id', client_id);
    requestBody.append('client_secret', client_secret);

    try {
        const response = await fetch(kaltura_token_url, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded'
            },
            body: requestBody
        });

        const responseData = await response.json();

        if (!response.ok) {
            return {
                statusCode: response.status,
                body: {
                    error: "Failed to exchange authorization code with Kaltura.",
                    kaltura_error: responseData
                }
            };
        }

        return {
            statusCode: 200,
            body: {
                access_token: responseData.access_token,
                refresh_token: responseData.refresh_token,
                expires_in: responseData.expires_in,
                token_type: responseData.token_type,
                scope: responseData.scope, // if provided by Kaltura
                message: "Successfully exchanged code for tokens."
            }
        };
    } catch (error) {
        console.error("Error calling Kaltura token endpoint:", error);
        return {
            statusCode: 500,
            body: {
                error: "An internal server error occurred while contacting Kaltura token endpoint.",
                details: error.message
            }
        };
    }
}

// Main function for the Adobe I/O Runtime action
async function main(params) {
    const { handler_type, ...actionParams } = params;

    if (handler_type === 'start_auth') {
        return start_auth(actionParams);
    } else if (handler_type === 'handle_callback') {
        return handle_callback(actionParams);
    } else {
        return {
            statusCode: 400,
            body: {
                error: "Missing or invalid 'handler_type' parameter. Must be 'start_auth' or 'handle_callback'."
            }
        };
    }
}

module.exports = { main, start_auth, handle_callback }; // Exporting all for testing and potential direct invocation if needed.
